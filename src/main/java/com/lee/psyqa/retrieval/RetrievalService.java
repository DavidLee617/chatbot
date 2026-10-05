package com.lee.psyqa.retrieval;

import com.lee.psyqa.config.PsyQaProperties;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 检索 top-k，再按相似度阈值过滤。
 * 阈值在代码里过滤而不是交给 SearchRequest，这样被丢掉的分数也能记进日志，用来调阈值。
 */
@Service
public class RetrievalService {

    private final VectorStore vectorStore;
    private final PsyQaProperties.Retrieval props;

    public RetrievalService(VectorStore vectorStore, PsyQaProperties props) {
        this.vectorStore = vectorStore;
        this.props = props.retrieval();
    }

    /** @param kept 过阈值的块（按分数降序）；topkScores 全部 top-k 的分数（含被丢掉的） */
    public record Result(List<Document> kept, List<Double> topkScores) {}

    public Result retrieve(String question) {
        List<Document> hits = vectorStore.similaritySearch(SearchRequest.builder()
                .query(question)
                .topK(props.topK())
                .similarityThresholdAll()
                .build());
        List<Double> scores = hits.stream().map(RetrievalService::score).toList();
        List<Document> kept = hits.stream().filter(d -> score(d) >= props.similarityThreshold()).toList();
        return new Result(kept, scores);
    }

    public static double score(Document d) {
        return d.getScore() == null ? 0 : d.getScore();
    }
}
