package com.lee.psyqa.answer;

import com.lee.psyqa.conversation.Turn;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 上下文预算的优先级：规则 + 问题 > 前 2 块 > 最近对话 > 其余块 > 摘要。 */
class ContextBudgetTest {

    /** 约 n 个 token 的英文文本（4 字符 1 token）。 */
    private static String tokens(int n) {
        return "abcd".repeat(n);
    }

    private static Document chunk(String id, int n) {
        return Document.builder().id(id).text(tokens(n)).score(0.5).build();
    }

    private static Turn turn(String q, int n) {
        return new Turn(q, tokens(n));
    }

    @Test
    void everythingFitsWithinBudget() {
        var plan = new ContextBudget(6000, 2).plan(tokens(500),
                List.of(chunk("a", 400), chunk("b", 400), chunk("c", 400)),
                List.of(turn("q1", 300), turn("q2", 300)), "earlier summary");
        assertThat(plan.trimmed()).isFalse();
        assertThat(plan.chunks()).hasSize(3);
        assertThat(plan.turns()).hasSize(2);
        assertThat(plan.summary()).isNotNull();
        assertThat(plan.totalTokens()).isLessThanOrEqualTo(6000);
    }

    @Test
    void trimsSummaryThenExtraChunksThenOldestTurns() {
        // 固定 500 + 必保留 2 块（各 400+20）= 1340；每轮对话 1+300+8 = 309
        // new → 1649，mid → 1958，old → 2267 超过 2100；块 c 420、摘要 200 都放不下
        var budget = new ContextBudget(2100, 2);
        var plan = budget.plan(tokens(500),
                List.of(chunk("a", 400), chunk("b", 400), chunk("c", 400), chunk("d", 400)),
                List.of(turn("old", 300), turn("mid", 300), turn("new", 300)), tokens(200));

        assertThat(plan.chunks()).extracting(Document::getId).containsExactly("a", "b");   // 其余块让位给对话
        assertThat(plan.turns()).extracting(Turn::question).containsExactly("mid", "new");  // 从新到旧放
        assertThat(plan.summary()).isNull();
        assertThat(plan.droppedChunks()).isEqualTo(2);
        assertThat(plan.droppedTurns()).isEqualTo(1);
        assertThat(plan.summaryDropped()).isTrue();
        assertThat(plan.totalTokens()).isLessThanOrEqualTo(2100);
    }

    @Test
    void mustKeepPartsAreKeptEvenOverBudget() {
        var plan = new ContextBudget(500, 2).plan(tokens(400),
                List.of(chunk("a", 400), chunk("b", 400), chunk("c", 10)), List.of(turn("q", 10)), null);
        assertThat(plan.chunks()).extracting(Document::getId).containsExactly("a", "b");
        assertThat(plan.turns()).isEmpty();
        assertThat(plan.overBudget()).isTrue();
    }

    @Test
    void estimateCountsChineseCharsOneEach() {
        assertThat(ContextBudget.estimate("荣格说的集体无意识")).isEqualTo(9);
        assertThat(ContextBudget.estimate("abcdefgh")).isEqualTo(2);
        assertThat(ContextBudget.estimate("什么是 libido？")).isEqualTo(3 + 1 + 2);   // 3 汉字 + 全角问号 + " libido" 7 字符
    }
}
