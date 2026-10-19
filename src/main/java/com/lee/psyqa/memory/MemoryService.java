package com.lee.psyqa.memory;

import com.lee.psyqa.conversation.Turn;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 长期记忆（T8）：每轮回答完成后后台提炼、写入；每次提问时召回。
 * <p>
 * 写入：
 * <ol>
 *   <li>{@link MemoryExtractor#extract} 一次调用拿到 facts（偏好 / 背景）和 topics</li>
 *   <li>topic：和已有 topic 比向量，相似度 ≥ {@link #TOPIC_DUPLICATE} 只刷新时间，否则新增。不调模型</li>
 *   <li>fact：找相似度 ≥ {@link #FACT_SIMILAR} 的旧 fact；没有就新增，有就调一次模型对账（mem0 的做法），
 *       这样"以后简短一点"会更新"喜欢详细回答"，而不是两条并存</li>
 * </ol>
 * 召回：偏好和背景全部带上（条数很少，每次回答都适用）；topic 按和当前问题的相似度取前 {@link #MAX_RECALL_TOPICS} 条。
 * 每个用户最多几百条，全部读出来在 Java 里算余弦，不用向量库。
 * <p>
 * 写入放在单线程执行器里排队（≈ 一个 Channel + 单消费者）：同一用户的两轮不会并发对账、写出重复记忆。
 * 记忆失败只记日志，不影响回答。
 */
@Service
public class MemoryService {

    private static final Logger log = LoggerFactory.getLogger(MemoryService.class);

    static final double TOPIC_DUPLICATE = 0.85;
    static final double FACT_SIMILAR = 0.5;
    /**
     * 2026-10-19 记忆评估：应召回的探针最低 0.515（u01 凝缩），不该召回的最高 0.485（u10 俄狄浦斯 vs 阴影问题），
     * 取中间 0.50。区间只有 0.03、探针只有 7 个，是起点不是结论。
     */
    static final double TOPIC_RECALL = 0.50;
    static final int MAX_RECALL_TOPICS = 3;
    static final int MAX_RECALL_FACTS = 10;
    static final int MAX_PER_USER = 200;

    /** 一次写入的统计，写日志和评估用。 */
    public record WriteResult(int facts, int topics, int added, int updated, int deleted, int unchanged,
                              int reconcileCalls, int tokens, long latencyMs) {
        static final WriteResult EMPTY = new WriteResult(0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    /** @param topicScores 召回到的 topic 和问题的相似度，和 topics 一一对应 */
    public record Recall(List<Memory> facts, List<Memory> topics, List<Double> topicScores, long latencyMs) {
        public static final Recall EMPTY = new Recall(List.of(), List.of(), List.of(), 0);

        public boolean isEmpty() {
            return facts.isEmpty() && topics.isEmpty();
        }

        /** 放进 prompt 的文本；没有记忆时为 null。 */
        public String render() {
            if (isEmpty()) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            for (Memory m : facts) {
                sb.append("- ").append(m.content()).append('\n');
            }
            if (!topics.isEmpty()) {
                sb.append("- 以前聊过：").append(String.join("、", topics.stream().map(Memory::content).toList())).append('\n');
            }
            return sb.toString().strip();
        }
    }

    private final MemoryRepository repository;
    private final MemoryExtractor extractor;
    private final EmbeddingModel embeddingModel;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "memory-writer");
        t.setDaemon(true);
        return t;
    });

    public MemoryService(MemoryRepository repository, MemoryExtractor extractor, EmbeddingModel embeddingModel) {
        this.repository = repository;
        this.extractor = extractor;
        this.embeddingModel = embeddingModel;
    }

    /** 后台写入，立即返回。 */
    public void writeAsync(String userId, List<Turn> history, Turn current) {
        if (userId == null) {
            return;
        }
        writer.submit(() -> {
            try {
                WriteResult r = write(userId, history, current);
                log.info("memory_write facts={} topics={} added={} updated={} deleted={} unchanged={} reconcile_calls={} tokens={} latency_ms={}",
                        r.facts(), r.topics(), r.added(), r.updated(), r.deleted(), r.unchanged(), r.reconcileCalls(),
                        r.tokens(), r.latencyMs());
            } catch (RuntimeException e) {
                log.warn("记忆写入失败，这一轮不记：{}", e.getMessage());
            }
        });
    }

    /** 同步写入（评估直接调用）。 */
    public WriteResult write(String userId, List<Turn> history, Turn current) {
        long t0 = System.nanoTime();
        MemoryExtractor.Extraction ex = extractor.extract(history, current);
        if (ex.isEmpty()) {
            return new WriteResult(0, 0, 0, 0, 0, 0, 0, ex.tokens(), elapsedMs(t0));
        }
        List<String> texts = new ArrayList<>();
        ex.facts().forEach(f -> texts.add(f.content()));
        texts.addAll(ex.topics());
        List<float[]> vectors = embeddingModel.embed(texts);   // facts ≤ 3 + topics ≤ 2，一次调用
        List<Memory> existing = new ArrayList<>(repository.findByUser(userId));

        int added = 0, updated = 0, deleted = 0, unchanged = 0, calls = 0, tokens = ex.tokens();
        for (int i = 0; i < ex.topics().size(); i++) {
            float[] v = vectors.get(ex.facts().size() + i);
            Memory dup = existing.stream().filter(m -> m.kind() == Memory.Kind.TOPIC)
                    .filter(m -> cosine(v, m.embedding()) >= TOPIC_DUPLICATE)
                    .findFirst().orElse(null);
            if (dup != null) {
                repository.touch(dup.id());
                unchanged++;
            } else {
                Memory m = newMemory(userId, Memory.Kind.TOPIC, ex.topics().get(i), v);
                repository.insert(m);
                existing.add(m);
                added++;
            }
        }
        for (int i = 0; i < ex.facts().size(); i++) {
            MemoryExtractor.Fact fact = ex.facts().get(i);
            float[] v = vectors.get(i);
            List<Memory> similar = existing.stream().filter(m -> m.kind().isFact())
                    .filter(m -> cosine(v, m.embedding()) >= FACT_SIMILAR)
                    .sorted(Comparator.comparingDouble((Memory m) -> cosine(v, m.embedding())).reversed())
                    .limit(3)
                    .toList();
            if (similar.isEmpty()) {
                Memory m = newMemory(userId, fact.kind(), fact.content(), v);
                repository.insert(m);
                existing.add(m);
                added++;
                continue;
            }
            MemoryExtractor.Action a = extractor.reconcile(similar, fact);
            calls++;
            tokens += a.tokens();
            switch (a.type()) {
                case ADD -> {
                    Memory m = newMemory(userId, fact.kind(), fact.content(), v);
                    repository.insert(m);
                    existing.add(m);
                    added++;
                }
                case UPDATE -> {
                    Memory target = similar.get(a.target() - 1);
                    float[] nv = a.content().equals(fact.content()) ? v : embeddingModel.embed(a.content());
                    repository.update(target.id(), a.content(), nv);
                    existing.replaceAll(m -> m.id().equals(target.id())
                            ? new Memory(m.id(), m.userId(), m.kind(), a.content(), nv, m.createdAt(), Instant.now()) : m);
                    updated++;
                }
                case DELETE -> {
                    Memory target = similar.get(a.target() - 1);
                    repository.delete(userId, target.id());
                    existing.removeIf(m -> m.id().equals(target.id()));
                    deleted++;
                }
                case NONE -> unchanged++;
            }
        }
        deleted += trim(userId, existing);
        return new WriteResult(ex.facts().size(), ex.topics().size(), added, updated, deleted, unchanged, calls, tokens,
                elapsedMs(t0));
    }

    public Recall recall(String userId, String question) {
        if (userId == null) {
            return Recall.EMPTY;
        }
        long t0 = System.nanoTime();
        List<Memory> all = repository.findByUser(userId);   // 已按 updated_at 降序
        if (all.isEmpty()) {
            return Recall.EMPTY;
        }
        List<Memory> facts = all.stream().filter(m -> m.kind().isFact()).limit(MAX_RECALL_FACTS).toList();
        List<Memory> topicMemories = all.stream().filter(m -> m.kind() == Memory.Kind.TOPIC).toList();
        List<Memory> topics = new ArrayList<>();
        List<Double> scores = new ArrayList<>();
        if (!topicMemories.isEmpty()) {
            float[] q = embeddingModel.embed(question);
            topicMemories.stream()
                    .map(m -> new Scored(m, cosine(q, m.embedding())))
                    .filter(s -> s.score() >= TOPIC_RECALL)
                    .sorted(Comparator.comparingDouble(Scored::score).reversed())
                    .limit(MAX_RECALL_TOPICS)
                    .forEach(s -> {
                        topics.add(s.memory());
                        scores.add(s.score());
                    });
        }
        return new Recall(facts, List.copyOf(topics), List.copyOf(scores), elapsedMs(t0));
    }

    /** 评估用：每个 topic 和问题的相似度（不过阈值也返回），用来看阈值放在哪。 */
    public List<Double> topicScores(String userId, String question) {
        List<Memory> topics = repository.findByUser(userId).stream().filter(m -> m.kind() == Memory.Kind.TOPIC).toList();
        if (topics.isEmpty()) {
            return List.of();
        }
        float[] q = embeddingModel.embed(question);
        return topics.stream().map(m -> cosine(q, m.embedding())).sorted(Comparator.reverseOrder()).toList();
    }

    public List<Memory> list(String userId) {
        return repository.findByUser(userId);
    }

    public boolean delete(String userId, UUID id) {
        return repository.delete(userId, id);
    }

    public int deleteAll(String userId) {
        return repository.deleteAll(userId);
    }

    /** 超过上限时删最久没聊到的 topic（偏好和背景不自动删）。 */
    private int trim(String userId, List<Memory> existing) {
        int over = existing.size() - MAX_PER_USER;
        if (over <= 0) {
            return 0;
        }
        List<Memory> oldest = existing.stream().filter(m -> m.kind() == Memory.Kind.TOPIC)
                .sorted(Comparator.comparing(Memory::updatedAt)).limit(over).toList();
        oldest.forEach(m -> repository.delete(userId, m.id()));
        return oldest.size();
    }

    @PreDestroy
    void shutdown() {
        writer.shutdown();
    }

    private record Scored(Memory memory, double score) {}

    private static Memory newMemory(String userId, Memory.Kind kind, String content, float[] v) {
        Instant now = Instant.now();
        return new Memory(UUID.randomUUID(), userId, kind, content, v, now, now);
    }

    static double cosine(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return na == 0 || nb == 0 ? 0 : dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    private static long elapsedMs(long t0) {
        return (System.nanoTime() - t0) / 1_000_000;
    }
}
