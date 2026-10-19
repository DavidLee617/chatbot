package com.lee.psyqa.guard;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * 危机判定的 LLM 层（T7）：语义层觉得"更像危机"时，带上最近几轮用户原话，让模型判断是不是用户本人有风险。
 * <p>
 * 语义层实际分的是"第一人称倾诉"和"第三人称理论问题"，所以第一人称但没有危险的话会误报
 * （"我是不是太内向了才这么累""朋友也是这样，我该怎么帮他"），而指代上文的话又要读上下文才知道指什么
 * （"Same here … how I would do it"）。这两件事都是语义理解，交给模型。
 * <p>
 * 只输出 RISK / SAFE；输出不是这两个词时抛异常，由 CrisisDetector 退回语义层的判定。
 */
@Component
public class CrisisJudge {

    public record Verdict(boolean risk, long latencyMs) {}

    private final ChatClient chatClient;
    private final String prompt;

    public CrisisJudge(ChatClient.Builder builder, @Value("classpath:prompts/crisis-judge.st") Resource prompt) throws IOException {
        this.chatClient = builder.build();
        this.prompt = prompt.getContentAsString(StandardCharsets.UTF_8);
    }

    /** @param userMessages 最近几轮用户原话 + 这一句，按时间顺序 */
    public Verdict judge(List<String> userMessages) {
        long t0 = System.nanoTime();
        String out = chatClient.prompt()
                .system(prompt)
                .user(format(userMessages))
                .options(OpenAiChatOptions.builder().temperature(0.0).maxTokens(5).build())
                .call()
                .content();
        return new Verdict(parse(out), (System.nanoTime() - t0) / 1_000_000);
    }

    static String format(List<String> userMessages) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < userMessages.size(); i++) {
            sb.append(i == userMessages.size() - 1 ? "LATEST: " : "Earlier: ").append(userMessages.get(i).strip()).append('\n');
        }
        return sb.toString();
    }

    static boolean parse(String out) {
        String s = out == null ? "" : out.strip().toUpperCase(Locale.ROOT);
        if (s.startsWith("RISK")) {
            return true;
        }
        if (s.startsWith("SAFE")) {
            return false;
        }
        throw new IllegalStateException("危机判定输出无法解析：" + (s.length() > 40 ? s.substring(0, 40) : s));
    }
}
