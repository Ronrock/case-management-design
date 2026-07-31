package org.casemgmt.domain;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class CaseStateTest {

    @Test
    void activeCasesCanCloseCancelAndSuspend() {
        assertThat(CaseState.ACTIVE.canTransitionTo(CaseState.CLOSED)).isTrue();
        assertThat(CaseState.ACTIVE.canTransitionTo(CaseState.CANCELLED)).isTrue();
        assertThat(CaseState.ACTIVE.canTransitionTo(CaseState.SUSPENDED)).isTrue();
    }

    @Test
    void cancelledIsTerminal() {
        for (CaseState target : CaseState.values()) {
            assertThat(CaseState.CANCELLED.canTransitionTo(target)).isFalse();
        }
    }

    @Test
    void closedCasesReactivateToActive() {
        assertThat(CaseState.CLOSED.canTransitionTo(CaseState.ACTIVE)).isTrue();
        assertThat(CaseState.CLOSED.canTransitionTo(CaseState.SUSPENDED)).isFalse();
    }
}
