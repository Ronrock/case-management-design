package org.casemgmt.domain;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CaseIdsTest {

    @Test
    void generatesGloballyUniqueIdsPrefixedWithTheEngineId() {
        String id = CaseIds.newCaseId("eng-a");
        assertThat(id).startsWith("eng-a:").hasSizeGreaterThan(10);
        assertThat(CaseIds.engineIdOf(id)).isEqualTo("eng-a");
    }

    @Test
    void twoIdsFromTheSameEngineDiffer() {
        assertThat(CaseIds.newCaseId("eng-a")).isNotEqualTo(CaseIds.newCaseId("eng-a"));
    }

    @Test
    void rejectsEngineIdsContainingTheSeparator() {
        assertThatThrownBy(() -> CaseIds.newCaseId("eng:a"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not contain ':'");
    }
}
