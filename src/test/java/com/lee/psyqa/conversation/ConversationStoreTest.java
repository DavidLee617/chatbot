package com.lee.psyqa.conversation;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationStoreTest {

    /** 可以手动拨的时钟，测过期用。 */
    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-19T00:00:00Z");

        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @Test
    void appendsInOrderPerConversation() {
        var store = new ConversationStore();
        store.append("a", new Turn("q1", "a1"));
        store.append("a", new Turn("q2", "a2"));
        store.append("b", new Turn("x", "y"));
        assertThat(store.history("a")).extracting(Turn::question).containsExactly("q1", "q2");
        assertThat(store.history("b")).hasSize(1);
    }

    @Test
    void blankIdMeansSingleTurn() {
        var store = new ConversationStore();
        store.append(null, new Turn("q", "a"));
        store.append(" ", new Turn("q", "a"));
        assertThat(store.history(null)).isEmpty();
        assertThat(store.history(" ")).isEmpty();
    }

    @Test
    void idleConversationsExpire() {
        var clock = new MutableClock();
        var store = new ConversationStore(clock);
        store.append("a", new Turn("q1", "a1"));
        clock.now = clock.now.plus(ConversationStore.TTL.minus(Duration.ofMinutes(1)));
        assertThat(store.history("a")).hasSize(1);
        clock.now = clock.now.plus(Duration.ofMinutes(2));
        assertThat(store.history("a")).isEmpty();
    }

    @Test
    void keepsOnlyMostRecentTurns() {
        var store = new ConversationStore();
        for (int i = 0; i < ConversationStore.MAX_TURNS + 5; i++) {
            store.append("a", new Turn("q" + i, "a" + i));
        }
        assertThat(store.history("a")).hasSize(ConversationStore.MAX_TURNS);
        assertThat(store.history("a").get(0).question()).isEqualTo("q5");
    }

    @Test
    void summaryIsAppendOnlyAndGuardedAgainstConcurrentSummaries() {
        var store = new ConversationStore();
        for (int i = 0; i < 8; i++) {
            store.append("a", new Turn("q" + i, "a" + i));
        }
        assertThat(store.appendSummary("a", 0, "- 前四轮要点", 4)).isTrue();
        assertThat(store.appendSummary("a", 0, "- 重复的摘要", 4)).isFalse();   // 别的请求已经摘要过
        assertThat(store.appendSummary("a", 4, "- 第五到八轮要点", 8)).isTrue();

        var snap = store.snapshot("a");
        assertThat(snap.summary()).isEqualTo("- 前四轮要点\n- 第五到八轮要点");   // 只追加，不重写
        assertThat(snap.summarizedCount()).isEqualTo(8);
        assertThat(snap.unsummarized()).isEmpty();
    }
}
