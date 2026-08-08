package org.casemgmt.domain;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CaseIdsTest {

    @Test
    void generatesGloballyUniqueIdsPrefixedWithTheEngineId() {
        String id = CaseIds.newCaseId("eng-a");

        assertThat(id).startsWith("eng-a:");
        String[] parts = id.split(":", 2);
        assertThat(parts).hasSize(2);
        assertThat(parts[0]).isEqualTo("eng-a");
        assertThat(UUID.fromString(parts[1])).isNotNull();
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

    @Test
    void rejectsNullEngineId() {
        assertThatThrownBy(() -> CaseIds.newCaseId(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be blank");
    }

    @Test
    void rejectsBlankEngineId() {
        assertThatThrownBy(() -> CaseIds.newCaseId("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be blank");
    }

    @Test
    void engineIdOfRejectsAnIdWithoutASeparator() {
        assertThatThrownBy(() -> CaseIds.engineIdOf("not-a-global-id"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Not a global case id");
    }
}
