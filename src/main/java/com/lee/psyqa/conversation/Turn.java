package com.lee.psyqa.conversation;

/** 对话里的一轮：用户原问题 + 最终回答（危机 / 拒答时是固定文本）。 */
public record Turn(String question, String answer) {}
