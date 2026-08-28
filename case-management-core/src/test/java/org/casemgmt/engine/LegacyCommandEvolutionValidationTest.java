package org.casemgmt.engine;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class LegacyCommandEvolutionValidationTest {

    private static final OffsetDateTime BASELINE = OffsetDateTime.parse("2026-01-01T00:00:00Z");

    @Test
    void markerAndVersionCannotRepackageAnUnchangedPendingBaseline() {
        assertThat(reachable(EngineCommandStatus.PENDING, EngineCommandStatus.PENDING,
                0, 0, 0, false, false, 1, BASELINE.plusSeconds(1))).isFalse();
    }

    @Test
    void sameRetryableStatusRequiresARealAttemptOrNormalizedOperatorAction() {
        assertThat(reachable(EngineCommandStatus.RETRYABLE, EngineCommandStatus.RETRYABLE,
                1, 1, 0, false, false, 1, BASELINE.plusSeconds(1))).isFalse();
        assertThat(reachable(EngineCommandStatus.RETRYABLE, EngineCommandStatus.RETRYABLE,
                1, 2, 0, false, false, 2, BASELINE.plusSeconds(1))).isTrue();
        assertThat(reachable(EngineCommandStatus.RETRYABLE, EngineCommandStatus.RETRYABLE,
                1, 1, 1, false, false, 2, BASELINE.plusSeconds(1))).isTrue();
    }

    @Test
    void markerClearRequiresLaterDecisionTimeAndDurablePolicyEvidence() {
        assertThat(reachable(EngineCommandStatus.AWAITING_CONFIRMATION,
                EngineCommandStatus.CONFIRMED, 3, 3, 0, true, false, 1, BASELINE)).isFalse();
        assertThat(reachable(EngineCommandStatus.AWAITING_CONFIRMATION,
                EngineCommandStatus.CONFIRMED, 3, 3, 0, true, false, 1,
                BASELINE.plusSeconds(1))).isTrue();
        assertThat(reachable(EngineCommandStatus.AWAITING_CONFIRMATION,
                EngineCommandStatus.CONFLICT, 3, 3, 0, false, false, 1,
                BASELINE.plusSeconds(1))).isFalse();
    }

    @Test
    void statusMatrixRequiresTheEvidenceUsedByAReachablePolicyPath() {
        assertThat(reachable(EngineCommandStatus.FAILED, EngineCommandStatus.DISPATCHING,
                4, 5, 0, false, false, 2, BASELINE.plusSeconds(1))).isFalse();
        assertThat(reachable(EngineCommandStatus.FAILED, EngineCommandStatus.DISPATCHING,
                4, 5, 1, false, false, 3, BASELINE.plusSeconds(1))).isTrue();
        assertThat(reachable(EngineCommandStatus.AWAITING_CONFIRMATION,
                EngineCommandStatus.RETRYABLE, 3, 4, 0, false, false, 2,
                BASELINE.plusSeconds(1))).isFalse();
        assertThat(reachable(EngineCommandStatus.PENDING, EngineCommandStatus.CONFIRMED,
                0, 0, 0, true, false, 1, BASELINE.plusSeconds(1))).isTrue();
        assertThat(reachable(EngineCommandStatus.PENDING, EngineCommandStatus.CANCELLED,
                0, 0, 0, false, false, 1, BASELINE.plusSeconds(1))).isFalse();
    }

    @Test
    void retryAndDispatchTimesMustBeStrictlyAfterDecision() {
        assertThat(ProductionEngineCommandStore.validTemporalDecision(
                EngineCommandStatus.RETRYABLE, BASELINE, BASELINE, null)).isFalse();
        assertThat(ProductionEngineCommandStore.validTemporalDecision(
                EngineCommandStatus.RETRYABLE, BASELINE, BASELINE.minusSeconds(1), null)).isFalse();
        assertThat(ProductionEngineCommandStore.validTemporalDecision(
                EngineCommandStatus.RETRYABLE, BASELINE, BASELINE.plusSeconds(1), null)).isTrue();
        assertThat(ProductionEngineCommandStore.validTemporalDecision(
                EngineCommandStatus.DISPATCHING, BASELINE, null, BASELINE)).isFalse();
        assertThat(ProductionEngineCommandStore.validTemporalDecision(
                EngineCommandStatus.DISPATCHING, BASELINE, null, BASELINE.plusSeconds(1))).isTrue();
    }

    private static boolean reachable(
            EngineCommandStatus baseline, EngineCommandStatus current,
            long baselineAttempts, long currentAttempts, long actions,
            boolean confirmation, boolean review, long version, OffsetDateTime decidedAt) {
        return ProductionEngineCommandStore.isPolicyReachableLegacyEvolution(
                baseline, current, baselineAttempts, currentAttempts, actions,
                confirmation, review, version, BASELINE, decidedAt);
    }
}
