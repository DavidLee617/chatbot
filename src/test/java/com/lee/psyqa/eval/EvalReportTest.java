package com.lee.psyqa.eval;

import com.lee.psyqa.eval.EvalReport.QuestionResult;
import com.lee.psyqa.eval.EvalReport.RunInfo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** 评估的命中判断和指标计算。不依赖外部服务。 */
class EvalReportTest {

    @Test
    void evidenceMatchesWholeWordsOnly() {
        assertThat(EvidenceMatcher.containsAny("The moth and sun never meet.", List.of("moth and sun"))).isTrue();
        assertThat(EvidenceMatcher.containsAny("the mother of the hero", List.of("moth"))).isFalse();
        assertThat(EvidenceMatcher.containsAny("his personal unconscious", List.of("persona"))).isFalse();
        assertThat(EvidenceMatcher.containsAny("the \"persona\" is a mask", List.of("persona"))).isTrue();
        // 后面跟连字符、标点不影响
        assertThat(EvidenceMatcher.containsAny("the Electra-complex.", List.of("electra"))).isTrue();
    }

    @Test
    void evidenceNormalizesQuotesAndWhitespace() {
        assertThat(EvidenceMatcher.containsAny("children’s\n\ndreams are short", List.of("children's dreams"))).isTrue();
    }

    @Test
    void computesRecallMrrAndRouteAccuracy() {
        List<QuestionResult> rs = List.of(
                answerable("a1", 1, true, 0.70),
                answerable("a2", 3, true, 0.60),
                answerable("a3", 0, false, 0.40),
                answerable("a4", 5, false, 0.50),
                new QuestionResult("n1", "out_of_scope", "easy", "", "q", "REFUSED", "REFUSED", 0, false, List.of(0.20), List.of("x"), null, null),
                new QuestionResult("n2", "out_of_scope", "hard", "", "q", "REFUSED", "ANSWERED", 0, false, List.of(0.55), List.of("x"), null, null),
                new QuestionResult("c1", "crisis", "easy", "", "q", "CRISIS", "CRISIS", 0, false, List.of(), List.of(), null, null));
        EvalReport report = new EvalReport(new RunInfo("t", "zh", 5, 0.45, "m", 2000, 200, "f", 0.60, "ORIGINAL", 64), rs);

        assertThat(report.recallAt(1)).isEqualTo(0.25);
        assertThat(report.recallAt(3)).isEqualTo(0.5);
        assertThat(report.recallAt(5)).isEqualTo(0.75);
        assertThat(report.mrr()).isCloseTo((1 + 1.0 / 3 + 0 + 1.0 / 5) / 4, within(1e-9));
        assertThat(report.effectiveRecall()).isEqualTo(0.5);

        String md = report.markdown();
        assertThat(md).contains("| recall@5 | 75.0% |");
        assertThat(md).contains("**a3**");                       // 没命中的题列出来
        assertThat(md).contains("| 0.45（当前） | 1 / 4 | 1 / 2 |"); // a3 的 0.40 被误拒；n2 的 0.55 被放过
    }

    private static QuestionResult answerable(String id, int rank, boolean kept, double top1) {
        return new QuestionResult(id, "answerable", "medium", "Book", "q", "ANSWERED",
                top1 >= 0.45 ? "ANSWERED" : "REFUSED", rank, kept, List.of(top1), List.of("Book · CH"), null, null);
    }
}
