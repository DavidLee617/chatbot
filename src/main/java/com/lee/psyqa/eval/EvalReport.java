package com.lee.psyqa.eval;

import com.lee.psyqa.guard.CrisisDetector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * 由每道题的检索结果算指标、生成 Markdown 报告。纯计算，不依赖 Spring，方便单元测试。
 */
public final class EvalReport {

    /**
     * 一道题的结果。
     *
     * @param firstHitRank 第一个命中块的名次（从 1 开始），0 表示 top-k 里没有命中
     * @param hitInKept    命中块是否在阈值之上，也就是模型真正能看到
     * @param scores       top-k 的相似度，降序
     * @param retrieved    top-k 每块的 "书 · 章"，用来人工排查
     * @param crisis       危机识别的中间结果，用于危机阈值扫描
     * @param translation  查询翻译（T3）；没翻译时为 null
     */
    public record QuestionResult(String id, String type, String difficulty, String primaryBook, String question,
                                 String expectedRoute, String predictedRoute, int firstHitRank, boolean hitInKept,
                                 List<Double> scores, List<String> retrieved, CrisisInfo crisis, TranslationInfo translation) {

        double top1() {
            return scores.isEmpty() ? 0 : scores.get(0);
        }

        boolean answerable() {
            return "answerable".equals(type);
        }

        /** 有答案、且检索要能命中的题：answerable + sensitive。 */
        boolean hasExpected() {
            return answerable() || "sensitive".equals(type);
        }
    }

    /** CrisisDetector.Decision 里评估要用的部分。layer：STRONG_KEYWORD / SEMANTIC / NONE / SEMANTIC_ERROR */
    public record CrisisInfo(String layer, boolean strongHit, boolean weakHit, double crisisScore, double theoryScore) {

        boolean crisisAt(double threshold) {
            return CrisisDetector.decide(strongHit, weakHit, crisisScore, theoryScore, threshold);
        }
    }

    /** 查询翻译的结果和开销。tokens = prompt + completion。 */
    public record TranslationInfo(String text, long latencyMs, int tokens) {}

    /** 报告头里记录的配置，换参数做对比实验时用来区分两次结果。 */
    public record RunInfo(String startedAt, String lang, int k, double threshold, String embeddingModel,
                          int chunkSize, int chunkOverlap, String questionsFile, double crisisThreshold,
                          String queryMode, int hnswEf) {}

    private static final double[] SWEEP = {0.30, 0.35, 0.40, 0.45, 0.50, 0.55, 0.60};
    private static final double[] CRISIS_SWEEP = {0.40, 0.45, 0.50, 0.55, 0.60, 0.65, 0.70, 0.75};

    private final RunInfo info;
    private final List<QuestionResult> results;
    private final List<QuestionResult> answerable;
    private final List<QuestionResult> outOfScope;
    private final List<QuestionResult> crisis;
    /** 易误报题：话题涉及死亡 / 自杀但在问理论，期望正常回答。单独统计，不混进 answerable，保证原有指标可比 */
    private final List<QuestionResult> sensitive;

    public EvalReport(RunInfo info, List<QuestionResult> results) {
        this.info = info;
        this.results = List.copyOf(results);
        this.answerable = filter(r -> r.answerable());
        this.outOfScope = filter(r -> "out_of_scope".equals(r.type()));
        this.crisis = filter(r -> "crisis".equals(r.type()));
        this.sensitive = filter(r -> "sensitive".equals(r.type()));
    }

    private List<QuestionResult> filter(Predicate<QuestionResult> p) {
        return results.stream().filter(p).toList();
    }

    // ---- 指标

    /** 第一个命中在前 n 名以内的比例。 */
    public double recallAt(int n) {
        return ratio(answerable, r -> r.firstHitRank() > 0 && r.firstHitRank() <= n);
    }

    /** Mean Reciprocal Rank：命中越靠前越接近 1，没命中记 0。 */
    public double mrr() {
        return answerable.isEmpty() ? 0
                : answerable.stream().mapToDouble(r -> r.firstHitRank() == 0 ? 0 : 1.0 / r.firstHitRank()).average().orElse(0);
    }

    /** 命中块在阈值之上（模型真正看得到）的比例。和 recall@k 的差距 = 阈值误杀的部分。 */
    public double effectiveRecall() {
        return ratio(answerable, QuestionResult::hitInKept);
    }

    public double routeAccuracy(List<QuestionResult> group) {
        return ratio(group, r -> r.expectedRoute().equals(r.predictedRoute()));
    }

    private static double ratio(List<QuestionResult> group, Predicate<QuestionResult> p) {
        return group.isEmpty() ? 0 : (double) group.stream().filter(p).count() / group.size();
    }

    // ---- 输出

    /** 控制台用的几行摘要。 */
    public String summary() {
        return """
                有答案的题 %d 道：recall@1 %s  recall@3 %s  recall@%d %s  MRR %.3f  阈值后召回 %s
                无答案的题 %d 道：正确拒答 %s
                危机题 %d 道：正确路由 %s
                易误报题 %d 道：正确路由（没被判成危机）%s  recall@%d %s
                """.formatted(
                answerable.size(), pct(recallAt(1)), pct(recallAt(3)), info.k(), pct(recallAt(info.k())), mrr(), pct(effectiveRecall()),
                outOfScope.size(), pct(routeAccuracy(outOfScope)),
                crisis.size(), pct(routeAccuracy(crisis)),
                sensitive.size(), pct(routeAccuracy(sensitive)), info.k(),
                pct(ratio(sensitive, r -> r.firstHitRank() > 0 && r.firstHitRank() <= info.k())))
                + "查询翻译：" + translationCost() + "\n";
    }

    /** 翻译了几道题、平均延迟、总 token。 */
    public String translationCost() {
        List<TranslationInfo> ts = results.stream().map(QuestionResult::translation).filter(t -> t != null).toList();
        if (ts.isEmpty()) {
            return "未翻译";
        }
        return "%d 道，平均 %.0f ms，共 %d tokens".formatted(ts.size(),
                ts.stream().mapToLong(TranslationInfo::latencyMs).average().orElse(0),
                ts.stream().mapToInt(TranslationInfo::tokens).sum());
    }

    public String markdown() {
        StringBuilder sb = new StringBuilder();
        sb.append("# PsyQA 检索评估 ").append(info.startedAt()).append("\n\n");
        sb.append("| 配置 | 值 |\n|---|---|\n");
        sb.append(row("提问语言", info.lang()));
        sb.append(row("top-k", String.valueOf(info.k())));
        sb.append(row("相似度阈值", "%.2f".formatted(info.threshold())));
        sb.append(row("embedding 模型", info.embeddingModel()));
        sb.append(row("chunk-size / overlap", info.chunkSize() + " / " + info.chunkOverlap()));
        sb.append(row("题库", info.questionsFile()));
        sb.append(row("危机语义阈值", "%.2f".formatted(info.crisisThreshold())));
        sb.append(row("查询方式", info.queryMode()));
        sb.append(row("HNSW ef", String.valueOf(info.hnswEf())));

        sb.append("\n## 总览\n\n| 指标 | 值 |\n|---|---|\n");
        sb.append(row("有答案的题", String.valueOf(answerable.size())));
        sb.append(row("recall@1", pct(recallAt(1))));
        sb.append(row("recall@3", pct(recallAt(3))));
        sb.append(row("recall@" + info.k(), pct(recallAt(info.k()))));
        sb.append(row("MRR", "%.3f".formatted(mrr())));
        sb.append(row("阈值后召回（模型能看到命中块）", pct(effectiveRecall())));
        sb.append(row("有答案的题路由正确", pct(routeAccuracy(answerable))));
        sb.append(row("无答案的题正确拒答", pct(routeAccuracy(outOfScope)) + "（" + outOfScope.size() + " 道）"));
        sb.append(row("危机题正确路由", pct(routeAccuracy(crisis)) + "（" + crisis.size() + " 道）"));
        sb.append(row("易误报题正确路由（没被判成危机）", pct(routeAccuracy(sensitive)) + "（" + sensitive.size() + " 道）"));
        sb.append(row("查询翻译开销", translationCost()));

        sb.append("\n## 按难度\n\n");
        breakdown(sb, QuestionResult::difficulty, List.of("easy", "medium", "hard"));
        sb.append("\n## 按书（以第一本期望的书归类）\n\n");
        breakdown(sb, QuestionResult::primaryBook, null);

        sb.append("\n## 阈值扫描（只看 top-1 分数）\n\n");
        sb.append("有答案的题被误拒越少越好，无答案的题被放过越少越好。两者此消彼长，选一个都能接受的点。\n\n");
        sb.append("| 阈值 | 有答案的题被误拒 | 无答案的题被放过 |\n|---|---|---|\n");
        for (double t : SWEEP) {
            long falseRefuse = answerable.stream().filter(r -> r.top1() < t).count();
            long falsePass = outOfScope.stream().filter(r -> r.top1() >= t).count();
            sb.append("| %.2f%s | %d / %d | %d / %d |\n".formatted(t, Math.abs(t - info.threshold()) < 1e-9 ? "（当前）" : "",
                    falseRefuse, answerable.size(), falsePass, outOfScope.size()));
        }
        sb.append("\n").append(scoreLine("有答案的题 top-1 分数", answerable));
        sb.append(scoreLine("无答案的题 top-1 分数", outOfScope));

        crisisSection(sb);

        sb.append("\n## 没命中的题（优先排查）\n\n");
        List<QuestionResult> misses = answerable.stream().filter(r -> r.firstHitRank() == 0).toList();
        if (misses.isEmpty()) {
            sb.append("无\n");
        }
        for (QuestionResult r : misses) {
            sb.append("- **%s**（%s）%s\n".formatted(r.id(), r.difficulty(), r.question()));
            for (int i = 0; i < r.retrieved().size(); i++) {
                sb.append("  %d. %.2f %s\n".formatted(i + 1, r.scores().get(i), r.retrieved().get(i)));
            }
        }

        sb.append("\n## 每题明细\n\n| id | 类型 | 难度 | 期望路由 | 实际路由 | 首个命中名次 | 阈值后可见 | top-1 | 问题 |\n|---|---|---|---|---|---|---|---|---|\n");
        for (QuestionResult r : results) {
            boolean routeOk = r.expectedRoute().equals(r.predictedRoute());
            sb.append("| %s | %s | %s | %s | %s%s | %s | %s | %.2f | %s |\n".formatted(
                    r.id(), r.type(), r.difficulty(), r.expectedRoute(), r.predictedRoute(), routeOk ? "" : " ❌",
                    r.hasExpected() ? (r.firstHitRank() == 0 ? "未命中 ❌" : String.valueOf(r.firstHitRank())) : "-",
                    r.hasExpected() ? (r.hitInKept() ? "是" : "否") : "-",
                    r.top1(), r.question().replace("|", "\\|")));
        }
        return sb.toString();
    }

    /** 危机识别：阈值扫描 + 每道相关题的分数。用同一个 CrisisDetector.decide()，扫描结果和线上一致。 */
    private void crisisSection(StringBuilder sb) {
        List<QuestionResult> others = filter(r -> !"crisis".equals(r.type()) && r.crisis() != null);
        sb.append("\n## 危机识别\n\n");
        sb.append("规则：强关键词直接判危机；命中弱关键词时只看危机分 > 理论分；都没命中时还要求危机分 ≥ 阈值。")
                .append("按\"宁可多报\"选：危机题全部识别的前提下，误报尽量少。\n\n");
        sb.append("| 危机阈值 | 危机题漏判 | 易误报题被判危机 | 其他题被判危机 |\n|---|---|---|---|\n");
        for (double t : CRISIS_SWEEP) {
            long missed = crisis.stream().filter(r -> r.crisis() == null || !r.crisis().crisisAt(t)).count();
            long fpSensitive = sensitive.stream().filter(r -> r.crisis() != null && r.crisis().crisisAt(t)).count();
            long fpOther = others.stream().filter(r -> !"sensitive".equals(r.type()) && r.crisis().crisisAt(t)).count();
            sb.append("| %.2f%s | %d / %d | %d / %d | %d / %d |\n".formatted(t,
                    Math.abs(t - info.crisisThreshold()) < 1e-9 ? "（当前）" : "",
                    missed, crisis.size(), fpSensitive, sensitive.size(), fpOther, others.size() - sensitive.size()));
        }
        sb.append("\n| id | 类型 | 判定层 | 强 / 弱关键词 | 危机分 | 理论分 | 问题 |\n|---|---|---|---|---|---|---|\n");
        List<QuestionResult> shown = new ArrayList<>(crisis);
        shown.addAll(sensitive);
        // 其他题里危机分最高的 5 道：看离误报还有多远
        others.stream().filter(r -> !"sensitive".equals(r.type()))
                .sorted(Comparator.comparingDouble((QuestionResult r) -> r.crisis().crisisScore()).reversed())
                .limit(5).forEach(shown::add);
        for (QuestionResult r : shown) {
            CrisisInfo c = r.crisis();
            if (c == null) {
                continue;
            }
            sb.append("| %s | %s | %s | %s / %s | %.3f | %.3f | %s |\n".formatted(r.id(), r.type(), c.layer(),
                    c.strongHit() ? "是" : "-", c.weakHit() ? "是" : "-", c.crisisScore(), c.theoryScore(),
                    r.question().replace("|", "\\|")));
        }
    }

    private void breakdown(StringBuilder sb, Function<QuestionResult, String> key, List<String> order) {
        Map<String, List<QuestionResult>> groups = answerable.stream()
                .collect(Collectors.groupingBy(key, LinkedHashMap::new, Collectors.toList()));
        List<String> keys = new ArrayList<>(groups.keySet());
        if (order != null) {
            keys.sort(Comparator.comparingInt(k -> order.indexOf(k) < 0 ? Integer.MAX_VALUE : order.indexOf(k)));
        }
        sb.append("| 分组 | 题数 | recall@").append(info.k()).append(" | MRR |\n|---|---|---|---|\n");
        for (String k : keys) {
            EvalReport sub = new EvalReport(info, groups.get(k));
            sb.append("| %s | %d | %s | %.3f |\n".formatted(k, groups.get(k).size(), pct(sub.recallAt(info.k())), sub.mrr()));
        }
    }

    private static String scoreLine(String label, List<QuestionResult> group) {
        if (group.isEmpty()) {
            return "";
        }
        List<Double> s = group.stream().map(QuestionResult::top1).sorted().toList();
        return "- %s：最低 %.2f，中位数 %.2f，最高 %.2f\n".formatted(label, s.get(0), s.get(s.size() / 2), s.get(s.size() - 1));
    }

    private static String row(String k, String v) {
        return "| " + k + " | " + v + " |\n";
    }

    private static String pct(double v) {
        return String.format(Locale.ROOT, "%.1f%%", v * 100);
    }
}
