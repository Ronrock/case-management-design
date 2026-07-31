package org.casemgmt.domain;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.assertj.core.api.SoftAssertions;
import static org.assertj.core.api.Assertions.assertThat;

class CaseStateTest {

    /** The full transition table, mirrored here as the independent source of truth for the exhaustive test below. */
    private static final Map<CaseState, Set<CaseState>> EXPECTED = new EnumMap<>(Map.of(
            CaseState.CREATED,   EnumSet.of(CaseState.ACTIVE, CaseState.CANCELLED),
            CaseState.ACTIVE,    EnumSet.of(CaseState.SUSPENDED, CaseState.CLOSED, CaseState.CANCELLED),
            CaseState.SUSPENDED, EnumSet.of(CaseState.ACTIVE, CaseState.CANCELLED),
            CaseState.CLOSED,    EnumSet.of(CaseState.ACTIVE),
            CaseState.CANCELLED, EnumSet.noneOf(CaseState.class)));

    @Test
    void everyStatePairMatchesTheExpectedTransitionTable() {
        SoftAssertions softly = new SoftAssertions();
        for (CaseState from : CaseState.values()) {
            for (CaseState target : CaseState.values()) {
                boolean expected = EXPECTED.get(from).contains(target);
                softly.assertThat(from.canTransitionTo(target))
                        .as("%s.canTransitionTo(%s)", from, target)
                        .isEqualTo(expected);
            }
        }
        softly.assertAll();
    }

    @Test
    void activeCasesCanCloseCancelAndSuspend() {
        assertThat(CaseState.ACTIVE.canTransitionTo(CaseState.CLOSED)).isTrue();
        assertThat(CaseState.ACTIVE.canTransitionTo(CaseState.CANCELLED)).isTrue();
        assertThat(CaseState.ACTIVE.canTransitionTo(CaseState.SUSPENDED)).isTrue();
    }

    @Test
    void activeCannotReturnToCreated() {
        assertThat(CaseState.ACTIVE.canTransitionTo(CaseState.CREATED)).isFalse();
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
