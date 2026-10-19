package com.lee.psyqa.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import com.lee.psyqa.retrieval.RetrievalService.QueryMode;
import org.springframework.core.io.Resource;

/**
 * application.yml 里 psyqa.* 的强类型绑定（≈ services.Configure&lt;PsyQaOptions&gt;(config.GetSection("psyqa"))）。
 */
@ConfigurationProperties("psyqa")
public record PsyQaProperties(Retrieval retrieval, Ingest ingest, Crisis crisis, Context context) {

    /**
     * @param queryMode ORIGINAL / TRANSLATED / BOTH，见 RetrievalService（T3）
     * @param hnswEf    Milvus HNSW 搜索时的候选数 ef，越大越接近精确检索；不能小于 topK
     * @param rerank    rerank 重排序（T5）
     */
    public record Retrieval(int topK, double similarityThreshold, QueryMode queryMode, int hnswEf, Rerank rerank) {}

    /**
     * @param enabled    是否在向量检索后做 rerank
     * @param candidates 粗召回的候选块数，rerank 后再取 topK
     */
    public record Rerank(boolean enabled, String model, String url, int candidates) {}

    public record Ingest(String booksDir, int chunkSizeChars, int chunkOverlapChars, int embedBatchSize) {}

    /**
     * 上下文预算（T4），见 ContextBudget。
     *
     * @param maxPromptTokens prompt 总 token 上限（估算值）
     * @param minChunks       无论如何都保留的检索块数
     * @param keepRawTurns    摘要之后至少保留原文的最近轮数
     * @param summarizeBatch  没摘要的轮次超过 keepRawTurns 这么多轮时，压缩最早的这一批
     */
    public record Context(int maxPromptTokens, int minChunks, int keepRawTurns, int summarizeBatch) {}

    /**
     * @param semanticThreshold 没命中关键词时，语义层判危机要求的最低危机分（另需危机分 &gt; 理论分），见 CrisisDetector
     */
    public record Crisis(Resource strongKeywordsFile, Resource weakKeywordsFile,
                         Resource examplesFile, Resource theoryExamplesFile,
                         double semanticThreshold, String hotlineText) {}
}
