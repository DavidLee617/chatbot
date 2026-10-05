package com.lee.psyqa.answer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lee.psyqa.guard.CrisisGuard;
import com.lee.psyqa.model.AskResult;
import com.lee.psyqa.model.AskResult.Route;
import com.lee.psyqa.model.SourceRef;
import com.lee.psyqa.retrieval.RetrievalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * 问答主流程：① 危机路由 → ② 检索 + 阈值 → ③ 拼 prompt → ④ 调模型 → ⑤ 回答 + 来源。
 * ①② 不通过时直接返回固定文本，不调模型。
 */
@Service
public class AnswerService {

    private static final Logger log = LoggerFactory.getLogger(AnswerService.class);

    static final String REFUSAL = "知识库里没有找到相关内容。这个助手只能回答荣格、弗洛伊德经典著作中涉及的心理学问题，可以换个问法试试。";
    private static final int SNIPPET_CHARS = 200;

    private final CrisisGuard crisisGuard;
    private final RetrievalService retrieval;
    private final PromptBuilder promptBuilder;
    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;

    public AnswerService(CrisisGuard crisisGuard, RetrievalService retrieval, PromptBuilder promptBuilder,
                         ChatClient.Builder chatClientBuilder, ObjectMapper objectMapper) {
        this.crisisGuard = crisisGuard;
        this.retrieval = retrieval;
        this.promptBuilder = promptBuilder;
        this.chatClient = chatClientBuilder.build();
        this.objectMapper = objectMapper;
    }

    /** SSE 事件：name 是 event 名，data 已经是 JSON 文本。 */
    public record StreamEvent(String name, String data) {}

    /** ①② 的结果。route != ANSWERED 时 fixedText 就是最终回答。 */
    private record Prepared(Route route, String fixedText, List<SourceRef> sources, String prompt, List<Double> topkScores) {}

    /** 同步问答（CLI 用）。 */
    public AskResult ask(String question) {
        long t0 = System.nanoTime();
        Prepared p = prepare(question);
        if (p.route() != Route.ANSWERED) {
            logLine(p, question, null, t0);
            return new AskResult(p.fixedText(), List.of(), p.route());
        }
        ChatResponse resp = chatClient.prompt().user(p.prompt()).call().chatResponse();
        String answer = resp == null || resp.getResult() == null ? "" : resp.getResult().getOutput().getText();
        logLine(p, question, resp == null ? null : resp.getMetadata().getUsage(), t0);
        return new AskResult(answer, p.sources(), p.route());
    }

    /**
     * 流式问答（Web 用）。事件顺序：route → sources（仅 ANSWERED）→ token… → done。
     * 检索是阻塞调用，放到 boundedElastic 线程池上，不占 WebFlux 的事件循环线程（≈ Task.Run 包住同步 IO）。
     */
    public Flux<StreamEvent> stream(String question) {
        return Flux.defer(() -> {
            long t0 = System.nanoTime();
            return Mono.fromCallable(() -> prepare(question))
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMapMany(p -> {
                        Flux<StreamEvent> head = Flux.just(new StreamEvent("route", json(p.route().name())));
                        if (p.route() != Route.ANSWERED) {
                            logLine(p, question, null, t0);
                            return Flux.concat(head, Flux.just(new StreamEvent("token", json(p.fixedText())), done()));
                        }
                        // 流式调用时 usage 在最后一个响应里（需要 stream-usage: true）
                        AtomicReference<Usage> usage = new AtomicReference<>();
                        Flux<StreamEvent> tokens = chatClient.prompt().user(p.prompt()).stream().chatResponse()
                                .doOnNext(r -> {
                                    Usage u = r.getMetadata().getUsage();
                                    if (u != null && u.getPromptTokens() != null && u.getPromptTokens() > 0) {
                                        usage.set(u);
                                    }
                                })
                                .mapNotNull(r -> r.getResult() == null ? null : r.getResult().getOutput().getText())
                                .filter(t -> !t.isEmpty())
                                .map(t -> new StreamEvent("token", json(t)))
                                .doOnComplete(() -> logLine(p, question, usage.get(), t0))
                                .onErrorResume(e -> {
                                    log.error("模型调用失败", e);
                                    return Flux.just(new StreamEvent("error", json("模型调用失败，请稍后再试。")));
                                });
                        return Flux.concat(head,
                                Flux.just(new StreamEvent("sources", json(p.sources()))),
                                tokens,
                                Flux.just(done()));
                    })
                    // 检索阶段（embedding / Milvus）失败：仍按 SSE 协议发 error + done，而不是让连接变成 HTTP 500
                    .onErrorResume(e -> {
                        log.error("检索失败", e);
                        return Flux.just(new StreamEvent("error", json("检索服务暂时不可用，请稍后再试。")), done());
                    });
        });
    }

    private Prepared prepare(String question) {
        if (crisisGuard.matches(question)) {
            return new Prepared(Route.CRISIS, crisisGuard.response(), List.of(), null, List.of());
        }
        RetrievalService.Result r = retrieval.retrieve(question);
        if (r.kept().isEmpty()) {
            return new Prepared(Route.REFUSED, REFUSAL, List.of(), null, r.topkScores());
        }
        List<SourceRef> sources = new ArrayList<>();
        for (int i = 0; i < r.kept().size(); i++) {
            Document d = r.kept().get(i);
            Map<String, Object> m = d.getMetadata();
            String text = d.getText() == null ? "" : d.getText();
            sources.add(new SourceRef(i + 1, str(m.get("book")), str(m.get("author")), str(m.get("chapter")),
                    RetrievalService.score(d), text.length() > SNIPPET_CHARS ? text.substring(0, SNIPPET_CHARS) + "…" : text));
        }
        return new Prepared(Route.ANSWERED, null, sources, promptBuilder.build(question, r.kept()), r.topkScores());
    }

    /** route=ANSWERED q_len=18 topk_scores=[0.71,0.66] kept=2 prompt_tokens=2410 completion_tokens=380 latency_ms=3120 */
    private void logLine(Prepared p, String question, Usage usage, long t0) {
        log.info("route={} q_len={} topk_scores=[{}] kept={} prompt_tokens={} completion_tokens={} latency_ms={}",
                p.route(), question.length(),
                p.topkScores().stream().map(s -> "%.2f".formatted(s)).collect(Collectors.joining(",")),
                p.sources().size(),
                usage == null ? 0 : usage.getPromptTokens(),
                usage == null ? 0 : usage.getCompletionTokens(),
                (System.nanoTime() - t0) / 1_000_000);
    }

    private static StreamEvent done() {
        return new StreamEvent("done", "");
    }

    /** token 也用 JSON 编码：SSE 的 data 行首空格会被浏览器吞掉，换行也要转义。 */
    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }
}
