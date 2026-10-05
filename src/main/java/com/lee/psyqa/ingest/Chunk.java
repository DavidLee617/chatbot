package com.lee.psyqa.ingest;

import org.springframework.ai.document.Document;

import java.util.Map;

/**
 * 一个检索块。id 由 (book, chapter, chunkIndex) 确定性生成，同时用作 Document id，重复 ingest 幂等。
 */
public record Chunk(String id, String book, String author, String chapter, int chunkIndex, String sourceUrl, String text) {

    public Document toDocument() {
        return Document.builder()
                .id(id)
                .text(text)
                .metadata(Map.of(
                        "chunkId", id,
                        "book", book,
                        "author", author,
                        "chapter", chapter,
                        "chunkIndex", chunkIndex,
                        "sourceUrl", sourceUrl))
                .build();
    }
}
