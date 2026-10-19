package com.lee.psyqa.guard;

import com.lee.psyqa.config.PsyQaProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 危机识别两层的判定规则。不联网：用假的 embedding（文本含 CRISIS / THEORY / MIXED 决定方向）。 */
public class CrisisDetectorTest {

    /**
     * 测试用的 CrisisDetector：关键词用真实词表，例句换成 CRISIS_x / THEORY_x 标记，
     * 问题文本里带 CRISIS 就算"像危机"，带 THEORY 就算"像理论"，MIXED 落在两者之间（危机分略高、但低于阈值）。
     */
    public static CrisisDetector detector(EmbeddingModel em, double threshold) {
        when(em.embed(anyString())).thenAnswer(inv -> fakeVector(inv.getArgument(0)));
        when(em.embed(anyList())).thenAnswer(inv -> ((List<?>) inv.getArgument(0)).stream()
                .map(o -> fakeVector((String) o)).toList());
        var props = new PsyQaProperties(null, null, new PsyQaProperties.Crisis(
                new ClassPathResource("crisis/strong-keywords.txt"),
                new ClassPathResource("crisis/weak-keywords.txt"),
                text("CRISIS_1\nCRISIS_2\n"),
                text("THEORY_1\n"),
                threshold, "热线 12356"), null);
        return new CrisisDetector(em, props);
    }

    static float[] fakeVector(String t) {
        if (t.contains("CRISIS")) {
            return new float[]{1, 0};
        }
        if (t.contains("MIXED")) {
            return new float[]{0.55f, 0.45f};   // 危机分 ≈ 0.77 > 理论分 ≈ 0.63
        }
        if (t.contains("THEORY")) {
            return new float[]{0, 1};
        }
        return new float[]{0.3f, 1};             // 默认：更像理论
    }

    private static ByteArrayResource text(String s) {
        return new ByteArrayResource(s.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void strongKeywordDecidesWithoutEmbedding() {
        EmbeddingModel em = mock(EmbeddingModel.class);
        var d = detector(em, 0.6).detect("我最近总想自杀，荣格的理论能帮我吗？");
        assertThat(d.crisis()).isTrue();
        assertThat(d.layer()).isEqualTo(CrisisDetector.Layer.STRONG_KEYWORD);
        verify(em, never()).embed(anyString());
    }

    @Test
    void weakKeywordAloneIsNotEnough() {
        // 有"自杀"（弱关键词），但语义更像理论讨论 → 不判危机
        var d = detector(mock(EmbeddingModel.class), 0.6).detect("THEORY 弗洛伊德怎么解释忧郁症患者的自杀倾向？");
        assertThat(d.weakHit()).isTrue();
        assertThat(d.crisis()).isFalse();
    }

    @Test
    void weakKeywordOnlyNeedsToBeCloserToCrisis() {
        // 危机分低于阈值，但命中弱关键词且更像危机 → 判危机
        var d = detector(mock(EmbeddingModel.class), 0.9).detect("MIXED 自杀");
        assertThat(d.crisisScore()).isLessThan(0.9).isGreaterThan(d.theoryScore());
        assertThat(d.crisis()).isTrue();
        assertThat(d.layer()).isEqualTo(CrisisDetector.Layer.SEMANTIC);
    }

    @Test
    void withoutKeywordsNeedsThresholdAndCloserToCrisis() {
        EmbeddingModel em = mock(EmbeddingModel.class);
        assertThat(detector(em, 0.6).detect("CRISIS 好累").crisis()).isTrue();
        assertThat(detector(mock(EmbeddingModel.class), 0.9).detect("MIXED 好累").crisis()).isFalse();   // 更像危机但没过阈值
        assertThat(detector(mock(EmbeddingModel.class), 0.6).detect("THEORY 荣格的重生原型").crisis()).isFalse();
    }

    @Test
    void embeddingFailureFallsThroughAsNotCrisis() {
        EmbeddingModel em = mock(EmbeddingModel.class);
        CrisisDetector detector = detector(em, 0.6);
        when(em.embed(anyString())).thenThrow(new RuntimeException("Arrearage"));
        var d = detector.detect("CRISIS 好累");
        assertThat(d.crisis()).isFalse();
        assertThat(d.layer()).isEqualTo(CrisisDetector.Layer.SEMANTIC_ERROR);
    }

    @Test
    void decideIsTheSameRuleUsedByEvalSweep() {
        assertThat(CrisisDetector.decide(true, false, 0, 0, 0.6)).isTrue();
        assertThat(CrisisDetector.decide(false, true, 0.50, 0.49, 0.6)).isTrue();
        assertThat(CrisisDetector.decide(false, true, 0.50, 0.51, 0.6)).isFalse();
        assertThat(CrisisDetector.decide(false, false, 0.65, 0.50, 0.6)).isTrue();
        assertThat(CrisisDetector.decide(false, false, 0.55, 0.50, 0.6)).isFalse();
        assertThat(CrisisDetector.decide(false, false, 0.70, 0.75, 0.6)).isFalse();
    }
}
