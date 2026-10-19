package com.lee.psyqa.eval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lee.psyqa.config.PsyQaProperties;
import com.lee.psyqa.eval.EvalReport.QuestionResult;
import com.lee.psyqa.eval.EvalReport.RunInfo;
import com.lee.psyqa.guard.CrisisDetector;
import com.lee.psyqa.retrieval.QueryTranslator;
import com.lee.psyqa.retrieval.RetrievalService;
import org.springframework.ai.document.Document;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 检索评估：对题库里每道题走一遍"危机路由 → 检索 → 阈值"，和标注对比，输出指标。
 * <p>
 * 只评检索，不调对话模型：每道题只花一次 embedding 调用，几十道题几秒钟、几乎不花钱，可以反复跑。
 * 路由判断和 AnswerService 保持一致：先 CrisisDetector（同一个方法），再看 top-1 分数是否过阈值。
 * 危机语义层每题多一次 embedding 调用；查询方式不是 ORIGINAL 时，中文题还要多一次对话模型调用（翻译）。
 * 检索走 RetrievalService（和线上同一个方法），不在这里单独调向量库。
 * 回答质量（引用对不对、有没有编）要人工抽查，不在这里。
 */
@Service
public class EvalService {

    /** 题库里的一行。中文题用 question，英文对照用 questionEn（跨语言实验用）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EvalQuestion(String id, String type, String category, String difficulty,
                               String question, String questionEn, String expectedRoute,
                               List<Expected> expected, String note) {

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Expected(String book, String chapter, List<String> evidence) {}
    }

    /**
     * @param english true 时用 questionEn 提问（没有英文版的题仍用中文）
     * @param mode    查询方式；null 表示用配置里的默认值
     */
    public record Options(Path questionsFile, int k, boolean english, RetrievalService.QueryMode mode, Path outDir) {}

    public record Outcome(EvalReport report, Path markdownFile) {}

    private final RetrievalService retrieval;
    private final CrisisDetector crisisDetector;
    private final PsyQaProperties props;
    private final ObjectMapper objectMapper;
    private final Environment env;

    public EvalService(RetrievalService retrieval, CrisisDetector crisisDetector, PsyQaProperties props,
                       ObjectMapper objectMapper, Environment env) {
        this.retrieval = retrieval;
        this.crisisDetector = crisisDetector;
        this.props = props;
        this.objectMapper = objectMapper;
        this.env = env;
    }

    public Outcome run(Options opt) throws IOException {
        List<EvalQuestion> questions = load(opt.questionsFile());
        double threshold = retrieval.threshold();
        RetrievalService.QueryMode mode = opt.mode() == null ? retrieval.defaultMode() : opt.mode();
        LocalDateTime now = LocalDateTime.now();

        List<QuestionResult> results = new ArrayList<>();
        for (EvalQuestion q : questions) {
            String text = opt.english() && q.questionEn() != null && !q.questionEn().isBlank() ? q.questionEn() : q.question();
            results.add(evaluate(q, text, opt.k(), threshold, mode));
            System.out.print("\r评估中 " + results.size() + "/" + questions.size());
        }
        System.out.println();

        RunInfo info = new RunInfo(now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")),
                opt.english() ? "en（questionEn）" : "zh", opt.k(), threshold,
                env.getProperty("spring.ai.openai.embedding.options.model", "?"),
                props.ingest().chunkSizeChars(), props.ingest().chunkOverlapChars(),
                opt.questionsFile().toString(), crisisDetector.threshold(), mode.name(), props.retrieval().hnswEf());
        EvalReport report = new EvalReport(info, results);

        Files.createDirectories(opt.outDir());
        String stamp = now.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + (opt.english() ? "-en" : "-zh")
                + (mode == RetrievalService.QueryMode.ORIGINAL ? "" : "-" + mode.name().toLowerCase());
        Path md = opt.outDir().resolve(stamp + ".md");
        Files.writeString(md, report.markdown(), StandardCharsets.UTF_8);
        // 每题一行 JSON：两次实验可以逐题 diff，看哪些题变好、哪些变差
        StringBuilder jsonl = new StringBuilder();
        for (QuestionResult r : results) {
            jsonl.append(objectMapper.writeValueAsString(r)).append('\n');
        }
        Files.writeString(opt.outDir().resolve(stamp + ".jsonl"), jsonl, StandardCharsets.UTF_8);
        return new Outcome(report, md);
    }

    private QuestionResult evaluate(EvalQuestion q, String text, int k, double threshold, RetrievalService.QueryMode mode) {
        String primaryBook = q.expected() == null || q.expected().isEmpty() ? "" : q.expected().get(0).book();

        // 和 AnswerService 一样：判为危机就不检索
        CrisisDetector.Decision c = crisisDetector.detect(text);
        EvalReport.CrisisInfo crisis = new EvalReport.CrisisInfo(c.layer().name(), c.strongHit(), c.weakHit(), c.crisisScore(), c.theoryScore());
        if (c.crisis()) {
            return new QuestionResult(q.id(), q.type(), q.difficulty(), primaryBook, text,
                    q.expectedRoute(), "CRISIS", 0, false, List.of(), List.of(), crisis, null);
        }

        RetrievalService.Result r = retrieval.retrieve(text, k, mode);
        List<Document> hits = r.hits();
        List<Double> scores = r.topkScores();
        QueryTranslator.Translation t = r.translation();
        EvalReport.TranslationInfo translation = t == null ? null
                : new EvalReport.TranslationInfo(t.text(), t.latencyMs(), t.promptTokens() + t.completionTokens());
        List<String> retrieved = hits.stream().map(EvalService::describe).toList();
        String route = !scores.isEmpty() && scores.get(0) >= threshold ? "ANSWERED" : "REFUSED";

        int firstHit = 0;
        boolean hitInKept = false;
        for (int i = 0; i < hits.size(); i++) {
            if (isRelevant(hits.get(i), q.expected())) {
                if (firstHit == 0) {
                    firstHit = i + 1;
                }
                if (scores.get(i) >= threshold) {
                    hitInKept = true;
                }
            }
        }
        return new QuestionResult(q.id(), q.type(), q.difficulty(), primaryBook, text,
                q.expectedRoute(), route, firstHit, hitInKept, scores, retrieved, crisis, translation);
    }

    /** 块的书名和某条标注一致，且块里出现该条标注的任一证据短语。 */
    static boolean isRelevant(Document d, List<EvalQuestion.Expected> expected) {
        if (expected == null) {
            return false;
        }
        String book = String.valueOf(d.getMetadata().get("book"));
        return expected.stream().anyMatch(e -> e.book().equals(book) && EvidenceMatcher.containsAny(d.getText(), e.evidence()));
    }

    private static String describe(Document d) {
        Map<String, Object> m = d.getMetadata();
        return m.get("book") + " · " + m.get("chapter");
    }

    private List<EvalQuestion> load(Path file) throws IOException {
        List<EvalQuestion> out = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                out.add(objectMapper.readValue(line, EvalQuestion.class));
            }
        }
        return out;
    }
}
