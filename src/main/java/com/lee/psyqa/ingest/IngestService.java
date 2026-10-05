package com.lee.psyqa.ingest;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lee.psyqa.config.PsyQaProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 读 books.json → 逐本清洗 → 切块 → 每 embedBatchSize 块一批写入向量库。
 * 每本书写入前先按 book 元数据删掉旧数据：Milvus 的 insert 不会按主键覆盖，不删会出现重复块。
 */
@Service
public class IngestService {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);
    private static final int MAX_RETRIES = 2;
    private static final long BACKOFF_MS = 1000;
    /** 连续这么多批都失败，多半是账号 / 网络问题（如百炼欠费 Arrearage），继续跑只是浪费时间 */
    private static final int MAX_CONSECUTIVE_FAILED_BATCHES = 3;

    private final VectorStore vectorStore;
    private final GutenbergCleaner cleaner;
    private final ChapterSplitter splitter;
    private final Chunker chunker;
    private final ObjectMapper objectMapper;
    private final PsyQaProperties.Ingest props;

    public IngestService(VectorStore vectorStore, GutenbergCleaner cleaner, ChapterSplitter splitter, Chunker chunker,
                         ObjectMapper objectMapper, PsyQaProperties props) {
        this.vectorStore = vectorStore;
        this.cleaner = cleaner;
        this.splitter = splitter;
        this.chunker = chunker;
        this.objectMapper = objectMapper;
        this.props = props.ingest();
    }

    public record Summary(int books, int chunks, List<String> failedChunkIds) {}

    /** @param onlyFile 只导入这个文件（books.json 里的 file 字段）；null 表示全部 */
    public Summary ingest(String onlyFile) throws IOException {
        Path dir = Path.of(props.booksDir());
        List<BookMeta> books = objectMapper.readValue(dir.resolve("books.json").toFile(), new TypeReference<>() {});
        if (onlyFile != null) {
            books = books.stream().filter(b -> b.file().equals(onlyFile)).toList();
            if (books.isEmpty()) {
                throw new IllegalArgumentException("books.json 里没有 file=" + onlyFile);
            }
        }

        int total = 0;
        int consecutiveFailures = 0;
        List<String> failed = new ArrayList<>();
        for (BookMeta book : books) {
            List<Chapter> chapters = splitter.split(cleaner.clean(Files.readString(dir.resolve(book.file()))));
            List<Chunk> chunks = chunker.chunk(book, chapters);
            System.out.printf("《%s》%d 章，%d 块%n", book.title(), chapters.size(), chunks.size());

            vectorStore.delete(new FilterExpressionBuilder().eq("book", book.title()).build());

            int batchSize = props.embedBatchSize();
            for (int from = 0; from < chunks.size(); from += batchSize) {
                List<Chunk> batch = chunks.subList(from, Math.min(from + batchSize, chunks.size()));
                if (addWithRetry(batch)) {
                    consecutiveFailures = 0;
                } else {
                    batch.forEach(c -> failed.add(c.id()));
                    if (++consecutiveFailures >= MAX_CONSECUTIVE_FAILED_BATCHES) {
                        throw new IllegalStateException("连续 " + consecutiveFailures + " 批写入失败，中止 ingest。检查百炼账号状态和 Milvus 连接后重新导入《"
                                + book.title() + "》（--book " + book.file() + "）");
                    }
                }
                System.out.printf("\r  %d / %d", Math.min(from + batchSize, chunks.size()), chunks.size());
            }
            System.out.println();
            total += chunks.size();
        }
        return new Summary(books.size(), total, failed);
    }

    private boolean addWithRetry(List<Chunk> batch) {
        List<Document> docs = batch.stream().map(Chunk::toDocument).toList();
        for (int attempt = 0; ; attempt++) {
            try {
                vectorStore.add(docs);
                return true;
            } catch (RuntimeException e) {
                if (attempt >= MAX_RETRIES) {
                    log.error("批次写入失败，已重试 {} 次，跳过。首个 chunkId={}", MAX_RETRIES, batch.get(0).id(), e);
                    return false;
                }
                long wait = BACKOFF_MS << attempt;   // 1s, 2s
                log.warn("批次写入失败（第 {} 次），{} ms 后重试：{}", attempt + 1, wait, e.getMessage());
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
    }
}
