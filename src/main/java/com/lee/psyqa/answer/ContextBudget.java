package com.lee.psyqa.answer;

import com.lee.psyqa.conversation.Turn;
import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 上下文预算（T4 第 2 步）：在 token 上限内决定 prompt 里放哪些东西。纯计算，不依赖 Spring。
 * <p>
 * 按优先级依次放入，放不下就跳过（相当于从低优先级往上裁剪）：
 * <ol>
 *   <li>规则 + 当前问题：永远保留</li>
 *   <li>前 minChunks 块检索资料：永远保留（只有它们自己就超预算时也保留，并标记 overBudget）</li>
 *   <li>最近的对话原文：从新到旧，放得下就放</li>
 *   <li>长期记忆（T8，偏好 / 背景 / 相关主题，通常几十 token）</li>
 *   <li>其余检索块：按分数从高到低</li>
 *   <li>更早对话的摘要</li>
 * </ol>
 * token 数是估算：汉字约 1 字 1 token，其他字符约 4 个 1 token。日志里同时记录估算值和接口返回的实际 prompt_tokens，用来校准。
 */
public final class ContextBudget {

    /**
     * @param chunks       放进 prompt 的检索块（保持原顺序，编号 [1]..[n] 按这个顺序）
     * @param turns        放进 prompt 的历史原文（按时间顺序）
     * @param summary      放进 prompt 的摘要；没有或放不下时为 null
     * @param memory       放进 prompt 的长期记忆；没有或放不下时为 null
     * @param fixedTokens  规则 + 问题
     * @param overBudget   必保留的部分本身就超过上限
     */
    public record Plan(List<Document> chunks, List<Turn> turns, String summary, String memory,
                       int fixedTokens, int chunkTokens, int turnTokens, int summaryTokens, int memoryTokens,
                       int droppedChunks, int droppedTurns, boolean summaryDropped, boolean memoryDropped, boolean overBudget) {

        public int totalTokens() {
            return fixedTokens + chunkTokens + turnTokens + summaryTokens + memoryTokens;
        }

        public boolean trimmed() {
            return droppedChunks > 0 || droppedTurns > 0 || summaryDropped || memoryDropped;
        }
    }

    private final int maxTokens;
    private final int minChunks;

    public ContextBudget(int maxTokens, int minChunks) {
        this.maxTokens = maxTokens;
        this.minChunks = minChunks;
    }

    /**
     * @param fixedText  规则 + 问题（不含检索资料）的文本，用来估算固定部分
     * @param chunks     检索块，按分数降序
     * @param turns      候选的历史原文，按时间顺序
     * @param summary    更早对话的摘要，可为 null
     */
    public Plan plan(String fixedText, List<Document> chunks, List<Turn> turns, String summary) {
        return plan(fixedText, chunks, turns, summary, null);
    }

    /** @param memory 长期记忆的文本（MemoryService.Recall#render），可为 null */
    public Plan plan(String fixedText, List<Document> chunks, List<Turn> turns, String summary, String memory) {
        int fixed = estimate(fixedText);
        int used = fixed;

        // ② 必保留的检索块
        int must = Math.min(minChunks, chunks.size());
        boolean[] keepChunk = new boolean[chunks.size()];
        int chunkTokens = 0;
        for (int i = 0; i < must; i++) {
            keepChunk[i] = true;
            chunkTokens += chunkTokens(chunks.get(i));
        }
        used += chunkTokens;
        boolean overBudget = used > maxTokens;

        // ③ 最近的对话：从新到旧
        List<Turn> keptTurns = new ArrayList<>();
        int turnTokens = 0;
        for (int i = turns.size() - 1; i >= 0; i--) {
            int t = turnTokens(turns.get(i));
            if (used + t > maxTokens) {
                break;   // 更早的轮次也不放：保持历史连续，不跳着放
            }
            keptTurns.add(turns.get(i));
            used += t;
            turnTokens += t;
        }
        Collections.reverse(keptTurns);

        // ④ 长期记忆
        String keptMemory = null;
        int memoryTokens = 0;
        boolean memoryDropped = false;
        if (memory != null && !memory.isBlank()) {
            int t = estimate(memory) + 30;   // 加上说明这段是什么的那一句
            if (used + t <= maxTokens) {
                keptMemory = memory;
                memoryTokens = t;
                used += t;
            } else {
                memoryDropped = true;
            }
        }

        // ⑤ 其余检索块
        for (int i = must; i < chunks.size(); i++) {
            int t = chunkTokens(chunks.get(i));
            if (used + t <= maxTokens) {
                keepChunk[i] = true;
                used += t;
                chunkTokens += t;
            }
        }

        // ⑥ 摘要
        String keptSummary = null;
        int summaryTokens = 0;
        boolean summaryDropped = false;
        if (summary != null && !summary.isBlank()) {
            int t = estimate(summary);
            if (used + t <= maxTokens) {
                keptSummary = summary;
                summaryTokens = t;
            } else {
                summaryDropped = true;
            }
        }

        List<Document> keptChunks = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            if (keepChunk[i]) {
                keptChunks.add(chunks.get(i));
            }
        }
        return new Plan(List.copyOf(keptChunks), List.copyOf(keptTurns), keptSummary, keptMemory,
                fixed, chunkTokens, turnTokens, summaryTokens, memoryTokens,
                chunks.size() - keptChunks.size(), turns.size() - keptTurns.size(), summaryDropped, memoryDropped, overBudget);
    }

    static int chunkTokens(Document d) {
        // 加上 "[n] 《书名》作者，章节" 这一行的开销
        return estimate(d.getText()) + 20;
    }

    static int turnTokens(Turn t) {
        return estimate(t.question()) + estimate(t.answer()) + 8;
    }

    /** 估算 token：汉字（及全角标点）1 个算 1 token，其他字符 4 个算 1 token。 */
    public static int estimate(String s) {
        if (s == null || s.isEmpty()) {
            return 0;
        }
        int wide = 0, other = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN || (c >= 0x3000 && c <= 0x303F) || (c >= 0xFF00 && c <= 0xFFEF)) {
                wide++;
            } else {
                other++;
            }
        }
        return wide + (other + 3) / 4;
    }
}
