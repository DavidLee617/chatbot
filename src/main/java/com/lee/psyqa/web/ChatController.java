package com.lee.psyqa.web;

import com.lee.psyqa.answer.AnswerService;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/** GET /api/chat?q=... → text/event-stream，事件顺序见 AnswerService#stream。 */
@RestController
public class ChatController {

    private static final int MAX_QUESTION_CHARS = 500;

    private final AnswerService answerService;

    public ChatController(AnswerService answerService) {
        this.answerService = answerService;
    }

    @GetMapping(path = "/api/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chat(@RequestParam String q) {
        String question = q.strip();
        if (question.isEmpty() || question.length() > MAX_QUESTION_CHARS) {
            return Flux.just(
                    sse("error", "\"问题不能为空，且不超过 " + MAX_QUESTION_CHARS + " 字\""),
                    sse("done", ""));
        }
        return answerService.stream(question).map(e -> sse(e.name(), e.data()));
    }

    private static ServerSentEvent<String> sse(String event, String data) {
        return ServerSentEvent.<String>builder().event(event).data(data).build();
    }
}
