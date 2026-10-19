package com.lee.psyqa.eval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lee.psyqa.conversation.Turn;
import com.lee.psyqa.retrieval.QueryTranslator;
import com.lee.psyqa.retrieval.RetrievalService;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 多轮检索评估（T4）：eval/conversations.jsonl 里每段对话的追问都带指代（"它""他""后一种"）。
 * 按顺序走每一轮，历史里放题库给的参考回答（模拟线上存的上一轮回答），只评检索、不调生成。
 * <p>
 * rewrite=true：历史传给 RetrievalService，追问被改写成独立的英文问题（线上路径）。
 * rewrite=false：不传历史，追问按单轮处理（对照组）。
 * 主指标是追问那几轮（第 2 轮起）的 recall@k；第 1 轮没有历史，两组相同，只作参照。
 */
@Service
public class ConversationEval {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Dialogue(String id, String note, List<DialogueTurn> turns) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DialogueTurn(String question, String answer, List<EvalService.EvalQuestion.Expected> expected) {}

    /** 一轮的结果。query 是实际用来检索的英文（改写 / 翻译结果），没翻译时为 null。 */
    public record TurnResult(String dialogueId, int turn, String question, String query, int firstHitRank,
                             List<Double> scores, List<String> retrieved, long rewriteMs, int rewriteTokens) {}

    public record Outcome(String summary, Path markdownFile) {}

    private final RetrievalService retrieval;
    private final ObjectMapper objectMapper;

    public ConversationEval(RetrievalService retrieval, ObjectMapper objectMapper) {
        this.retrieval = retrieval;
        this.objectMapper = objectMapper;
    }

    public Outcome run(Path file, int k, boolean rewrite, RetrievalService.QueryMode mode, Boolean rerank, Path outDir) throws IOException {
        RetrievalService.QueryMode m = mode == null ? retrieval.defaultMode() : mode;
        boolean rr = rerank == null ? retrieval.defaultRerank() : rerank;
        List<Dialogue> dialogues = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                dialogues.add(objectMapper.readValue(line, Dialogue.class));
            }
        }

        List<TurnResult> results = new ArrayList<>();
        for (Dialogue d : dialogues) {
            List<Turn> history = new ArrayList<>();
            for (int i = 0; i < d.turns().size(); i++) {
                DialogueTurn t = d.turns().get(i);
                RetrievalService.Result r = retrieval.retrieve(t.question(), rewrite ? List.copyOf(history) : List.of(), k, m, rr);
                int firstHit = 0;
                for (int j = 0; j < r.hits().size(); j++) {
                    if (EvalService.isRelevant(r.hits().get(j), t.expected())) {
                        firstHit = j + 1;
                        break;
                    }
                }
                QueryTranslator.Translation tr = r.translation();
                results.add(new TurnResult(d.id(), i + 1, t.question(), tr == null ? null : tr.text(), firstHit,
                        r.topkScores(), r.hits().stream().map(ConversationEval::describe).toList(),
                        tr == null ? 0 : tr.latencyMs(), tr == null ? 0 : tr.promptTokens() + tr.completionTokens()));
                history.add(new Turn(t.question(), t.answer() == null ? "" : t.answer()));
                System.out.print("\r多轮评估中 " + d.id() + " 第 " + (i + 1) + " 轮");
            }
        }
        System.out.println();

        List<TurnResult> first = results.stream().filter(r -> r.turn() == 1).toList();
        List<TurnResult> followUps = results.stream().filter(r -> r.turn() > 1).toList();
        String summary = """
                追问 %d 轮：recall@1 %s  recall@%d %s  MRR %.3f
                第 1 轮（参照）%d 轮：recall@%d %s
                改写 / 翻译开销：%s
                """.formatted(followUps.size(), pct(recallAt(followUps, 1)), k, pct(recallAt(followUps, k)), mrr(followUps),
                first.size(), k, pct(recallAt(first, k)), cost(results));

        LocalDateTime now = LocalDateTime.now();
        String stamp = now.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + "-conv-"
                + (rewrite ? "rewrite" : "norewrite") + "-" + m.name().toLowerCase(Locale.ROOT) + (rr ? "-rerank" : "");
        Files.createDirectories(outDir);
        Path md = outDir.resolve(stamp + ".md");
        Files.writeString(md, markdown(now, file, k, rewrite, m, summary, results), StandardCharsets.UTF_8);
        StringBuilder jsonl = new StringBuilder();
        for (TurnResult r : results) {
            jsonl.append(objectMapper.writeValueAsString(r)).append('\n');
        }
        Files.writeString(outDir.resolve(stamp + ".jsonl"), jsonl, StandardCharsets.UTF_8);
        return new Outcome(summary, md);
    }

    private static String markdown(LocalDateTime now, Path file, int k, boolean rewrite, RetrievalService.QueryMode m,
                                   String summary, List<TurnResult> results) {
        StringBuilder sb = new StringBuilder("# PsyQA 多轮检索评估 ")
                .append(now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))).append("\n\n");
        sb.append("| 配置 | 值 |\n|---|---|\n")
                .append("| 题库 | ").append(file).append(" |\n")
                .append("| top-k | ").append(k).append(" |\n")
                .append("| 结合历史改写 | ").append(rewrite ? "是" : "否（对照组）").append(" |\n")
                .append("| 查询方式 | ").append(m).append(" |\n\n");
        sb.append("```\n").append(summary).append("```\n\n");
        sb.append("| 对话 | 轮 | 问题 | 实际检索的英文 | 首个命中 | top-1 | top-1 来源 |\n|---|---|---|---|---|---|---|\n");
        for (TurnResult r : results) {
            sb.append("| %s | %d | %s | %s | %s | %.2f | %s |\n".formatted(r.dialogueId(), r.turn(), r.question(),
                    r.query() == null ? "-" : r.query().replace("|", "\\|"),
                    r.firstHitRank() == 0 ? "未命中 ❌" : String.valueOf(r.firstHitRank()),
                    r.scores().isEmpty() ? 0 : r.scores().get(0),
                    r.retrieved().isEmpty() ? "-" : r.retrieved().get(0)));
        }
        return sb.toString();
    }

    static double recallAt(List<TurnResult> rs, int n) {
        return rs.isEmpty() ? 0 : (double) rs.stream().filter(r -> r.firstHitRank() > 0 && r.firstHitRank() <= n).count() / rs.size();
    }

    static double mrr(List<TurnResult> rs) {
        return rs.stream().mapToDouble(r -> r.firstHitRank() == 0 ? 0 : 1.0 / r.firstHitRank()).average().orElse(0);
    }

    private static String cost(List<TurnResult> rs) {
        List<TurnResult> t = rs.stream().filter(r -> r.query() != null).toList();
        return t.isEmpty() ? "无" : "%d 轮，平均 %.0f ms，共 %d tokens".formatted(t.size(),
                t.stream().mapToLong(TurnResult::rewriteMs).average().orElse(0),
                t.stream().mapToInt(TurnResult::rewriteTokens).sum());
    }

    private static String describe(Document d) {
        return d.getMetadata().get("book") + " · " + d.getMetadata().get("chapter");
    }

    private static String pct(double v) {
        return String.format(Locale.ROOT, "%.1f%%", v * 100);
    }
}
