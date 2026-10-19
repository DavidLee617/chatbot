package com.lee.psyqa.memory;

import com.lee.psyqa.conversation.Turn;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 写入（topic 去重、fact 对账）和召回的规则。不联网、不连数据库：
 * 假向量按文本里的标记决定方向（含 A 的指向 x 轴，含 B 的指向 y 轴），两个同标记的文本相似度为 1，不同标记为 0。
 */
class MemoryServiceTest {

    private MemoryRepository repo;
    private MemoryExtractor extractor;
    private EmbeddingModel em;
    private MemoryService service;
    private final Turn turn = new Turn("q", "a");

    @BeforeEach
    void setUp() {
        repo = mock(MemoryRepository.class);
        extractor = mock(MemoryExtractor.class);
        em = mock(EmbeddingModel.class);
        when(em.embed(anyString())).thenAnswer(inv -> vec(inv.getArgument(0)));
        when(em.embed(anyList())).thenAnswer(inv -> ((List<?>) inv.getArgument(0)).stream().map(o -> vec((String) o)).toList());
        service = new MemoryService(repo, extractor, em);
    }

    static float[] vec(String t) {
        if (t.contains("A")) {
            return new float[]{1, 0};
        }
        if (t.contains("B")) {
            return new float[]{0, 1};
        }
        return new float[]{0.7f, 0.7f};
    }

    static Memory mem(Memory.Kind kind, String content) {
        return new Memory(UUID.randomUUID(), "u", kind, content, vec(content), Instant.now(), Instant.now());
    }

    @Test
    void emptyExtractionWritesNothing() {
        when(extractor.extract(anyList(), any())).thenReturn(new MemoryExtractor.Extraction(List.of(), List.of(), 50));
        var r = service.write("u", List.of(), turn);
        assertThat(r.added()).isZero();
        verify(repo, never()).insert(any());
        verify(em, never()).embed(anyList());
    }

    @Test
    void similarTopicIsTouchedNotDuplicated() {
        Memory old = mem(Memory.Kind.TOPIC, "荣格的阴影 A");
        when(repo.findByUser("u")).thenReturn(List.of(old));
        when(extractor.extract(anyList(), any())).thenReturn(
                new MemoryExtractor.Extraction(List.of(), List.of("荣格说的阴影 A", "弗洛伊德的口误 B"), 50));
        var r = service.write("u", List.of(), turn);
        verify(repo).touch(old.id());
        verify(repo).insert(argThat(m -> m.content().equals("弗洛伊德的口误 B")));
        assertThat(r.added()).isEqualTo(1);
        assertThat(r.unchanged()).isEqualTo(1);
        verify(extractor, never()).reconcile(anyList(), any());   // topic 不调模型对账
    }

    @Test
    void newFactWithoutSimilarMemoryIsAddedWithoutReconcile() {
        when(repo.findByUser("u")).thenReturn(List.of(mem(Memory.Kind.PREFERENCE, "用户希望回答简短 A")));
        when(extractor.extract(anyList(), any())).thenReturn(new MemoryExtractor.Extraction(
                List.of(new MemoryExtractor.Fact(Memory.Kind.BACKGROUND, "用户是心理学专业学生 B")), List.of(), 50));
        service.write("u", List.of(), turn);
        verify(repo).insert(argThat(m -> m.kind() == Memory.Kind.BACKGROUND));
        verify(extractor, never()).reconcile(anyList(), any());
    }

    @Test
    void conflictingFactUpdatesTheOldOne() {
        Memory old = mem(Memory.Kind.PREFERENCE, "用户喜欢详细的回答 A");
        when(repo.findByUser("u")).thenReturn(List.of(old));
        var fact = new MemoryExtractor.Fact(Memory.Kind.PREFERENCE, "用户希望回答简短 A");
        when(extractor.extract(anyList(), any())).thenReturn(new MemoryExtractor.Extraction(List.of(fact), List.of(), 50));
        when(extractor.reconcile(eq(List.of(old)), eq(fact)))
                .thenReturn(new MemoryExtractor.Action(MemoryExtractor.ActionType.UPDATE, 1, "用户希望回答简短 A", 30));
        var r = service.write("u", List.of(), turn);
        verify(repo).update(eq(old.id()), eq("用户希望回答简短 A"), any());
        verify(repo, never()).insert(any());
        assertThat(r.updated()).isEqualTo(1);
        assertThat(r.reconcileCalls()).isEqualTo(1);
        assertThat(r.tokens()).isEqualTo(80);
    }

    @Test
    void recallReturnsAllFactsAndOnlyRelatedTopics() {
        when(repo.findByUser("u")).thenReturn(List.of(
                mem(Memory.Kind.PREFERENCE, "用户希望回答简短 B"),
                mem(Memory.Kind.TOPIC, "荣格的阴影 A"),
                mem(Memory.Kind.TOPIC, "弗洛伊德的口误 B")));
        var r = service.recall("u", "阴影和人格面具 A");
        assertThat(r.facts()).extracting(Memory::content).containsExactly("用户希望回答简短 B");
        assertThat(r.topics()).extracting(Memory::content).containsExactly("荣格的阴影 A");
        assertThat(r.render()).isEqualTo("- 用户希望回答简短 B\n- 以前聊过：荣格的阴影 A");
    }

    @Test
    void recallWithoutMemoriesSkipsEmbedding() {
        when(repo.findByUser("u")).thenReturn(List.of());
        assertThat(service.recall("u", "q").render()).isNull();
        verify(em, never()).embed(anyString());
        assertThat(service.recall(null, "q")).isSameAs(MemoryService.Recall.EMPTY);
    }
}
