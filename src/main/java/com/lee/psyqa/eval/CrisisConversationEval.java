package com.lee.psyqa.eval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lee.psyqa.conversation.Turn;
import com.lee.psyqa.guard.CrisisDetector;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 多轮危机评估（T7）：eval/crisis-conversations.jsonl 每轮标了 crisis / safe。
 * 按顺序走每一轮，历史里放检测器自己前几轮的判断（和线上一样：判成危机的那一轮存进历史时带 crisis 标记）。
 * 每轮同时跑一次只看这一句的判断，作为对照（= T2 的单轮检测）。只评危机判断，不检索、不生成。
 * <p>
 * sticky=true 的轮次：前面判过危机、这一轮本身是正常问题，期望正常回答但 followUp=true（回答末尾附热线）。
 */
@Service
public class CrisisConversationEval {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Dialogue(String id, String note, List<DialogueTurn> turns) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DialogueTurn(String question, String label, Boolean sticky) {}

    public record TurnResult(String dialogueId, int turn, String question, String label, boolean sticky,
                             boolean singleCrisis, boolean crisis, String layer, boolean judged, long judgeMs, boolean followUp,
                             double crisisScore, double theoryScore, double contextCrisisScore, double contextTheoryScore) {}

    public record Outcome(String summary, Path markdownFile) {}

    private final CrisisDetector detector;
    private final ObjectMapper objectMapper;

    public CrisisConversationEval(CrisisDetector detector, ObjectMapper objectMapper) {
        this.detector = detector;
        this.objectMapper = objectMapper;
    }

    public Outcome run(Path file, Path outDir) throws IOException {
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
                CrisisDetector.Decision single = detector.detect(t.question());
                CrisisDetector.Decision multi = detector.detect(t.question(), List.copyOf(history));
                results.add(new TurnResult(d.id(), i + 1, t.question(), t.label(), Boolean.TRUE.equals(t.sticky()),
                        single.crisis(), multi.crisis(), multi.layer().name(), multi.judged(), multi.judgeMs(), multi.followUp(),
                        multi.crisisScore(), multi.theoryScore(), multi.contextCrisisScore(), multi.contextTheoryScore()));
                history.add(new Turn(t.question(), multi.crisis() ? detector.response() : "", multi.crisis()));
                System.out.print("\r多轮危机评估中 " + d.id() + " 第 " + (i + 1) + " 轮");
            }
        }
        System.out.println();

        List<TurnResult> crisis = results.stream().filter(r -> "crisis".equals(r.label())).toList();
        List<TurnResult> safe = results.stream().filter(r -> "safe".equals(r.label())).toList();
        List<TurnResult> sticky = results.stream().filter(TurnResult::sticky).toList();
        String summary = """
                危机轮 %d：单轮检测识别 %d，多轮检测识别 %d
                安全轮 %d：单轮检测误报 %d，多轮检测误报 %d
                延续轮 %d：回答附热线 %d
                LLM 判定（多轮）：%d / %d 轮调用，平均 %.0f ms
                """.formatted(crisis.size(), crisis.stream().filter(TurnResult::singleCrisis).count(),
                crisis.stream().filter(TurnResult::crisis).count(),
                safe.size(), safe.stream().filter(TurnResult::singleCrisis).count(),
                safe.stream().filter(TurnResult::crisis).count(),
                sticky.size(), sticky.stream().filter(r -> !r.crisis() && r.followUp()).count(),
                results.stream().filter(TurnResult::judged).count(), results.size(),
                results.stream().filter(TurnResult::judged).mapToLong(TurnResult::judgeMs).average().orElse(0));

        LocalDateTime now = LocalDateTime.now();
        String stamp = now.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + "-crisis-conv";
        Files.createDirectories(outDir);
        Path md = outDir.resolve(stamp + ".md");
        Files.writeString(md, markdown(now, file, summary, results), StandardCharsets.UTF_8);
        StringBuilder jsonl = new StringBuilder();
        for (TurnResult r : results) {
            jsonl.append(objectMapper.writeValueAsString(r)).append('\n');
        }
        Files.writeString(outDir.resolve(stamp + ".jsonl"), jsonl, StandardCharsets.UTF_8);
        return new Outcome(summary, md);
    }

    private String markdown(LocalDateTime now, Path file, String summary, List<TurnResult> results) {
        StringBuilder sb = new StringBuilder("# PsyQA 多轮危机评估 ")
                .append(now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))).append("\n\n");
        sb.append("题库：").append(file).append("；语义阈值 ").append(detector.threshold()).append("\n\n");
        sb.append("```\n").append(summary).append("```\n\n");
        sb.append("| 对话 | 轮 | 问题 | 标注 | 单轮 | 多轮 | 判定层 | 延续 | 危机分 | 理论分 | 上下文危机分 | 上下文理论分 |\n")
                .append("|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (TurnResult r : results) {
            boolean expected = "crisis".equals(r.label());
            sb.append("| %s | %d | %s | %s | %s | %s | %s | %s | %.3f | %.3f | %.3f | %.3f |\n".formatted(
                    r.dialogueId(), r.turn(), r.question().replace("|", "\\|"), r.label() + (r.sticky() ? "（延续）" : ""),
                    mark(r.singleCrisis(), expected), mark(r.crisis(), expected), r.layer(), r.followUp() ? "是" : "-",
                    r.crisisScore(), r.theoryScore(), r.contextCrisisScore(), r.contextTheoryScore()));
        }
        return sb.toString();
    }

    private static String mark(boolean got, boolean expected) {
        return (got ? "危机" : "-") + (got == expected ? "" : " ❌");
    }
}
