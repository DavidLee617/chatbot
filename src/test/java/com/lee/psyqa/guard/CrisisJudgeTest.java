package com.lee.psyqa.guard;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CrisisJudgeTest {

    @Test
    void parsesOnlyRiskOrSafe() {
        assertThat(CrisisJudge.parse("RISK")).isTrue();
        assertThat(CrisisJudge.parse(" safe.\n")).isFalse();
        assertThatThrownBy(() -> CrisisJudge.parse("我觉得用户可能有风险")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> CrisisJudge.parse(null)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void marksLatestMessage() {
        assertThat(CrisisJudge.format(List.of("弗洛伊德怎么解释自杀？", "我也有过这种念头")))
                .isEqualTo("Earlier: 弗洛伊德怎么解释自杀？\nLATEST: 我也有过这种念头\n");
    }
}
