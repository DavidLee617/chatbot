package com.lee.psyqa.eval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lee.psyqa.conversation.Turn;
import com.lee.psyqa.memory.Memory;
import com.lee.psyqa.memory.MemoryService;
import com.lee.psyqa.retrieval.QueryTranslator;
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
 * 跨对话记忆评估（T8）：eval/memory-scenarios.jsonl 每个剧本有几段对话 + 期望 + 召回探针。
 * <p>
 * 每个剧本用一个临时 userId，按顺序把每一轮交给 {@link MemoryService#write}（同步，和线上后台写入同一个方法），
 * 每段对话的历史从空开始（= 新对话）。全部写完后检查最终的记忆：
 * <ul>
 *   <li>facts：期望记到的偏好 / 背景（按 kind + 关键词任一命中）</li>
 *   <li>forbid：任何记忆里都不能出现的词（情绪、健康、人际、安全相关）</li>
 *   <li>absent：更新 / 更正之后，偏好 / 背景里不应再出现的旧内容</li>
 *   <li>noFacts：只问普通问题，不应产生偏好 / 背景</li>
 * </ul>
 * 再跑探针问题：recall 列出期望召回的主题（每组关键词任一命中一条召回的 topic），空列表表示不应召回任何主题。
 * 结束后删掉临时用户的记忆（最终记忆写进结果文件）。只评记忆，不检索、不生成。
 * <p>
 * references（T9，eval/memory-reference.jsonl）：新对话的第一句带指代。先召回记忆主题，再走线上同一个改写方法，
 * 检查判断（RESOLVED / AMBIGUOUS / NONE）对不对；RESOLVED 时英文问题里要出现期望的词（any 任一）。
 */
@Service
public class MemoryEval {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Scenario(String id, String note, List<Session> sessions, Expect expect, List<Probe> probes,
                           List<Reference> references) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Reference(String question, String expect, List<String> any) {}

    public record ReferenceResult(String question, String expect, String got, String text, List<String> topics, boolean ok) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Session(List<Turn> turns) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Expect(List<ExpectedFact> facts, List<String> forbid, List<String> absent, Boolean noFacts) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ExpectedFact(String kind, List<String> any) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Probe(String question, List<List<String>> recall) {}

    public record ProbeResult(String question, boolean expectNone, int expectedGroups, int hitGroups,
                              List<String> recalled, List<Double> recalledScores, List<Double> allTopicScores) {
        boolean ok() {
            return expectNone ? recalled.isEmpty() : hitGroups == expectedGroups;
        }
    }

    public record ScenarioResult(String id, String note, List<String> memories,
                                 int expectedFacts, int foundFacts, List<String> forbidHits, List<String> absentHits,
                                 boolean noFactsViolated, List<ProbeResult> probes, List<ReferenceResult> references,
                                 int extractCalls, int reconcileCalls, int tokens, long writeMs) {}

    public record Outcome(String summary, Path markdownFile) {}

    private final MemoryService memory;
    private final QueryTranslator translator;
    private final ObjectMapper objectMapper;

    public MemoryEval(MemoryService memory, QueryTranslator translator, ObjectMapper objectMapper) {
        this.memory = memory;
        this.translator = translator;
        this.objectMapper = objectMapper;
    }

    public Outcome run(Path file, Path outDir) throws IOException {
        List<Scenario> scenarios = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                scenarios.add(objectMapper.readValue(line, Scenario.class));
            }
        }
        LocalDateTime now = LocalDateTime.now();
        String stamp = now.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        List<ScenarioResult> results = new ArrayList<>();
        for (Scenario sc : scenarios) {
            String userId = "eval-" + stamp + "-" + sc.id();
            try {
                results.add(runOne(sc, userId));
            } finally {
                memory.deleteAll(userId);
            }
            System.out.print("\r记忆评估中 " + sc.id());
        }
        System.out.println();

        int expected = results.stream().mapToInt(ScenarioResult::expectedFacts).sum();
        int found = results.stream().mapToInt(ScenarioResult::foundFacts).sum();
        List<ProbeResult> probes = results.stream().flatMap(r -> r.probes().stream()).toList();
        List<ProbeResult> positive = probes.stream().filter(p -> !p.expectNone()).toList();
        List<ProbeResult> negative = probes.stream().filter(ProbeResult::expectNone).toList();
        int extracts = results.stream().mapToInt(ScenarioResult::extractCalls).sum();
        List<ReferenceResult> refs = results.stream().flatMap(r -> r.references().stream()).toList();
        StringBuilder refLine = new StringBuilder();
        for (String kind : List.of("RESOLVED", "AMBIGUOUS", "NONE")) {
            List<ReferenceResult> k = refs.stream().filter(r -> r.expect().equals(kind)).toList();
            if (!k.isEmpty()) {
                refLine.append(refLine.isEmpty() ? "" : "，").append("期望 ").append(kind).append(' ')
                        .append(k.stream().filter(ReferenceResult::ok).count()).append('/').append(k.size());
            }
        }
        String summary = (refs.isEmpty() ? "" : "新对话指代：" + refLine + "\n") + """
                应记住的偏好 / 背景 %d 条：记到 %d
                不该记的内容（情绪 / 健康 / 人际 / 安全）：%d 个剧本违规 %s
                更新 / 更正后旧内容残留：%d 个剧本 %s
                只有普通问题却记了偏好 / 背景：%d 个剧本 %s
                召回探针：应召回 %d 个、召回到 %d；不该召回 %d 个、误召回 %d
                开销：提炼 %d 次、对账 %d 次，共 %d tokens，平均每轮写入 %.0f ms
                """.formatted(expected, found,
                count(results, r -> !r.forbidHits().isEmpty()), ids(results, r -> !r.forbidHits().isEmpty()),
                count(results, r -> !r.absentHits().isEmpty()), ids(results, r -> !r.absentHits().isEmpty()),
                count(results, ScenarioResult::noFactsViolated), ids(results, ScenarioResult::noFactsViolated),
                positive.size(), positive.stream().filter(ProbeResult::ok).count(),
                negative.size(), negative.stream().filter(p -> !p.ok()).count(),
                extracts, results.stream().mapToInt(ScenarioResult::reconcileCalls).sum(),
                results.stream().mapToInt(ScenarioResult::tokens).sum(),
                extracts == 0 ? 0.0 : (double) results.stream().mapToLong(ScenarioResult::writeMs).sum() / extracts);

        Files.createDirectories(outDir);
        Path md = outDir.resolve(stamp + "-memory.md");
        Files.writeString(md, markdown(now, file, summary, results), StandardCharsets.UTF_8);
        StringBuilder jsonl = new StringBuilder();
        for (ScenarioResult r : results) {
            jsonl.append(objectMapper.writeValueAsString(r)).append('\n');
        }
        Files.writeString(outDir.resolve(stamp + "-memory.jsonl"), jsonl, StandardCharsets.UTF_8);
        return new Outcome(summary, md);
    }

    private ScenarioResult runOne(Scenario sc, String userId) {
        int extracts = 0, reconciles = 0, tokens = 0;
        long writeMs = 0;
        for (Session session : sc.sessions()) {
            List<Turn> history = new ArrayList<>();
            for (Turn t : session.turns()) {
                MemoryService.WriteResult w = memory.write(userId, List.copyOf(history), t);
                extracts++;
                reconciles += w.reconcileCalls();
                tokens += w.tokens();
                writeMs += w.latencyMs();
                history.add(t);
            }
        }
        List<Memory> mems = memory.list(userId);
        List<Memory> facts = mems.stream().filter(m -> m.kind().isFact()).toList();
        Expect ex = sc.expect() == null ? new Expect(List.of(), List.of(), List.of(), null) : sc.expect();

        int foundFacts = 0;
        for (ExpectedFact f : nullToEmpty(ex.facts())) {
            Memory.Kind kind = Memory.Kind.valueOf(f.kind().toUpperCase(Locale.ROOT));
            if (facts.stream().anyMatch(m -> m.kind() == kind && containsAny(m.content(), f.any()))) {
                foundFacts++;
            }
        }
        List<String> forbidHits = mems.stream().map(Memory::content)
                .filter(c -> containsAny(c, nullToEmpty(ex.forbid()))).toList();
        List<String> absentHits = facts.stream().map(Memory::content)
                .filter(c -> containsAny(c, nullToEmpty(ex.absent()))).toList();
        boolean noFactsViolated = Boolean.TRUE.equals(ex.noFacts()) && !facts.isEmpty();

        List<ProbeResult> probes = new ArrayList<>();
        for (Probe p : nullToEmpty(sc.probes())) {
            MemoryService.Recall r = memory.recall(userId, p.question());
            List<String> recalled = r.topics().stream().map(Memory::content).toList();
            List<List<String>> groups = nullToEmpty(p.recall());
            int hit = (int) groups.stream().filter(g -> recalled.stream().anyMatch(c -> containsAny(c, g))).count();
            probes.add(new ProbeResult(p.question(), groups.isEmpty(), groups.size(), hit, recalled, r.topicScores(),
                    memory.topicScores(userId, p.question())));
        }
        List<ReferenceResult> refs = new ArrayList<>();
        for (Reference ref : nullToEmpty(sc.references())) {
            List<String> topics = memory.recall(userId, ref.question()).recentTopics().stream().map(Memory::content).toList();
            String got;
            String text;
            try {
                var t = translator.translate(ref.question(), List.of(), topics);
                got = t.map(x -> x.resolution().name()).orElse("NONE");
                text = t.map(QueryTranslator.Translation::text).orElse(ref.question());
            } catch (RuntimeException e) {
                got = "ERROR";
                text = e.getMessage();
            }
            boolean ok = got.equals(ref.expect())
                    && (!got.equals("RESOLVED") || containsAny(text, nullToEmpty(ref.any())));
            refs.add(new ReferenceResult(ref.question(), ref.expect(), got, text, topics, ok));
        }
        return new ScenarioResult(sc.id(), sc.note(),
                mems.stream().map(m -> m.kind() + " " + m.content()).toList(),
                nullToEmpty(ex.facts()).size(), foundFacts, forbidHits, absentHits, noFactsViolated, probes, refs,
                extracts, reconciles, tokens, writeMs);
    }

    private static String markdown(LocalDateTime now, Path file, String summary, List<ScenarioResult> results) {
        StringBuilder sb = new StringBuilder("# PsyQA 长期记忆评估 ")
                .append(now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))).append("\n\n");
        sb.append("题库：").append(file).append("\n\n```\n").append(summary).append("```\n");
        for (ScenarioResult r : results) {
            sb.append("\n## ").append(r.id()).append(" ").append(r.note()).append("\n\n");
            sb.append("最终记忆：").append(r.memories().isEmpty() ? "（无）" : "").append('\n');
            r.memories().forEach(m -> sb.append("- ").append(m).append('\n'));
            List<String> problems = new ArrayList<>();
            if (r.foundFacts() < r.expectedFacts()) {
                problems.add("期望的偏好 / 背景只记到 " + r.foundFacts() + "/" + r.expectedFacts());
            }
            if (!r.forbidHits().isEmpty()) {
                problems.add("记了不该记的：" + r.forbidHits());
            }
            if (!r.absentHits().isEmpty()) {
                problems.add("旧内容残留：" + r.absentHits());
            }
            if (r.noFactsViolated()) {
                problems.add("普通问题却记了偏好 / 背景");
            }
            for (ProbeResult p : r.probes()) {
                sb.append("\n探针「").append(p.question()).append("」→ 召回 ").append(p.recalled())
                        .append(" 分数 ").append(fmt(p.recalledScores())).append("；全部主题分数 ").append(fmt(p.allTopicScores()))
                        .append(p.ok() ? " ✅" : " ❌").append('\n');
            }
            for (ReferenceResult rr : r.references()) {
                sb.append("\n指代「").append(rr.question()).append("」主题 ").append(rr.topics()).append(" → ").append(rr.got())
                        .append("：").append(rr.text()).append("（期望 ").append(rr.expect()).append("）")
                        .append(rr.ok() ? " ✅" : " ❌").append('\n');
            }
            sb.append('\n').append(problems.isEmpty() ? "✅ 记忆检查通过" : "❌ " + String.join("；", problems)).append('\n');
        }
        return sb.toString();
    }

    private static String fmt(List<Double> ds) {
        return ds.stream().map(d -> String.format(Locale.ROOT, "%.3f", d)).toList().toString();
    }

    private static boolean containsAny(String text, List<String> words) {
        String t = text.toLowerCase(Locale.ROOT);
        return words.stream().anyMatch(w -> t.contains(w.toLowerCase(Locale.ROOT)));
    }

    private static <T> List<T> nullToEmpty(List<T> l) {
        return l == null ? List.of() : l;
    }

    private static long count(List<ScenarioResult> rs, java.util.function.Predicate<ScenarioResult> p) {
        return rs.stream().filter(p).count();
    }

    private static String ids(List<ScenarioResult> rs, java.util.function.Predicate<ScenarioResult> p) {
        List<String> ids = rs.stream().filter(p).map(ScenarioResult::id).toList();
        return ids.isEmpty() ? "" : ids.toString();
    }
}
