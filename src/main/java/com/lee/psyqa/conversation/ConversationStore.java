package com.lee.psyqa.conversation;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话历史，按 conversationId 存在内存里（≈ IMemoryCache + 滑动过期）。闲置超过 ttl 的会话在下次访问时清理。
 * v2 不上数据库：重启即丢，单实例部署够用。
 * <p>
 * 每个会话存：全部原始轮次 + 更早轮次的摘要（只追加、不重写）+ 已经压进摘要的轮数。
 * 放进 prompt 时取多少由 ContextBudget 决定。
 */
@Component
public class ConversationStore {

    static final Duration TTL = Duration.ofMinutes(30);
    /** 单个会话最多保留的原始轮数，防止内存无限增长。 */
    static final int MAX_TURNS = 50;

    /**
     * @param summarizedCount turns 里前多少轮已经压进 summary
     */
    public record Snapshot(List<Turn> turns, String summary, int summarizedCount) {

        public static final Snapshot EMPTY = new Snapshot(List.of(), null, 0);

        /** 还没压进摘要的轮次，按时间顺序。 */
        public List<Turn> unsummarized() {
            return turns.subList(summarizedCount, turns.size());
        }
    }

    private record Conversation(Snapshot snapshot, Instant lastAccess) {}

    private final Map<String, Conversation> conversations = new ConcurrentHashMap<>();
    private final Clock clock;

    public ConversationStore() {
        this(Clock.systemUTC());
    }

    ConversationStore(Clock clock) {
        this.clock = clock;
    }

    /** id 为空表示单轮问答，返回空快照。 */
    public Snapshot snapshot(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return Snapshot.EMPTY;
        }
        evictExpired();
        Conversation c = conversations.get(conversationId);
        return c == null ? Snapshot.EMPTY : c.snapshot();
    }

    /** 历史原文（按时间顺序）。 */
    public List<Turn> history(String conversationId) {
        return snapshot(conversationId).turns();
    }

    public void append(String conversationId, Turn turn) {
        if (conversationId == null || conversationId.isBlank()) {
            return;
        }
        conversations.compute(conversationId, (id, c) -> {
            Snapshot s = c == null ? Snapshot.EMPTY : c.snapshot();
            List<Turn> turns = new ArrayList<>(s.turns());
            turns.add(turn);
            int summarized = s.summarizedCount();
            if (turns.size() > MAX_TURNS) {
                int drop = turns.size() - MAX_TURNS;
                turns = new ArrayList<>(turns.subList(drop, turns.size()));
                summarized = Math.max(0, summarized - drop);
            }
            return new Conversation(new Snapshot(List.copyOf(turns), s.summary(), summarized), clock.instant());
        });
    }

    /**
     * 把 addition 接到摘要末尾，并把已摘要轮数推进到 newSummarizedCount。
     * 只在已摘要轮数仍是 expectedCount 时生效（两个并发请求同时摘要时，只有一个算数）。
     */
    public boolean appendSummary(String conversationId, int expectedCount, String addition, int newSummarizedCount) {
        if (conversationId == null || conversationId.isBlank()) {
            return false;
        }
        boolean[] applied = {false};
        conversations.computeIfPresent(conversationId, (id, c) -> {
            Snapshot s = c.snapshot();
            if (s.summarizedCount() != expectedCount || newSummarizedCount > s.turns().size()) {
                return c;
            }
            applied[0] = true;
            String summary = s.summary() == null ? addition.strip() : s.summary() + "\n" + addition.strip();
            return new Conversation(new Snapshot(s.turns(), summary, newSummarizedCount), c.lastAccess());
        });
        return applied[0];
    }

    private void evictExpired() {
        Instant cutoff = clock.instant().minus(TTL);
        conversations.entrySet().removeIf(e -> e.getValue().lastAccess().isBefore(cutoff));
    }
}
