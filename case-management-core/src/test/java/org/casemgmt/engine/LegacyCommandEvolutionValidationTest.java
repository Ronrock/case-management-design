package org.casemgmt.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.OffsetDateTime;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class LegacyCommandEvolutionValidationTest {

    private static final OffsetDateTime BASELINE = OffsetDateTime.parse("2026-01-01T00:00:00Z");

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

    @Test
    void nullableRawLegacyPayloadRetainsNullWhileCurrentPayloadUsesAnEmptyObject() {
        assertThat(ProductionEngineCommandStore.matchesRetainedLegacyPayload(null, "{}"))
                .isTrue();
        assertThat(ProductionEngineCommandStore.matchesRetainedLegacyPayload(
                "{\"old\":true}", "{\"old\":true}"))
                .isTrue();
        assertThat(ProductionEngineCommandStore.matchesRetainedLegacyPayload(null, null))
                .isFalse();
        assertThat(ProductionEngineCommandStore.matchesRetainedLegacyPayload(
                "{\"old\":true}", "{}"))
                .isFalse();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("exactEvolutionMatrix")
    void exactEvolutionMatrixRejectsEveryImpossibleLegacyStatusAndProvenance(
            String scenario, ProductionEngineCommandStore.LegacyEvolutionFacts facts,
            boolean expected) {
        assertThat(ProductionEngineCommandStore.isPolicyReachableLegacyEvolution(facts))
                .as(scenario).isEqualTo(expected);
    }

    private static Stream<Arguments> exactEvolutionMatrix() {
        Stream<Arguments> immutable = Stream.of(
                EngineCommandStatus.CONFIRMED, EngineCommandStatus.FAILED).flatMap(baseline ->
                Stream.of(EngineCommandStatus.values()).map(current -> Arguments.of(
                        baseline + " cannot evolve to " + current,
                        facts(baseline, current, 4, 5, 1, 1, 0,
                                CommandDispatchOutcome.ActionType.RETRY_OVERRIDE,
                                CommandDispatchOutcome.ReviewFinding.DEFINITIVE_ABSENCE,
                                CommandDispatchOutcome.ReviewSource.OPERATOR_REVIEW,
                                current == EngineCommandStatus.CONFIRMED, 4), false)));
        Stream<Arguments> live = Stream.of(
                Arguments.of("pending claim", facts(EngineCommandStatus.PENDING,
                        EngineCommandStatus.DISPATCHING, 0, 1, 0, 0, 0,
                        null, null, null, false, 1), true),
                Arguments.of("pending cannot clear without transition", facts(
                        EngineCommandStatus.PENDING, EngineCommandStatus.PENDING,
                        0, 0, 0, 0, 0, null, null, null, false, 1), false),
                Arguments.of("retrying direct reviewed retry", facts(
                        EngineCommandStatus.RETRYABLE, EngineCommandStatus.RETRYABLE,
                        2, 2, 1, 1, 0, CommandDispatchOutcome.ActionType.RETRY_OVERRIDE,
                        CommandDispatchOutcome.ReviewFinding.DEFINITIVE_ABSENCE,
                        CommandDispatchOutcome.ReviewSource.OPERATOR_REVIEW, false, 1), true),
                Arguments.of("claimed capped reconciliation failure", facts(
                        EngineCommandStatus.AWAITING_CONFIRMATION, EngineCommandStatus.FAILED,
                        6, 6, 0, 0, 0, null,
                        CommandDispatchOutcome.ReviewFinding.DEFINITIVE_ABSENCE,
                        CommandDispatchOutcome.ReviewSource.RECONCILIATION, false, 1), true),
                Arguments.of("claimed non-cap reconciliation cannot fail", facts(
                        EngineCommandStatus.AWAITING_CONFIRMATION, EngineCommandStatus.FAILED,
                        5, 5, 0, 0, 0, null,
                        CommandDispatchOutcome.ReviewFinding.DEFINITIVE_ABSENCE,
                        CommandDispatchOutcome.ReviewSource.RECONCILIATION, false, 1), false),
                Arguments.of("claimed retry through reconciliation then dispatch", facts(
                        EngineCommandStatus.AWAITING_CONFIRMATION, EngineCommandStatus.RETRYABLE,
                        3, 4, 0, 0, 0, null, null, null, false, 3), true),
                Arguments.of("claimed cannot be dispatching without exit transition", facts(
                        EngineCommandStatus.AWAITING_CONFIRMATION, EngineCommandStatus.DISPATCHING,
                        3, 4, 0, 0, 0, null, null, null, false, 1), false),
                Arguments.of("claimed cannot conflict after an unproven redispatch", facts(
                        EngineCommandStatus.AWAITING_CONFIRMATION, EngineCommandStatus.CONFLICT,
                        3, 4, 0, 0, 0, null, null, null, false, 2), false),
                Arguments.of("claimed reconciliation can precede a conflicting redispatch", facts(
                        EngineCommandStatus.AWAITING_CONFIRMATION, EngineCommandStatus.CONFLICT,
                        3, 4, 0, 0, 0, null, null, null, false, 3), true),
                Arguments.of("claimed reviewed retry can conflict after redispatch", facts(
                        EngineCommandStatus.AWAITING_CONFIRMATION, EngineCommandStatus.CONFLICT,
                        3, 4, 1, 1, 0, null, null, null, false, 3), true),
                Arguments.of("claimed reviewed retry then dispatch", facts(
                        EngineCommandStatus.AWAITING_CONFIRMATION, EngineCommandStatus.DISPATCHING,
                        3, 4, 1, 1, 0, null, null, null, false, 2), true),
                Arguments.of("consumed reconcile action requires its later decision version", facts(
                        EngineCommandStatus.AWAITING_CONFIRMATION,
                        EngineCommandStatus.MANUAL_REVIEW, 3, 3, 1, 0, 0, null,
                        CommandDispatchOutcome.ReviewFinding.INCONCLUSIVE,
                        CommandDispatchOutcome.ReviewSource.RECONCILIATION, false, 1), false),
                Arguments.of("reconcile action followed by inconclusive result", facts(
                        EngineCommandStatus.AWAITING_CONFIRMATION,
                        EngineCommandStatus.MANUAL_REVIEW, 3, 3, 1, 0, 0, null,
                        CommandDispatchOutcome.ReviewFinding.INCONCLUSIVE,
                        CommandDispatchOutcome.ReviewSource.RECONCILIATION, false, 2), true),
                Arguments.of("reviewed retry can complete another dispatch cycle", facts(
                        EngineCommandStatus.AWAITING_CONFIRMATION, EngineCommandStatus.RETRYABLE,
                        3, 4, 1, 1, 0, null, null, null, false, 3), true),
                Arguments.of("pending direct cancel", facts(EngineCommandStatus.PENDING,
                        EngineCommandStatus.CANCELLED, 0, 0, 1, 0, 1,
                        CommandDispatchOutcome.ActionType.CANCEL, null, null, false, 1), true),
                Arguments.of("claimed cancel requires reviewed absence", facts(
                        EngineCommandStatus.AWAITING_CONFIRMATION, EngineCommandStatus.CANCELLED,
                        3, 3, 1, 0, 1, CommandDispatchOutcome.ActionType.CANCEL,
                        null, null, false, 1), false),
                Arguments.of("claimed reviewed cancel", facts(
                        EngineCommandStatus.AWAITING_CONFIRMATION, EngineCommandStatus.CANCELLED,
                        3, 3, 1, 0, 1, CommandDispatchOutcome.ActionType.CANCEL,
                        CommandDispatchOutcome.ReviewFinding.DEFINITIVE_ABSENCE,
                        CommandDispatchOutcome.ReviewSource.OPERATOR_REVIEW, false, 1), true),
                Arguments.of("confirmation is reachable from claimed", facts(
                        EngineCommandStatus.AWAITING_CONFIRMATION, EngineCommandStatus.CONFIRMED,
                        3, 3, 0, 0, 0, null, null, null, true, 1), true),
                Arguments.of("confirmation status requires evidence", facts(
                        EngineCommandStatus.RETRYABLE, EngineCommandStatus.CONFIRMED,
                        2, 2, 0, 0, 0, null, null, null, false, 1), false));
        Stream<Arguments> everyLiveBaselineAndCurrentStatus = Stream.of(
                EngineCommandStatus.PENDING, EngineCommandStatus.RETRYABLE,
                EngineCommandStatus.AWAITING_CONFIRMATION).flatMap(baseline ->
                Stream.of(EngineCommandStatus.values()).map(current -> Arguments.of(
                        baseline + " matrix to " + current,
                        reachableMatrixFact(baseline, current),
                        current != EngineCommandStatus.PENDING)));
        return Stream.concat(Stream.concat(immutable, everyLiveBaselineAndCurrentStatus), live);
    }

    private static ProductionEngineCommandStore.LegacyEvolutionFacts reachableMatrixFact(
            EngineCommandStatus baseline, EngineCommandStatus current) {
        long baselineAttempts = baseline == EngineCommandStatus.PENDING ? 0
                : baseline == EngineCommandStatus.RETRYABLE ? 2 : 3;
        long currentAttempts = baselineAttempts;
        long actions = 0;
        long retries = 0;
        long cancellations = 0;
        long version = 1;
        CommandDispatchOutcome.ActionType action = null;
        CommandDispatchOutcome.ReviewFinding finding = null;
        CommandDispatchOutcome.ReviewSource source = null;
        boolean confirmation = false;
        switch (current) {
            case PENDING -> { }
            case DISPATCHING -> {
                currentAttempts++;
                if (baseline == EngineCommandStatus.AWAITING_CONFIRMATION) {
                    actions = retries = 1;
                    version = 2;
                }
            }
            case RETRYABLE -> {
                if (baseline == EngineCommandStatus.PENDING) {
                    currentAttempts++;
                    version = 2;
                } else if (baseline == EngineCommandStatus.RETRYABLE) {
                    actions = retries = 1;
                    action = CommandDispatchOutcome.ActionType.RETRY_OVERRIDE;
                    finding = CommandDispatchOutcome.ReviewFinding.DEFINITIVE_ABSENCE;
                    source = CommandDispatchOutcome.ReviewSource.OPERATOR_REVIEW;
                } else {
                    finding = CommandDispatchOutcome.ReviewFinding.DEFINITIVE_ABSENCE;
                    source = CommandDispatchOutcome.ReviewSource.RECONCILIATION;
                }
            }
            case AWAITING_CONFIRMATION -> {
                if (baseline == EngineCommandStatus.AWAITING_CONFIRMATION) {
                    actions = 1;
                    action = CommandDispatchOutcome.ActionType.RECONCILE;
                } else {
                    currentAttempts++;
                    version = 2;
                }
            }
            case CONFIRMED -> confirmation = true;
            case FAILED -> {
                if (baseline == EngineCommandStatus.AWAITING_CONFIRMATION) {
                    baselineAttempts = currentAttempts = EngineCommandPolicy.MAX_AUTOMATIC_ATTEMPTS;
                    finding = CommandDispatchOutcome.ReviewFinding.DEFINITIVE_ABSENCE;
                    source = CommandDispatchOutcome.ReviewSource.RECONCILIATION;
                } else {
                    currentAttempts++;
                    version = 2;
                }
            }
            case CONFLICT -> {
                currentAttempts++;
                version = baseline == EngineCommandStatus.AWAITING_CONFIRMATION ? 3 : 2;
                if (baseline == EngineCommandStatus.AWAITING_CONFIRMATION) {
                    actions = retries = 1;
                }
            }
            case MANUAL_REVIEW -> {
                finding = CommandDispatchOutcome.ReviewFinding.INCONCLUSIVE;
                source = CommandDispatchOutcome.ReviewSource.RECONCILIATION;
                if (baseline != EngineCommandStatus.AWAITING_CONFIRMATION) {
                    currentAttempts++;
                    version = 3;
                }
            }
            case CANCELLED -> {
                actions = cancellations = 1;
                action = CommandDispatchOutcome.ActionType.CANCEL;
                if (baseline == EngineCommandStatus.AWAITING_CONFIRMATION) {
                    finding = CommandDispatchOutcome.ReviewFinding.DEFINITIVE_ABSENCE;
                    source = CommandDispatchOutcome.ReviewSource.OPERATOR_REVIEW;
                }
            }
        }
        return facts(baseline, current, baselineAttempts, currentAttempts, actions, retries,
                cancellations, action, finding, source, confirmation, version);
    }

    private static ProductionEngineCommandStore.LegacyEvolutionFacts facts(
            EngineCommandStatus baseline, EngineCommandStatus current,
            long baselineAttempts, long currentAttempts, long actions, long retryActions,
            long cancelActions, CommandDispatchOutcome.ActionType appliedAction,
            CommandDispatchOutcome.ReviewFinding reviewFinding,
            CommandDispatchOutcome.ReviewSource reviewSource,
            boolean confirmation, long version) {
        return new ProductionEngineCommandStore.LegacyEvolutionFacts(
                baseline, current, baselineAttempts, currentAttempts, actions, retryActions,
                cancelActions, appliedAction, reviewFinding, reviewSource, confirmation,
                version, BASELINE, BASELINE.plusSeconds(1));
    }

}
