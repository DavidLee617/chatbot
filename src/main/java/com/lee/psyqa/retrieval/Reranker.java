package com.lee.psyqa.retrieval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.lee.psyqa.config.PsyQaProperties;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 百炼 rerank（T5）：向量检索粗召回 top-N 后，用 cross-encoder 对"问题 + 每块原文"逐对打分，再取前 k。
 * <p>
 * 向量检索是问题和块各自编码再比相似度（bi-encoder），快但粗；rerank 把问题和块放在一起编码，
 * 能看出"段落是不是在回答这个问题"，慢且贵，所以只对少量候选做。
 * <p>
 * Spring AI 没有封装百炼的 rerank，这里用 RestClient 直接调兼容接口 /compatible-api/v1/reranks
 * （2026-10-19 核对：模型 qwen3.7-text-rerank）。
 * 返回的块保留原来的余弦分（阈值、拒答仍按余弦分判断），rerank 分写进 metadata 的 rerankScore。
 */
@Component
public class Reranker {

    /** @param docs 按 rerank 分降序的前 topN 块 */
    public record Outcome(List<Document> docs, long latencyMs, int tokens) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Response(List<Item> results, Usage usage) {

        @JsonIgnoreProperties(ignoreUnknown = true)
        record Item(int index, double relevance_score) {}

        @JsonIgnoreProperties(ignoreUnknown = true)
        record Usage(Integer total_tokens) {}
    }

    private final RestClient client;
    private final PsyQaProperties.Rerank props;

    public Reranker(RestClient.Builder builder, @Value("${spring.ai.openai.api-key}") String apiKey, PsyQaProperties props) {
        this.props = props.retrieval().rerank();
        this.client = builder.baseUrl(this.props.url())
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .build();
    }

    public Outcome rerank(String query, List<Document> docs, int topN) {
        long t0 = System.nanoTime();
        Response resp = client.post()
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("model", props.model(), "query", query,
                        "documents", docs.stream().map(d -> d.getText() == null ? "" : d.getText()).toList(),
                        "top_n", Math.min(topN, docs.size())))
                .retrieve()
                .body(Response.class);
        if (resp == null || resp.results() == null) {
            throw new IllegalStateException("rerank 返回为空");
        }
        List<Document> out = new ArrayList<>();
        for (Response.Item item : resp.results()) {
            Document d = docs.get(item.index());
            Map<String, Object> meta = new HashMap<>(d.getMetadata());
            meta.put("rerankScore", item.relevance_score());
            out.add(Document.builder().id(d.getId()).text(d.getText()).metadata(meta).score(d.getScore()).build());
        }
        return new Outcome(List.copyOf(out), (System.nanoTime() - t0) / 1_000_000,
                resp.usage() == null || resp.usage().total_tokens() == null ? 0 : resp.usage().total_tokens());
    }
}
