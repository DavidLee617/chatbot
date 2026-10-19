package com.lee.psyqa.retrieval;

import com.lee.psyqa.config.PsyQaProperties;
import com.lee.psyqa.conversation.Turn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.milvus.MilvusSearchRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 检索 top-k，再按相似度阈值过滤。
 * 阈值在代码里过滤而不是交给 SearchRequest，这样被丢掉的分数也能记进日志，用来调阈值。
 * <p>
 * 查询方式（psyqa.retrieval.query-mode，T3）：
 * <ul>
 *   <li>ORIGINAL：用原问题检索</li>
 *   <li>TRANSLATED：中文问题先译成英文，只用译文检索</li>
 *   <li>BOTH：原问题和译文各检索 top-k，按块 id 去重、保留较高分，再取 top-k</li>
 * </ul>
 * 没有汉字的问题不翻译，三种方式等价。翻译失败时退回原问题检索，不影响回答。
 * 有对话历史时（T4），"译文"换成结合历史改写出的独立英文问题，英文问题也会改写。
 * AnswerService 和 EvalService 都调用这里，保证评估测的是真实路径。
 */
@Service
public class RetrievalService {

    private static final Logger log = LoggerFactory.getLogger(RetrievalService.class);

    public enum QueryMode { ORIGINAL, TRANSLATED, BOTH }

    private final VectorStore vectorStore;
    private final QueryTranslator translator;
    private final PsyQaProperties.Retrieval props;

    public RetrievalService(VectorStore vectorStore, QueryTranslator translator, PsyQaProperties props) {
        this.vectorStore = vectorStore;
        this.translator = translator;
        this.props = props.retrieval();
    }

    /**
     * @param hits        top-k（按分数降序，含阈值以下的）
     * @param kept        过阈值的块
     * @param topkScores  hits 的分数
     * @param translation 译文；没翻译（ORIGINAL、没有汉字、翻译失败）时为 null
     */
    public record Result(List<Document> hits, List<Document> kept, List<Double> topkScores,
                         QueryMode mode, QueryTranslator.Translation translation) {

        /** 测试和拒答场景用：只有分数、没有块。 */
        public Result(List<Document> kept, List<Double> topkScores) {
            this(kept, kept, topkScores, QueryMode.ORIGINAL, null);
        }
    }

    public Result retrieve(String question) {
        return retrieve(question, List.of());
    }

    public Result retrieve(String question, List<Turn> history) {
        return retrieve(question, history, props.topK(), props.queryMode());
    }

    public Result retrieve(String question, int k, QueryMode mode) {
        return retrieve(question, List.of(), k, mode);
    }

    /** @param history 对话历史，空表示单轮；ORIGINAL 模式下忽略 */
    public Result retrieve(String question, List<Turn> history, int k, QueryMode mode) {
        QueryTranslator.Translation t = mode == QueryMode.ORIGINAL ? null : translateOrNull(question, history);
        List<Document> hits;
        if (t == null) {
            hits = search(question, k);
        } else if (mode == QueryMode.TRANSLATED) {
            hits = search(t.text(), k);
        } else {
            hits = merge(search(question, k), search(t.text(), k), k);
        }
        List<Double> scores = hits.stream().map(RetrievalService::score).toList();
        List<Document> kept = hits.stream().filter(d -> score(d) >= props.similarityThreshold()).toList();
        return new Result(hits, kept, scores, mode, t);
    }

    public double threshold() {
        return props.similarityThreshold();
    }

    public QueryMode defaultMode() {
        return props.queryMode();
    }

    private QueryTranslator.Translation translateOrNull(String question, List<Turn> history) {
        try {
            Optional<QueryTranslator.Translation> t = translator.translate(question, history);
            return t.orElse(null);
        } catch (RuntimeException e) {
            log.warn("查询翻译失败，退回原问题检索：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 唯一依赖 Milvus 专有类的地方：HNSW 的搜索参数 ef 只能通过 MilvusSearchRequest 传。
     * 不传时 Milvus 的默认 ef 太小，会漏掉真正最近的块（2026-10-19 发现：a14 的真 top-1 在 top-5、top-20 都没被找回，
     * ef=64 后和暴力计算一致）。换向量库时只改这个方法。
     */
    private List<Document> search(String query, int k) {
        return vectorStore.similaritySearch(MilvusSearchRequest.milvusBuilder()
                .query(query)
                .topK(k)
                .similarityThresholdAll()
                .searchParamsJson("{\"ef\":" + Math.max(props.hnswEf(), k) + "}")
                .build());
    }

    /** 两路结果按块 id 去重、保留较高分，按分数降序取前 k。两路用同一个 embedding 模型，余弦分可以直接比。 */
    static List<Document> merge(List<Document> a, List<Document> b, int k) {
        Map<String, Document> best = new LinkedHashMap<>();
        for (List<Document> list : List.of(a, b)) {
            for (Document d : list) {
                best.merge(d.getId(), d, (x, y) -> score(y) > score(x) ? y : x);
            }
        }
        List<Document> out = new ArrayList<>(best.values());
        out.sort(Comparator.comparingDouble(RetrievalService::score).reversed());
        return out.size() > k ? List.copyOf(out.subList(0, k)) : List.copyOf(out);
    }

    public static double score(Document d) {
        return d.getScore() == null ? 0 : d.getScore();
    }
}
