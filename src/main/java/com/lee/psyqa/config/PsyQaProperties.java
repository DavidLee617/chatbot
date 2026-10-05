package com.lee.psyqa.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;

/**
 * application.yml 里 psyqa.* 的强类型绑定（≈ services.Configure&lt;PsyQaOptions&gt;(config.GetSection("psyqa"))）。
 */
@ConfigurationProperties("psyqa")
public record PsyQaProperties(Retrieval retrieval, Ingest ingest, Crisis crisis) {

    public record Retrieval(int topK, double similarityThreshold) {}

    public record Ingest(String booksDir, int chunkSizeChars, int chunkOverlapChars, int embedBatchSize) {}

    public record Crisis(Resource keywordsFile, String hotlineText) {}
}
