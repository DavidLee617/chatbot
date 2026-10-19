package com.lee.psyqa.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lee.psyqa.conversation.Turn;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.ClassPathResource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class MemoryExtractorTest {

    private final MemoryExtractor extractor;

    MemoryExtractorTest() throws Exception {
        extractor = new MemoryExtractor(mock(ChatClient.Builder.class), new ObjectMapper(),
                new ClassPathResource("prompts/memory-extract.st"), new ClassPathResource("prompts/memory-reconcile.st"));
    }

    @Test
    void parsesFencedJsonAndDropsInvalidEntries() {
        var ex = extractor.parseExtraction("""
                ```json
                {"facts": [{"kind": "preference", "content": "用户希望回答简短"},
                           {"kind": "MOOD", "content": "用户最近压力大"},
                           {"kind": "BACKGROUND", "content": ""}],
                 "topics": ["荣格的阴影", "弗洛伊德的口误", "第三个"]}
                ```""", 10);
        assertThat(ex.facts()).containsExactly(new MemoryExtractor.Fact(Memory.Kind.PREFERENCE, "用户希望回答简短"));
        assertThat(ex.topics()).containsExactly("荣格的阴影", "弗洛伊德的口误");
    }

    @Test
    void rejectsNonJson() {
        assertThatThrownBy(() -> extractor.parseExtraction("没有可以记的内容", 0)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void reconcileTargetMustPointAtACandidate() {
        assertThat(extractor.parseAction("{\"action\": \"ADD\", \"target\": 0}", 2, 0).type())
                .isEqualTo(MemoryExtractor.ActionType.ADD);
        assertThatThrownBy(() -> extractor.parseAction("{\"action\": \"DELETE\", \"target\": 3}", 2, 0))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> extractor.parseAction("{\"action\": \"UPDATE\", \"target\": 1}", 2, 0))
                .isInstanceOf(IllegalStateException.class);   // UPDATE 必须给 content
    }

    @Test
    void formatsOnlyRecentTurnsAndMarksCurrent() {
        String s = MemoryExtractor.formatTurns(
                List.of(new Turn("q1", "a1"), new Turn("q2", "a2"), new Turn("q3", "a3")), new Turn("q4", "a4"));
        assertThat(s).doesNotContain("q1").contains("User: q2").contains("CURRENT turn:\nUser: q4");
    }

    @Test
    void vectorLiteral() {
        assertThat(MemoryRepository.literal(new float[]{0.5f, -1f})).isEqualTo("{0.5,-1.0}");
    }
}
