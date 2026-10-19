package com.lee.psyqa.conversation;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 把一批较早的对话压缩成要点（T4 第 2 步）。只压缩新的这一批，结果接在已有摘要后面，不重写旧摘要：
 * 旧摘要不变，prompt 的前缀就稳定，利于提示缓存；也避免反复摘要导致信息逐渐失真。
 */
@Component
public class ConversationSummarizer {

    private final ChatClient chatClient;
    private final String template;

    public ConversationSummarizer(ChatClient.Builder chatClientBuilder,
                                  @Value("classpath:prompts/summarize.st") Resource template) throws IOException {
        this.chatClient = chatClientBuilder.build();
        this.template = template.getContentAsString(StandardCharsets.UTF_8);
    }

    public String summarize(List<Turn> turns) {
        StringBuilder sb = new StringBuilder();
        for (Turn t : turns) {
            sb.append("用户：").append(t.question().strip()).append('\n')
                    .append("助手：").append(t.answer() == null ? "" : t.answer().strip()).append("\n\n");
        }
        String out = chatClient.prompt()
                .system(template)
                .user(sb.toString())
                .options(OpenAiChatOptions.builder().temperature(0.0).build())
                .call()
                .content();
        if (out == null || out.isBlank()) {
            throw new IllegalStateException("摘要为空");
        }
        return out.strip();
    }
}
