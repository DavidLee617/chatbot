package com.lee.psyqa.retrieval;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** T3：查询翻译的辅助逻辑和 BOTH 模式的合并。不联网。 */
class RetrievalServiceTest {

    private static Document doc(String id, double score) {
        return Document.builder().id(id).text(id).score(score).build();
    }

    @Test
    void mergeKeepsHigherScorePerChunkAndTopK() {
        var original = List.of(doc("a", 0.50), doc("b", 0.48), doc("c", 0.40));
        var translated = List.of(doc("b", 0.62), doc("d", 0.55), doc("a", 0.45));

        List<Document> merged = RetrievalService.merge(original, translated, 3);

        assertThat(merged).extracting(Document::getId).containsExactly("b", "d", "a");
        assertThat(merged).extracting(RetrievalService::score).containsExactly(0.62, 0.55, 0.50);
    }

    @Test
    void onlyQuestionsWithChineseAreTranslated() {
        assertThat(QueryTranslator.needsTranslation("荣格说的集体无意识是什么？")).isTrue();
        assertThat(QueryTranslator.needsTranslation("最近真的很 down")).isTrue();
        assertThat(QueryTranslator.needsTranslation("What is the collective unconscious?")).isFalse();
    }

    @Test
    void cleanTakesFirstLineAndStripsQuotes() {
        assertThat(QueryTranslator.clean("\"What is libido according to Jung?\"\n\nExplanation: ..."))
                .isEqualTo("What is libido according to Jung?");
        assertThat(QueryTranslator.clean("“Electra complex”")).isEqualTo("Electra complex");
        assertThat(QueryTranslator.clean("  \n")).isEmpty();
    }

    @Test
    void validateRejectsAnswersAndRunawayOutput() {
        String q = "荣格说的集体无意识是什么？";
        QueryTranslator.validate("What is the collective unconscious according to Jung?", QueryTranslator.maxTranslationChars(q));
        assertThatThrownBy(() -> QueryTranslator.validate("荣格所说的集体无意识是指……", QueryTranslator.maxTranslationChars(q)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> QueryTranslator.validate("x ".repeat(100), QueryTranslator.maxTranslationChars("电影推荐？")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> QueryTranslator.validate("", 100)).isInstanceOf(IllegalStateException.class);
        // 改写的上限不按原问题长度：五个字的追问展开成 150 字的独立问题是正常的
        QueryTranslator.validate("x".repeat(150), QueryTranslator.REWRITE_MAX_CHARS);
    }

    @Test
    void rerankFailureFallsBackToCosineOrderTopK() {
        var store = org.mockito.Mockito.mock(org.springframework.ai.vectorstore.VectorStore.class);
        var reranker = org.mockito.Mockito.mock(Reranker.class);
        var candidates = java.util.stream.IntStream.range(0, 20).mapToObj(i -> doc("d" + i, 0.9 - i * 0.01)).toList();
        org.mockito.Mockito.when(store.similaritySearch(org.mockito.ArgumentMatchers.any(org.springframework.ai.vectorstore.SearchRequest.class)))
                .thenReturn(candidates);
        org.mockito.Mockito.when(reranker.rerank(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.anyInt())).thenThrow(new RuntimeException("rerank down"));
        var props = new com.lee.psyqa.config.PsyQaProperties(
                new com.lee.psyqa.config.PsyQaProperties.Retrieval(5, 0.45, RetrievalService.QueryMode.ORIGINAL, 64,
                        new com.lee.psyqa.config.PsyQaProperties.Rerank(true, "m", "u", 20)), null, null, null);
        var service = new RetrievalService(store, org.mockito.Mockito.mock(QueryTranslator.class), reranker, props);

        var r = service.retrieve("What is libido?", List.of(), 5, RetrievalService.QueryMode.ORIGINAL, true);

        assertThat(r.hits()).extracting(Document::getId).containsExactly("d0", "d1", "d2", "d3", "d4");
        assertThat(r.rerank().failed()).isTrue();
    }
}
