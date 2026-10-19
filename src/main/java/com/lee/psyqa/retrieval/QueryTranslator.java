package com.lee.psyqa.retrieval;

import com.lee.psyqa.conversation.Turn;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 把中文问题改写成一句英文检索语句（知识库是英文原文，英文提问的 recall 明显更高）。
 * <p>
 * 只用于检索：危机判断在翻译之前、用原问题；生成回答也用用户的中文原问题。
 * 没有汉字的问题不翻译，直接返回 empty。temperature 0，尽量让同一问题每次译文一致。
 * <p>
 * prompt 放在 system，用户原问题单独作为 user 消息；译文必须通过 {@link #validate} 才用，否则抛异常，
 * 由 RetrievalService 退回原问题检索。2026-10-19 第一版 prompt 实测的失败：模型把 prompt 里的说明
 * （"in the English translations of Jung and Freud"、术语表）抄进译文，把"推荐电影"译成一句满是心理学术语的查询；
 * 还有一次直接用中文回答了问题。
 * <p>
 * 多轮对话（T4）：有历史时改用 prompts/rewrite.st，结合最近几轮把追问改写成一句独立的英文问题
 * （"那他和弗洛伊德有什么不同" → "How does Jung differ from Freud …"）。翻译和改写合成一次调用。
 * 有历史时英文问题也要改写（指代问题和语言无关）。
 */
@Component
public class QueryTranslator {

    private static final Pattern HAN = Pattern.compile("\\p{IsHan}");

    /** @param text 英文检索语句 */
    public record Translation(String text, long latencyMs, int promptTokens, int completionTokens) {}

    /** 改写时带上最近几轮；更早的对话对指代消解帮助很小，还会拉长 prompt。 */
    static final int REWRITE_TURNS = 3;
    /** 历史回答只取开头这么多字：指代通常在回答开头就能解析（"弗洛伊德把焦虑分成两种……"）。 */
    static final int ANSWER_CHARS = 300;
    /**
     * 改写结果的长度上限。不按原问题长度算：追问常常只有几个字（"后一种呢？"），展开后理应比它长。
     * 2026-10-19 旧提示词把上一轮回答整段抄进改写（h04 182 字被拒），新提示词要求 25 词以内，这里留余量。
     */
    static final int REWRITE_MAX_CHARS = 300;

    private final ChatClient chatClient;
    private final String template;
    private final String rewriteTemplate;

    public QueryTranslator(ChatClient.Builder chatClientBuilder,
                           @Value("classpath:prompts/translate.st") Resource template,
                           @Value("classpath:prompts/rewrite.st") Resource rewriteTemplate) throws IOException {
        this.chatClient = chatClientBuilder.build();
        this.template = template.getContentAsString(StandardCharsets.UTF_8);
        this.rewriteTemplate = rewriteTemplate.getContentAsString(StandardCharsets.UTF_8);
    }

    public static boolean needsTranslation(String question) {
        return HAN.matcher(question).find();
    }

    public Optional<Translation> translate(String question) {
        return translate(question, List.of());
    }

    /**
     * 没有历史：中文问题翻译、英文问题不动（返回 empty）。
     * 有历史：不论中英文都改写成独立的英文问题。
     */
    public Optional<Translation> translate(String question, List<Turn> history) {
        if (history.isEmpty()) {
            return needsTranslation(question)
                    ? Optional.of(call(template, question, maxTranslationChars(question)))
                    : Optional.empty();
        }
        return Optional.of(call(rewriteTemplate, formatConversation(history, question), REWRITE_MAX_CHARS));
    }

    /** 改写 prompt 的 user 消息：最近 REWRITE_TURNS 轮 + 当前消息。 */
    static String formatConversation(List<Turn> history, String question) {
        StringBuilder sb = new StringBuilder("Conversation:\n");
        for (Turn t : history.subList(Math.max(0, history.size() - REWRITE_TURNS), history.size())) {
            String a = t.answer() == null ? "" : t.answer().replaceAll("\\s+", " ").strip();
            sb.append("User: ").append(t.question().strip()).append('\n')
                    .append("Assistant: ").append(a.length() > ANSWER_CHARS ? a.substring(0, ANSWER_CHARS) + "…" : a).append('\n');
        }
        return sb.append("Latest message: ").append(question.strip()).toString();
    }

    private Translation call(String system, String user, int maxChars) {
        long t0 = System.nanoTime();
        ChatResponse resp = chatClient.prompt()
                .system(system)
                .user(user)
                .options(OpenAiChatOptions.builder().temperature(0.0).build())
                .call()
                .chatResponse();
        String raw = resp == null || resp.getResult() == null ? "" : resp.getResult().getOutput().getText();
        String text = clean(raw);
        validate(text, maxChars);
        Usage u = resp.getMetadata().getUsage();
        return new Translation(text, (System.nanoTime() - t0) / 1_000_000,
                u == null || u.getPromptTokens() == null ? 0 : u.getPromptTokens(),
                u == null || u.getCompletionTokens() == null ? 0 : u.getCompletionTokens());
    }

    static int maxTranslationChars(String question) {
        return question.length() * 6 + 80;
    }

    /** 译文必须非空、不含汉字（含汉字说明模型在回答而不是翻译），长度也不能超过 maxChars（单轮翻译按原问题长度算）。 */
    static void validate(String text, int maxChars) {
        if (text.isEmpty()) {
            throw new IllegalStateException("翻译结果为空");
        }
        if (HAN.matcher(text).find()) {
            throw new IllegalStateException("翻译结果含汉字，模型可能在回答而不是翻译：" + abbreviate(text));
        }
        if (text.length() > maxChars) {
            throw new IllegalStateException("翻译结果过长：" + abbreviate(text));
        }
    }

    private static String abbreviate(String s) {
        return s.length() > 80 ? s.substring(0, 80) + "…" : s;
    }

    /** 只取第一行非空文本，去掉模型偶尔加上的引号。 */
    static String clean(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.lines().map(String::strip).filter(l -> !l.isEmpty()).findFirst().orElse("")
                .replaceAll("^[\"'“”‘’]+|[\"'“”‘’]+$", "").strip();
    }
}
