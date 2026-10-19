package com.lee.psyqa.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 记忆的两次模型调用（T8）：
 * <ol>
 *   <li>{@link #extract}：从这一轮里提炼偏好 / 背景（facts）和主题（topics），prompts/memory-extract.st</li>
 *   <li>{@link #reconcile}：新 fact 和相似的旧记忆"对账"，决定新增 / 更新 / 删除 / 不变，prompts/memory-reconcile.st</li>
 * </ol>
 * 输出都要求是 JSON；解析失败抛异常，由 MemoryService 记日志、这一轮不写记忆（记忆是增强，宁可漏记）。
 */
@Component
public class MemoryExtractor {

    public record Fact(Memory.Kind kind, String content) {}

    public record Extraction(List<Fact> facts, List<String> topics, int tokens) {

        public boolean isEmpty() {
            return facts.isEmpty() && topics.isEmpty();
        }
    }

    public enum ActionType { ADD, UPDATE, DELETE, NONE }

    /** @param target 1 起的旧记忆编号；ADD 时为 0 */
    public record Action(ActionType type, int target, String content, int tokens) {}

    static final int MAX_FACTS = 3;
    static final int MAX_TOPICS = 2;
    static final int MAX_CONTENT_CHARS = 80;
    /** 提炼时带上的前几轮（只为理解这一轮）。 */
    static final int CONTEXT_TURNS = 2;
    static final int ANSWER_CHARS = 200;

    private final ChatClient chatClient;
    private final ObjectMapper mapper;
    private final String extractPrompt;
    private final String reconcilePrompt;

    public MemoryExtractor(ChatClient.Builder builder, ObjectMapper mapper,
                           @Value("classpath:prompts/memory-extract.st") Resource extractPrompt,
                           @Value("classpath:prompts/memory-reconcile.st") Resource reconcilePrompt) throws IOException {
        this.chatClient = builder.build();
        this.mapper = mapper;
        this.extractPrompt = extractPrompt.getContentAsString(StandardCharsets.UTF_8);
        this.reconcilePrompt = reconcilePrompt.getContentAsString(StandardCharsets.UTF_8);
    }

    public Extraction extract(List<Turn> history, Turn current) {
        ChatResponse resp = call(extractPrompt, formatTurns(history, current));
        return parseExtraction(text(resp), tokens(resp));
    }

    public Action reconcile(List<Memory> similar, Fact fact) {
        StringBuilder sb = new StringBuilder("Existing memories:\n");
        for (int i = 0; i < similar.size(); i++) {
            Memory m = similar.get(i);
            sb.append('[').append(i + 1).append("] (").append(m.kind()).append(") ").append(m.content()).append('\n');
        }
        sb.append("New information: (").append(fact.kind()).append(") ").append(fact.content());
        ChatResponse resp = call(reconcilePrompt, sb.toString());
        return parseAction(text(resp), similar.size(), tokens(resp));
    }

    static String formatTurns(List<Turn> history, Turn current) {
        StringBuilder sb = new StringBuilder();
        List<Turn> earlier = history.subList(Math.max(0, history.size() - CONTEXT_TURNS), history.size());
        if (!earlier.isEmpty()) {
            sb.append("Earlier turns:\n");
            for (Turn t : earlier) {
                sb.append("User: ").append(t.question().strip()).append('\n')
                        .append("Assistant: ").append(abbreviate(t.answer())).append('\n');
            }
        }
        return sb.append("CURRENT turn:\nUser: ").append(current.question().strip()).append('\n')
                .append("Assistant: ").append(abbreviate(current.answer())).toString();
    }

    Extraction parseExtraction(String raw, int tokens) {
        JsonNode root = json(raw);
        List<Fact> facts = new ArrayList<>();
        for (JsonNode f : root.path("facts")) {
            String kind = f.path("kind").asText("").toUpperCase(Locale.ROOT);
            String content = f.path("content").asText("").strip();
            // 只接受两种 fact；TOPIC 走 topics 列表
            if ((kind.equals("PREFERENCE") || kind.equals("BACKGROUND")) && !content.isEmpty()
                    && content.length() <= MAX_CONTENT_CHARS && facts.size() < MAX_FACTS) {
                facts.add(new Fact(Memory.Kind.valueOf(kind), content));
            }
        }
        List<String> topics = new ArrayList<>();
        for (JsonNode t : root.path("topics")) {
            String topic = t.asText("").strip();
            if (!topic.isEmpty() && topic.length() <= MAX_CONTENT_CHARS && topics.size() < MAX_TOPICS) {
                topics.add(topic);
            }
        }
        return new Extraction(List.copyOf(facts), List.copyOf(topics), tokens);
    }

    Action parseAction(String raw, int candidates, int tokens) {
        JsonNode root = json(raw);
        ActionType type = ActionType.valueOf(root.path("action").asText("").toUpperCase(Locale.ROOT));
        int target = root.path("target").asInt(0);
        String content = root.path("content").asText("").strip();
        if ((type == ActionType.UPDATE || type == ActionType.DELETE || type == ActionType.NONE)
                && (target < 1 || target > candidates)) {
            throw new IllegalStateException("对账结果的 target 越界：" + target);
        }
        if (type == ActionType.UPDATE && (content.isEmpty() || content.length() > MAX_CONTENT_CHARS)) {
            throw new IllegalStateException("对账 UPDATE 没有给出合法的 content");
        }
        return new Action(type, target, content, tokens);
    }

    /** 模型偶尔会包一层 ```json 代码块：取第一个 { 到最后一个 }。 */
    private JsonNode json(String raw) {
        int from = raw == null ? -1 : raw.indexOf('{');
        int to = raw == null ? -1 : raw.lastIndexOf('}');
        if (from < 0 || to < from) {
            throw new IllegalStateException("记忆模型输出不是 JSON：" + abbreviate(raw));
        }
        try {
            return mapper.readTree(raw.substring(from, to + 1));
        } catch (IOException e) {
            throw new IllegalStateException("记忆模型输出 JSON 解析失败：" + abbreviate(raw), e);
        }
    }

    private ChatResponse call(String system, String user) {
        return chatClient.prompt()
                .system(system)
                .user(user)
                .options(OpenAiChatOptions.builder().temperature(0.0).build())
                .call()
                .chatResponse();
    }

    private static String text(ChatResponse resp) {
        return resp == null || resp.getResult() == null ? "" : resp.getResult().getOutput().getText();
    }

    private static int tokens(ChatResponse resp) {
        Usage u = resp == null ? null : resp.getMetadata().getUsage();
        return u == null || u.getTotalTokens() == null ? 0 : u.getTotalTokens();
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        String t = s.replaceAll("\\s+", " ").strip();
        return t.length() > ANSWER_CHARS ? t.substring(0, ANSWER_CHARS) + "…" : t;
    }
}
