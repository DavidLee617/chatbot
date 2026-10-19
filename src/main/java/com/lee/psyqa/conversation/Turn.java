package com.lee.psyqa.conversation;

/**
 * 对话里的一轮：用户原问题 + 最终回答（危机 / 拒答时是固定文本）。
 *
 * @param crisis 这一轮被判为危机（T7：之后几轮的危机判断要知道）
 */
public record Turn(String question, String answer, boolean crisis) {

    public Turn(String question, String answer) {
        this(question, answer, false);
    }
}
