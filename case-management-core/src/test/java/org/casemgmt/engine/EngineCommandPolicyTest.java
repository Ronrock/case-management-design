package org.casemgmt.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.casemgmt.engine.CommandDispatchOutcome.Acceptance.ACCEPTED;
import static org.casemgmt.engine.CommandDispatchOutcome.Acceptance.POSSIBLY_ACCEPTED;
import static org.casemgmt.engine.CommandDispatchOutcome.Acceptance.PROVEN_NOT_ACCEPTED;
import static org.casemgmt.engine.CommandDispatchOutcome.ConfirmationSource.DUPLICATE_RESPONSE;
import static org.casemgmt.engine.CommandDispatchOutcome.ConfirmationSource.HTTP_RESPONSE;
import static org.casemgmt.engine.CommandDispatchOutcome.ConfirmationSource.OBSERVATION;
import static org.casemgmt.engine.CommandDispatchOutcome.ConfirmationSource.RECONCILIATION;
import static org.casemgmt.engine.CommandDispatchOutcome.ReviewFinding.DEFINITIVE_ABSENCE;
import static org.casemgmt.engine.CommandDispatchOutcome.ReviewFinding.INCONCLUSIVE;
import static org.casemgmt.engine.CommandDispatchOutcome.ReviewSource.OPERATOR_REVIEW;
import static org.casemgmt.engine.CommandDispatchOutcome.TransportFailure.MID_WRITE_FAILURE;
import static org.casemgmt.engine.CommandDispatchOutcome.TransportFailure.PRE_CONNECT_FAILURE;
import static org.casemgmt.engine.CommandDispatchOutcome.TransportFailure.PRE_SEND_ZERO_BYTES;
import static org.casemgmt.engine.CommandDispatchOutcome.TransportFailure.READ_FAILURE;
import static org.casemgmt.engine.CommandDispatchOutcome.TransportFailure.TIMEOUT;
import static org.casemgmt.engine.CommandDispatchOutcome.TransportFailure.UNKNOWN;

class EngineCommandPolicyTest {

    private static final Instant NOW = Instant.parse("2026-08-28T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final EngineCommandPolicy POLICY = new EngineCommandPolicy(CLOCK);
    private static final OffsetDateTime FIRST_RETRY = OffsetDateTime.parse(
            "2026-08-28T12:00:53.424Z");

    @ParameterizedTest(name = "{0} {1} after {2} -> {3}")
    @MethodSource("independentDecisionTable")
    void exactTransitionTableIsIndependentOfPolicyImplementation(
            EngineCommand.Type type, EngineCommandStatus current,
            Scenario scenario, Expected expected) {
        EngineCommandPolicy.CommandContext command = command(type);

        if (expected == null) {
            assertThatThrownBy(() -> POLICY.transition(
                    command, current, scenario.totalDispatchAttempts(), scenario.outcome()))
                    .isInstanceOf(IllegalStateException.class);
            return;
        }

        EngineCommandPolicy.Decision decision = POLICY.transition(
                command, current, scenario.totalDispatchAttempts(), scenario.outcome());
        assertThat(decision.status()).isEqualTo(expected.status());
        assertThat(decision.errorCode()).isEqualTo(expected.errorCode());
        assertThat(decision.nextAttemptAt()).isEqualTo(expected.nextAttemptAt());
    }

    static Stream<Arguments> independentDecisionTable() {
        return Stream.of(EngineCommand.Type.values()).flatMap(type ->
                Stream.of(scenarios(type)).flatMap(scenario ->
                        Stream.of(EngineCommandStatus.values()).map(status -> Arguments.of(
                                type, status, scenario,
                                scenario.expectedByStatus().get(status)))));
    }

    private static Scenario[] scenarios(EngineCommand.Type type) {
        Expected dispatching = expected(EngineCommandStatus.DISPATCHING, null, null);
        Expected retry = expected(EngineCommandStatus.RETRYABLE,
                "transport.not_sent", FIRST_RETRY);
        Expected awaitingTransport = expected(EngineCommandStatus.AWAITING_CONFIRMATION,
                "transport.possibly_sent", null);
        Expected awaitingHttp = expected(EngineCommandStatus.AWAITING_CONFIRMATION,
                "response.unconfirmed", null);
        Expected malformed = expected(EngineCommandStatus.AWAITING_CONFIRMATION,
                "response.malformed", null);
        Expected duplicate = expected(EngineCommandStatus.CONFLICT,
                "response.duplicate", null);
        Expected expiredLease = expected(EngineCommandStatus.AWAITING_CONFIRMATION,
                "dispatch.lease_expired", null);
        Expected confirmed = expected(EngineCommandStatus.CONFIRMED, null, null);
        Expected inconclusive = expected(EngineCommandStatus.MANUAL_REVIEW,
                "reconcile.inconclusive", null);
        Expected manual = expected(EngineCommandStatus.MANUAL_REVIEW,
                "review.requested", null);
        Expected awaitingReview = expected(EngineCommandStatus.AWAITING_CONFIRMATION,
                "reconcile.requested", null);
        Expected reviewedRetry = expected(EngineCommandStatus.RETRYABLE,
                "review.retry", OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
        Expected cancelled = expected(EngineCommandStatus.CANCELLED, null, null);
        return new Scenario[] {
                new Scenario("dispatch", 0, CommandDispatchOutcome.dispatchRequested(), Map.of(
                        EngineCommandStatus.PENDING, dispatching,
                        EngineCommandStatus.RETRYABLE, dispatching)),
                new Scenario("zero-byte failure", 1,
                        CommandDispatchOutcome.transportFailure(PRE_CONNECT_FAILURE), Map.of(
                        EngineCommandStatus.DISPATCHING, retry)),
                new Scenario("possibly-sent failure", 1,
                        CommandDispatchOutcome.transportFailure(MID_WRITE_FAILURE), Map.of(
                        EngineCommandStatus.DISPATCHING, awaitingTransport)),
                new Scenario("accepted without proof", 1,
                        CommandDispatchOutcome.http(202, ACCEPTED, null, null), Map.of(
                        EngineCommandStatus.DISPATCHING, awaitingHttp)),
                new Scenario("malformed response", 1,
                        CommandDispatchOutcome.malformedResponse(), Map.of(
                        EngineCommandStatus.DISPATCHING, malformed)),
                new Scenario("unproven duplicate", 1,
                        CommandDispatchOutcome.duplicateResponse(null), Map.of(
                        EngineCommandStatus.DISPATCHING, duplicate)),
                new Scenario("expired lease", 1,
                        CommandDispatchOutcome.leaseExpired(), Map.of(
                        EngineCommandStatus.DISPATCHING, expiredLease)),
                new Scenario("observation confirmation", 1,
                        CommandDispatchOutcome.observation(confirmation(type, OBSERVATION)), Map.of(
                        EngineCommandStatus.PENDING, confirmed,
                        EngineCommandStatus.DISPATCHING, confirmed,
                        EngineCommandStatus.RETRYABLE, confirmed,
                        EngineCommandStatus.AWAITING_CONFIRMATION, confirmed,
                        EngineCommandStatus.CONFLICT, confirmed,
                        EngineCommandStatus.MANUAL_REVIEW, confirmed,
                        EngineCommandStatus.CONFIRMED, confirmed)),
                new Scenario("HTTP confirmation", 1,
                        CommandDispatchOutcome.http(200, ACCEPTED, null,
                                confirmation(type, HTTP_RESPONSE)), Map.of(
                        EngineCommandStatus.DISPATCHING, confirmed,
                        EngineCommandStatus.CONFIRMED, confirmed)),
                new Scenario("duplicate confirmation", 1,
                        CommandDispatchOutcome.duplicateResponse(
                                confirmation(type, DUPLICATE_RESPONSE)), Map.of(
                        EngineCommandStatus.DISPATCHING, confirmed,
                        EngineCommandStatus.CONFIRMED, confirmed)),
                new Scenario("reconciliation confirmation", 1,
                        CommandDispatchOutcome.reconciliationConfirmed(
                                confirmation(type, RECONCILIATION)), Map.of(
                        EngineCommandStatus.AWAITING_CONFIRMATION, confirmed,
                        EngineCommandStatus.CONFLICT, confirmed,
                        EngineCommandStatus.MANUAL_REVIEW, confirmed,
                        EngineCommandStatus.CONFIRMED, confirmed)),
                new Scenario("reconciliation absence", 1,
                        CommandDispatchOutcome.reconciliation(review(
                                type, DEFINITIVE_ABSENCE,
                                CommandDispatchOutcome.ReviewSource.RECONCILIATION)), Map.of(
                        EngineCommandStatus.AWAITING_CONFIRMATION,
                        expected(EngineCommandStatus.RETRYABLE,
                                "reconcile.absent", FIRST_RETRY),
                        EngineCommandStatus.CONFLICT,
                        expected(EngineCommandStatus.RETRYABLE,
                                "reconcile.absent", FIRST_RETRY),
                        EngineCommandStatus.MANUAL_REVIEW,
                        expected(EngineCommandStatus.RETRYABLE,
                                "reconcile.absent", FIRST_RETRY))),
                new Scenario("inconclusive reconciliation", 1,
                        CommandDispatchOutcome.reconciliation(review(
                                type, INCONCLUSIVE,
                                CommandDispatchOutcome.ReviewSource.RECONCILIATION)), Map.of(
                        EngineCommandStatus.AWAITING_CONFIRMATION, inconclusive,
                        EngineCommandStatus.CONFLICT, inconclusive,
                        EngineCommandStatus.MANUAL_REVIEW, inconclusive)),
                new Scenario("manual review", 1,
                        CommandDispatchOutcome.manualReviewRequested(
                                operator(type, false)), Map.of(
                        EngineCommandStatus.AWAITING_CONFIRMATION, manual,
                        EngineCommandStatus.CONFLICT, manual,
                        EngineCommandStatus.MANUAL_REVIEW, manual)),
                new Scenario("reconciliation request", 1,
                        CommandDispatchOutcome.reconciliationRequested(
                                operator(type, false)), Map.of(
                        EngineCommandStatus.AWAITING_CONFIRMATION, awaitingReview,
                        EngineCommandStatus.CONFLICT, awaitingReview,
                        EngineCommandStatus.MANUAL_REVIEW, awaitingReview)),
                new Scenario("reviewed retry", 1,
                        CommandDispatchOutcome.retryAfterReviewedAbsence(
                                review(type, DEFINITIVE_ABSENCE, OPERATOR_REVIEW),
                                operator(type, false)), Map.of(
                        EngineCommandStatus.AWAITING_CONFIRMATION, reviewedRetry,
                        EngineCommandStatus.CONFLICT, reviewedRetry,
                        EngineCommandStatus.MANUAL_REVIEW, reviewedRetry,
                        EngineCommandStatus.RETRYABLE, reviewedRetry)),
                new Scenario("cancel unsent", 0,
                        CommandDispatchOutcome.cancelUnsent(
                                operator(type, false)), Map.of(
                        EngineCommandStatus.PENDING, cancelled,
                        EngineCommandStatus.RETRYABLE, cancelled,
                        EngineCommandStatus.CANCELLED, cancelled)),
                new Scenario("cancel after review", 1,
                        CommandDispatchOutcome.cancelAfterReviewedAbsence(
                                review(type, DEFINITIVE_ABSENCE, OPERATOR_REVIEW),
                                operator(type, false)), Map.of(
                        EngineCommandStatus.PENDING, cancelled,
                        EngineCommandStatus.RETRYABLE, cancelled,
                        EngineCommandStatus.AWAITING_CONFIRMATION, cancelled,
                        EngineCommandStatus.CONFLICT, cancelled,
                        EngineCommandStatus.MANUAL_REVIEW, cancelled,
                        EngineCommandStatus.CANCELLED, cancelled))
        };
    }

    @ParameterizedTest(name = "{0} is classified for every current command type")
    @EnumSource(EngineCommand.Type.class)
    void everyCurrentCommandTypeHasAnExplicitTerminalStateAndTargetClassification(
            EngineCommand.Type type) {
        assertThat(POLICY.expectedTerminalStates(type)).containsExactlyInAnyOrderElementsOf(switch (type) {
            case CREATE_TASK -> java.util.Set.of(CommandDispatchOutcome.RemoteState.TASK_CREATED);
            case CLAIM_TASK -> java.util.Set.of(CommandDispatchOutcome.RemoteState.TASK_CLAIMED);
            case COMPLETE_TASK -> java.util.Set.of(CommandDispatchOutcome.RemoteState.TASK_COMPLETED);
            case START_PROCESS -> java.util.Set.of(CommandDispatchOutcome.RemoteState.PROCESS_STARTED);
            case CANCEL_PROCESS -> java.util.Set.of(
                    CommandDispatchOutcome.RemoteState.PROCESS_CANCELLED,
                    CommandDispatchOutcome.RemoteState.PROCESS_TERMINATED);
            case DEPLOY_ORCHESTRATION -> java.util.Set.of(
                    CommandDispatchOutcome.RemoteState.ORCHESTRATION_DEPLOYED);
            case CORRELATE_MESSAGE -> java.util.Set.of(
                    CommandDispatchOutcome.RemoteState.MESSAGE_CORRELATED);
        });
        assertThat(POLICY.isResourceTargeted(type)).isEqualTo(switch (type) {
            case CREATE_TASK, CLAIM_TASK, COMPLETE_TASK, CANCEL_PROCESS -> true;
            case START_PROCESS, DEPLOY_ORCHESTRATION, CORRELATE_MESSAGE -> false;
        });
    }

    @ParameterizedTest(name = "{0} × {1} confirmation order")
    @MethodSource("confirmationOrderPermutations")
    void everyMatchingConfirmationSourceIsIdempotentInEveryOrder(
            EngineCommand.Type type,
            CommandDispatchOutcome.ConfirmationSource first,
            CommandDispatchOutcome.ConfirmationSource replay) {
        EngineCommandPolicy.CommandContext command = command(type);
        EngineCommandStatus start = first == RECONCILIATION
                ? EngineCommandStatus.AWAITING_CONFIRMATION : EngineCommandStatus.DISPATCHING;
        EngineCommandPolicy.Decision initial = POLICY.transition(
                command, start, 1, confirmationOutcome(type, first));

        assertThat(initial.status()).isEqualTo(EngineCommandStatus.CONFIRMED);
        assertThat(POLICY.transition(command, EngineCommandStatus.CONFIRMED, 1,
                confirmationOutcome(type, replay)).status())
                .isEqualTo(EngineCommandStatus.CONFIRMED);
    }

    static Stream<Arguments> confirmationOrderPermutations() {
        return Stream.of(EngineCommand.Type.values()).flatMap(type ->
                Stream.of(CommandDispatchOutcome.ConfirmationSource.values()).flatMap(first ->
                        Stream.of(CommandDispatchOutcome.ConfirmationSource.values()).map(replay ->
                                Arguments.of(type, first, replay))));
    }

    @ParameterizedTest(name = "reject {0} mismatch even after terminal confirmation")
    @MethodSource("mismatchedConfirmationEvidence")
    void confirmationEvidenceMustMatchTheWholeCommandIdentityEvenAfterConfirmed(
            String mismatch, CommandDispatchOutcome.ConfirmationEvidence evidence) {
        EngineCommandPolicy.CommandContext command = command(EngineCommand.Type.COMPLETE_TASK);
        CommandDispatchOutcome outcome = CommandDispatchOutcome.observation(evidence);

        assertThatThrownBy(() -> POLICY.transition(
                command, EngineCommandStatus.DISPATCHING, 1, outcome))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(mismatch);
        assertThatThrownBy(() -> POLICY.transition(
                command, EngineCommandStatus.CONFIRMED, 1, outcome))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(mismatch);
    }

    static Stream<Arguments> mismatchedConfirmationEvidence() {
        EngineCommand.Type type = EngineCommand.Type.COMPLETE_TASK;
        CommandDispatchOutcome.ConfirmationEvidence matching = confirmation(type, OBSERVATION);
        return Stream.of(
                Arguments.of("tenant", copy(matching, "other-tenant", matching.operationId(),
                        matching.commandId(), type, matching.expectedTargetIdentity(),
                        matching.remoteIdentity(), matching.remoteState(), OBSERVATION)),
                Arguments.of("operation", copy(matching, matching.tenantId(), "other-operation",
                        matching.commandId(), type, matching.expectedTargetIdentity(),
                        matching.remoteIdentity(), matching.remoteState(), OBSERVATION)),
                Arguments.of("command", copy(matching, matching.tenantId(), matching.operationId(),
                        "other-command", type, matching.expectedTargetIdentity(),
                        matching.remoteIdentity(), matching.remoteState(), OBSERVATION)),
                Arguments.of("type", copy(matching, matching.tenantId(), matching.operationId(),
                        matching.commandId(), EngineCommand.Type.CLAIM_TASK,
                        matching.expectedTargetIdentity(), matching.remoteIdentity(),
                        CommandDispatchOutcome.RemoteState.TASK_CLAIMED, OBSERVATION)),
                Arguments.of("target", copy(matching, matching.tenantId(), matching.operationId(),
                        matching.commandId(), type, "other-target", matching.remoteIdentity(),
                        matching.remoteState(), OBSERVATION)),
                Arguments.of("remote identity", copy(matching, matching.tenantId(),
                        matching.operationId(), matching.commandId(), type,
                        matching.expectedTargetIdentity(), "other-task",
                        matching.remoteState(), OBSERVATION)),
                Arguments.of("state", copy(matching, matching.tenantId(), matching.operationId(),
                        matching.commandId(), type, matching.expectedTargetIdentity(),
                        matching.remoteIdentity(), CommandDispatchOutcome.RemoteState.TASK_CLAIMED,
                        OBSERVATION)),
                Arguments.of("source", copy(matching, matching.tenantId(), matching.operationId(),
                        matching.commandId(), type, matching.expectedTargetIdentity(),
                        matching.remoteIdentity(), matching.remoteState(), HTTP_RESPONSE)));
    }

    @ParameterizedTest
    @EnumSource(EngineCommand.Type.class)
    void wrongTerminalStateIsRejectedForEveryCommandType(EngineCommand.Type type) {
        CommandDispatchOutcome.ConfirmationEvidence matching = confirmation(type, OBSERVATION);
        CommandDispatchOutcome.RemoteState wrongState = switch (type) {
            case CREATE_TASK -> CommandDispatchOutcome.RemoteState.TASK_COMPLETED;
            case CLAIM_TASK -> CommandDispatchOutcome.RemoteState.TASK_CREATED;
            case COMPLETE_TASK -> CommandDispatchOutcome.RemoteState.TASK_CLAIMED;
            case START_PROCESS -> CommandDispatchOutcome.RemoteState.PROCESS_TERMINATED;
            case CANCEL_PROCESS -> CommandDispatchOutcome.RemoteState.PROCESS_STARTED;
            case DEPLOY_ORCHESTRATION -> CommandDispatchOutcome.RemoteState.PROCESS_STARTED;
            case CORRELATE_MESSAGE -> CommandDispatchOutcome.RemoteState.PROCESS_STARTED;
        };
        CommandDispatchOutcome.ConfirmationEvidence wrong = copy(
                matching, matching.tenantId(), matching.operationId(), matching.commandId(),
                type, matching.expectedTargetIdentity(), matching.remoteIdentity(),
                wrongState, OBSERVATION);

        assertThatThrownBy(() -> POLICY.transition(command(type),
                EngineCommandStatus.DISPATCHING, 1,
                CommandDispatchOutcome.observation(wrong)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("state");
    }

    @ParameterizedTest(name = "HTTP {0}/{1} for {2} -> {3}")
    @MethodSource("httpDecisionTable")
    void httpStatusAndAcceptanceAreClassifiedIndependently(
            int status, CommandDispatchOutcome.Acceptance acceptance,
            EngineCommand.Type type, EngineCommandStatus expectedStatus,
            String expectedCode, Duration retryAfter, OffsetDateTime expectedRetryAt) {
        EngineCommandPolicy.Decision decision = POLICY.transition(command(type),
                EngineCommandStatus.DISPATCHING, 1,
                CommandDispatchOutcome.http(status, acceptance, retryAfter, null));

        assertThat(decision.status()).isEqualTo(expectedStatus);
        assertThat(decision.errorCode()).isEqualTo(expectedCode);
        assertThat(decision.nextAttemptAt()).isEqualTo(expectedRetryAt);
    }

    static Stream<Arguments> httpDecisionTable() {
        return Stream.of(
                http(200, ACCEPTED, EngineCommand.Type.COMPLETE_TASK,
                        EngineCommandStatus.AWAITING_CONFIRMATION, "response.unconfirmed", null, null),
                http(202, ACCEPTED, EngineCommand.Type.START_PROCESS,
                        EngineCommandStatus.AWAITING_CONFIRMATION, "response.unconfirmed", null, null),
                http(400, PROVEN_NOT_ACCEPTED, EngineCommand.Type.COMPLETE_TASK,
                        EngineCommandStatus.FAILED, "http.400.rejected", null, null),
                http(401, PROVEN_NOT_ACCEPTED, EngineCommand.Type.COMPLETE_TASK,
                        EngineCommandStatus.FAILED, "http.401.rejected", null, null),
                http(403, PROVEN_NOT_ACCEPTED, EngineCommand.Type.COMPLETE_TASK,
                        EngineCommandStatus.FAILED, "http.403.rejected", null, null),
                http(404, PROVEN_NOT_ACCEPTED, EngineCommand.Type.COMPLETE_TASK,
                        EngineCommandStatus.FAILED, "http.404.rejected", null, null),
                http(404, PROVEN_NOT_ACCEPTED, EngineCommand.Type.CANCEL_PROCESS,
                        EngineCommandStatus.CONFLICT, "target.not_found", null, null),
                http(404, POSSIBLY_ACCEPTED, EngineCommand.Type.CANCEL_PROCESS,
                        EngineCommandStatus.AWAITING_CONFIRMATION, "response.ambiguous", null, null),
                http(408, PROVEN_NOT_ACCEPTED, EngineCommand.Type.START_PROCESS,
                        EngineCommandStatus.RETRYABLE, "http.408.not_accepted",
                        Duration.ofMinutes(10), OffsetDateTime.parse("2026-08-28T12:10:00Z")),
                http(408, POSSIBLY_ACCEPTED, EngineCommand.Type.START_PROCESS,
                        EngineCommandStatus.AWAITING_CONFIRMATION, "response.ambiguous", null, null),
                http(425, PROVEN_NOT_ACCEPTED, EngineCommand.Type.CORRELATE_MESSAGE,
                        EngineCommandStatus.RETRYABLE, "http.425.not_accepted", null, FIRST_RETRY),
                http(425, POSSIBLY_ACCEPTED, EngineCommand.Type.CORRELATE_MESSAGE,
                        EngineCommandStatus.AWAITING_CONFIRMATION, "response.ambiguous", null, null),
                http(429, PROVEN_NOT_ACCEPTED, EngineCommand.Type.COMPLETE_TASK,
                        EngineCommandStatus.RETRYABLE, "http.429.not_accepted",
                        Duration.ofMinutes(30), OffsetDateTime.parse("2026-08-28T12:30:00Z")),
                http(429, POSSIBLY_ACCEPTED, EngineCommand.Type.COMPLETE_TASK,
                        EngineCommandStatus.AWAITING_CONFIRMATION, "response.ambiguous", null, null),
                http(409, PROVEN_NOT_ACCEPTED, EngineCommand.Type.COMPLETE_TASK,
                        EngineCommandStatus.CONFLICT, "http.409.conflict", null, null),
                http(422, PROVEN_NOT_ACCEPTED, EngineCommand.Type.COMPLETE_TASK,
                        EngineCommandStatus.FAILED, "http.422.rejected", null, null),
                http(500, PROVEN_NOT_ACCEPTED, EngineCommand.Type.START_PROCESS,
                        EngineCommandStatus.RETRYABLE, "http.500.not_accepted", null, FIRST_RETRY),
                http(500, POSSIBLY_ACCEPTED, EngineCommand.Type.START_PROCESS,
                        EngineCommandStatus.AWAITING_CONFIRMATION, "response.ambiguous", null, null),
                http(503, PROVEN_NOT_ACCEPTED, EngineCommand.Type.COMPLETE_TASK,
                        EngineCommandStatus.RETRYABLE, "http.503.not_accepted",
                        Duration.ofHours(2), OffsetDateTime.parse("2026-08-28T14:00:00Z")),
                http(503, POSSIBLY_ACCEPTED, EngineCommand.Type.COMPLETE_TASK,
                        EngineCommandStatus.AWAITING_CONFIRMATION, "response.ambiguous", null, null));
    }

    @ParameterizedTest(name = "{0} transport phase -> {1}")
    @MethodSource("transportDecisionTable")
    void onlyProvenZeroByteTransportFailuresCanRetry(
            CommandDispatchOutcome.TransportFailure failure,
            EngineCommandStatus expected, String code, OffsetDateTime retryAt) {
        EngineCommandPolicy.Decision decision = POLICY.transition(
                command(EngineCommand.Type.START_PROCESS), EngineCommandStatus.DISPATCHING, 1,
                CommandDispatchOutcome.transportFailure(failure));

        assertThat(decision.status()).isEqualTo(expected);
        assertThat(decision.errorCode()).isEqualTo(code);
        assertThat(decision.nextAttemptAt()).isEqualTo(retryAt);
    }

    static Stream<Arguments> transportDecisionTable() {
        return Stream.of(
                Arguments.of(PRE_CONNECT_FAILURE, EngineCommandStatus.RETRYABLE,
                        "transport.not_sent", FIRST_RETRY),
                Arguments.of(PRE_SEND_ZERO_BYTES, EngineCommandStatus.RETRYABLE,
                        "transport.not_sent", FIRST_RETRY),
                Arguments.of(MID_WRITE_FAILURE, EngineCommandStatus.AWAITING_CONFIRMATION,
                        "transport.possibly_sent", null),
                Arguments.of(TIMEOUT, EngineCommandStatus.AWAITING_CONFIRMATION,
                        "transport.possibly_sent", null),
                Arguments.of(READ_FAILURE, EngineCommandStatus.AWAITING_CONFIRMATION,
                        "transport.possibly_sent", null),
                Arguments.of(UNKNOWN, EngineCommandStatus.AWAITING_CONFIRMATION,
                        "transport.possibly_sent", null));
    }

    @Test
    void cancellationRequiresExactCancelledOrTerminatedEvidenceRatherThan404OrAbsence() {
        EngineCommandPolicy.CommandContext command = command(EngineCommand.Type.CANCEL_PROCESS);

        assertThat(POLICY.transition(command, EngineCommandStatus.DISPATCHING, 1,
                CommandDispatchOutcome.http(404, PROVEN_NOT_ACCEPTED, null, null)).status())
                .isEqualTo(EngineCommandStatus.CONFLICT);
        assertThat(POLICY.transition(command, EngineCommandStatus.AWAITING_CONFIRMATION, 1,
                CommandDispatchOutcome.reconciliation(review(EngineCommand.Type.CANCEL_PROCESS,
                        DEFINITIVE_ABSENCE,
                        CommandDispatchOutcome.ReviewSource.RECONCILIATION))).status())
                .isEqualTo(EngineCommandStatus.RETRYABLE);
        assertThat(POLICY.transition(command, EngineCommandStatus.AWAITING_CONFIRMATION, 1,
                CommandDispatchOutcome.observation(confirmation(
                        EngineCommand.Type.CANCEL_PROCESS, OBSERVATION))).status())
                .isEqualTo(EngineCommandStatus.CONFIRMED);
        assertThatThrownBy(() -> CommandDispatchOutcome.http(
                404, PROVEN_NOT_ACCEPTED, null,
                confirmation(EngineCommand.Type.CANCEL_PROCESS, HTTP_RESPONSE)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("2xx");
    }

    @Test
    void reviewEvidenceIsBoundAndCannotAuthorizeAnotherCommand() {
        CommandDispatchOutcome.ReviewEvidence wrong = new CommandDispatchOutcome.ReviewEvidence(
                "tenant-a", "operation-a", "another-command", EngineCommand.Type.COMPLETE_TASK,
                "task-a", DEFINITIVE_ABSENCE,
                CommandDispatchOutcome.ReviewSource.RECONCILIATION, "review:44");

        assertThatThrownBy(() -> POLICY.transition(command(EngineCommand.Type.COMPLETE_TASK),
                EngineCommandStatus.AWAITING_CONFIRMATION, 1,
                CommandDispatchOutcome.reconciliation(wrong)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("command");
    }

    @Test
    void manualActionsAreAuditedReplayIdempotentAndExplicitAboutBudgetOverride() {
        EngineCommandPolicy.CommandContext command = command(EngineCommand.Type.COMPLETE_TASK);
        CommandDispatchOutcome.ReviewEvidence absence = review(
                EngineCommand.Type.COMPLETE_TASK, DEFINITIVE_ABSENCE, OPERATOR_REVIEW);
        CommandDispatchOutcome.OperatorAction ordinary = operator(
                EngineCommand.Type.COMPLETE_TASK, false);
        CommandDispatchOutcome retry = CommandDispatchOutcome.retryAfterReviewedAbsence(
                absence, ordinary);

        EngineCommandPolicy.Decision first = POLICY.transition(command,
                EngineCommandStatus.MANUAL_REVIEW, 2, retry);
        EngineCommandPolicy.Decision replay = POLICY.transition(command,
                EngineCommandStatus.RETRYABLE, 2, retry);

        assertThat(first).isEqualTo(replay);
        assertThat(first.operatorActionId()).isEqualTo("operator-action-7");
        assertThat(first.auditReference()).isEqualTo("audit:operator-action-7");
        assertThat(first.resetAutomaticAttempts()).isFalse();
        assertThat(first.totalDispatchAttempts()).isEqualTo(2);

        CommandDispatchOutcome override = CommandDispatchOutcome.retryAfterReviewedAbsence(
                absence, operator(EngineCommand.Type.COMPLETE_TASK, true));
        EngineCommandPolicy.Decision overridden = POLICY.transition(command,
                EngineCommandStatus.MANUAL_REVIEW,
                EngineCommandPolicy.MAX_AUTOMATIC_ATTEMPTS, override);
        assertThat(overridden.status()).isEqualTo(EngineCommandStatus.RETRYABLE);
        assertThat(overridden.resetAutomaticAttempts()).isTrue();
        assertThat(overridden.totalDispatchAttempts()).isZero();
        assertThat(POLICY.transition(command, EngineCommandStatus.RETRYABLE,
                overridden.totalDispatchAttempts(), override)).isEqualTo(overridden);

        assertThatThrownBy(() -> POLICY.transition(command,
                EngineCommandStatus.MANUAL_REVIEW,
                EngineCommandPolicy.MAX_AUTOMATIC_ATTEMPTS, retry))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("override");
        assertThatThrownBy(() -> POLICY.transition(command,
                EngineCommandStatus.MANUAL_REVIEW, 2, override))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("override");
    }

    @Test
    void allManualActionKindsAreReplayIdempotentAndCarryAuditIdentity() {
        EngineCommandPolicy.CommandContext command = command(EngineCommand.Type.COMPLETE_TASK);
        CommandDispatchOutcome.OperatorAction action = operator(
                EngineCommand.Type.COMPLETE_TASK, false);

        assertManualReplay(command, EngineCommandStatus.AWAITING_CONFIRMATION,
                EngineCommandStatus.MANUAL_REVIEW,
                CommandDispatchOutcome.manualReviewRequested(action));
        assertManualReplay(command, EngineCommandStatus.MANUAL_REVIEW,
                EngineCommandStatus.AWAITING_CONFIRMATION,
                CommandDispatchOutcome.reconciliationRequested(action));
        assertManualReplay(command, EngineCommandStatus.PENDING,
                EngineCommandStatus.CANCELLED,
                CommandDispatchOutcome.cancelUnsent(action));
        assertManualReplay(command, EngineCommandStatus.AWAITING_CONFIRMATION,
                EngineCommandStatus.CANCELLED,
                CommandDispatchOutcome.cancelAfterReviewedAbsence(
                        review(EngineCommand.Type.COMPLETE_TASK, DEFINITIVE_ABSENCE,
                                OPERATOR_REVIEW), action));
    }

    @Test
    void dispatchCounterMeansTotalStartedAttemptsAndAutomaticBudgetIsSeparate() {
        EngineCommandPolicy.CommandContext command = command(EngineCommand.Type.START_PROCESS);

        EngineCommandPolicy.Decision firstDispatch = POLICY.transition(
                command, EngineCommandStatus.PENDING, 0,
                CommandDispatchOutcome.dispatchRequested());
        assertThat(firstDispatch.totalDispatchAttempts()).isEqualTo(1);

        EngineCommandPolicy.Decision finalAutomaticRetry = POLICY.transition(command,
                EngineCommandStatus.DISPATCHING,
                EngineCommandPolicy.MAX_AUTOMATIC_ATTEMPTS - 1,
                CommandDispatchOutcome.transportFailure(PRE_SEND_ZERO_BYTES));
        assertThat(finalAutomaticRetry.status()).isEqualTo(EngineCommandStatus.RETRYABLE);
        assertThat(finalAutomaticRetry.nextAttemptAt())
                .isBetween(OffsetDateTime.parse("2026-08-28T20:00:00Z"),
                        OffsetDateTime.parse("2026-08-29T00:00:00Z"));

        EngineCommandPolicy.Decision exhausted = POLICY.transition(command,
                EngineCommandStatus.DISPATCHING,
                EngineCommandPolicy.MAX_AUTOMATIC_ATTEMPTS,
                CommandDispatchOutcome.transportFailure(PRE_SEND_ZERO_BYTES));
        assertThat(exhausted.status()).isEqualTo(EngineCommandStatus.FAILED);
        assertThat(exhausted.errorCode()).isEqualTo("attempts.exhausted");
    }

    @Test
    void retrySchedulingSaturatesAtPersistableTimestampAndBoundsRetryAfter() {
        EngineCommandPolicy farFuturePolicy = new EngineCommandPolicy(Clock.fixed(
                Instant.parse("9999-12-31T23:59:59.999998Z"), ZoneOffset.UTC));
        EngineCommandPolicy.Decision saturated = farFuturePolicy.transition(
                command(EngineCommand.Type.COMPLETE_TASK), EngineCommandStatus.DISPATCHING, 1,
                CommandDispatchOutcome.transportFailure(PRE_SEND_ZERO_BYTES));
        assertThat(saturated.nextAttemptAt()).isEqualTo(
                EngineCommandPolicy.MAX_PERSISTABLE_TIMESTAMP);

        EngineCommandPolicy.Decision bounded = POLICY.transition(
                command(EngineCommand.Type.COMPLETE_TASK), EngineCommandStatus.DISPATCHING, 1,
                CommandDispatchOutcome.http(429, PROVEN_NOT_ACCEPTED,
                        Duration.ofDays(365_000), null));
        assertThat(bounded.nextAttemptAt()).isEqualTo(
                OffsetDateTime.parse("2026-09-27T12:00:00Z"));
    }

    @Test
    void evidenceAndAuditReferencesRejectUnsafeOrSecretBearingValues() {
        assertThatThrownBy(() -> new CommandDispatchOutcome.ConfirmationEvidence(
                "tenant-a", "operation-a", "command-a", EngineCommand.Type.COMPLETE_TASK,
                "task-a", "task-a", CommandDispatchOutcome.RemoteState.TASK_COMPLETED,
                OBSERVATION, "Bearer secret"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CommandDispatchOutcome.OperatorAction(
                "tenant-a", "operation-a", "command-a",
                EngineCommand.Type.COMPLETE_TASK, "task-a",
                "action-a", "password=hunter2",
                OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC), false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void operatorActionsAreBoundToOneCommandEvenWithoutReviewEvidence() {
        CommandDispatchOutcome.OperatorAction wrong = new CommandDispatchOutcome.OperatorAction(
                "tenant-a", "operation-a", "another-command",
                EngineCommand.Type.COMPLETE_TASK, "task-a",
                "operator-action-7", "audit:operator-action-7",
                OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC), false);

        assertThatThrownBy(() -> POLICY.transition(command(EngineCommand.Type.COMPLETE_TASK),
                EngineCommandStatus.PENDING, 0,
                CommandDispatchOutcome.cancelUnsent(wrong)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("command");
    }

    @Test
    void impossibleOutcomeShapesFailClosed() {
        assertThatThrownBy(() -> CommandDispatchOutcome.http(99, ACCEPTED, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CommandDispatchOutcome.http(
                200, PROVEN_NOT_ACCEPTED, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CommandDispatchOutcome.http(
                400, ACCEPTED, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> POLICY.transition(command(EngineCommand.Type.COMPLETE_TASK),
                EngineCommandStatus.DISPATCHING, 0,
                CommandDispatchOutcome.transportFailure(PRE_SEND_ZERO_BYTES)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("totalDispatchAttempts");
    }

    private static void assertManualReplay(
            EngineCommandPolicy.CommandContext command, EngineCommandStatus start,
            EngineCommandStatus result, CommandDispatchOutcome outcome) {
        EngineCommandPolicy.Decision first = POLICY.transition(command, start, 1, outcome);
        EngineCommandPolicy.Decision replay = POLICY.transition(command, result, 1, outcome);
        assertThat(first).isEqualTo(replay);
        assertThat(first.operatorActionId()).isEqualTo("operator-action-7");
        assertThat(first.auditReference()).isEqualTo("audit:operator-action-7");
    }

    private static CommandDispatchOutcome confirmationOutcome(
            EngineCommand.Type type, CommandDispatchOutcome.ConfirmationSource source) {
        CommandDispatchOutcome.ConfirmationEvidence evidence = confirmation(type, source);
        return switch (source) {
            case HTTP_RESPONSE -> CommandDispatchOutcome.http(200, ACCEPTED, null, evidence);
            case DUPLICATE_RESPONSE -> CommandDispatchOutcome.duplicateResponse(evidence);
            case OBSERVATION -> CommandDispatchOutcome.observation(evidence);
            case RECONCILIATION -> CommandDispatchOutcome.reconciliationConfirmed(evidence);
        };
    }

    private static EngineCommandPolicy.CommandContext command(EngineCommand.Type type) {
        return new EngineCommandPolicy.CommandContext(
                "tenant-a", "operation-a", "command-a", type, expectedTarget(type));
    }

    private static CommandDispatchOutcome.ConfirmationEvidence confirmation(
            EngineCommand.Type type, CommandDispatchOutcome.ConfirmationSource source) {
        return new CommandDispatchOutcome.ConfirmationEvidence(
                "tenant-a", "operation-a", "command-a", type, expectedTarget(type),
                remoteIdentity(type), expectedState(type), source, "evidence:44");
    }

    private static CommandDispatchOutcome.ReviewEvidence review(
            EngineCommand.Type type, CommandDispatchOutcome.ReviewFinding finding,
            CommandDispatchOutcome.ReviewSource source) {
        return new CommandDispatchOutcome.ReviewEvidence(
                "tenant-a", "operation-a", "command-a", type, expectedTarget(type),
                finding, source, "review:44");
    }

    private static CommandDispatchOutcome.OperatorAction operator(
            EngineCommand.Type type, boolean override) {
        return new CommandDispatchOutcome.OperatorAction(
                "tenant-a", "operation-a", "command-a", type, expectedTarget(type),
                "operator-action-7", "audit:operator-action-7",
                OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC), override);
    }

    private static CommandDispatchOutcome.ConfirmationEvidence copy(
            CommandDispatchOutcome.ConfirmationEvidence original, String tenantId,
            String operationId, String commandId, EngineCommand.Type type,
            String expectedTarget, String remoteIdentity,
            CommandDispatchOutcome.RemoteState state,
            CommandDispatchOutcome.ConfirmationSource source) {
        return new CommandDispatchOutcome.ConfirmationEvidence(
                tenantId, operationId, commandId, type, expectedTarget,
                remoteIdentity, state, source, original.evidenceReference());
    }

    private static String expectedTarget(EngineCommand.Type type) {
        return switch (type) {
            case CREATE_TASK -> "plan-item-a";
            case CLAIM_TASK, COMPLETE_TASK -> "task-a";
            case START_PROCESS -> "process-definition-a";
            case CANCEL_PROCESS -> "process-instance-a";
            case DEPLOY_ORCHESTRATION -> "release-a";
            case CORRELATE_MESSAGE -> "message-correlation-a";
        };
    }

    private static String remoteIdentity(EngineCommand.Type type) {
        return switch (type) {
            case CREATE_TASK -> "engine-task-a";
            case CLAIM_TASK, COMPLETE_TASK -> "task-a";
            case START_PROCESS -> "process-instance-a";
            case CANCEL_PROCESS -> "process-instance-a";
            case DEPLOY_ORCHESTRATION -> "deployment-a";
            case CORRELATE_MESSAGE -> "execution-a";
        };
    }

    private static CommandDispatchOutcome.RemoteState expectedState(EngineCommand.Type type) {
        return switch (type) {
            case CREATE_TASK -> CommandDispatchOutcome.RemoteState.TASK_CREATED;
            case CLAIM_TASK -> CommandDispatchOutcome.RemoteState.TASK_CLAIMED;
            case COMPLETE_TASK -> CommandDispatchOutcome.RemoteState.TASK_COMPLETED;
            case START_PROCESS -> CommandDispatchOutcome.RemoteState.PROCESS_STARTED;
            case CANCEL_PROCESS -> CommandDispatchOutcome.RemoteState.PROCESS_TERMINATED;
            case DEPLOY_ORCHESTRATION ->
                    CommandDispatchOutcome.RemoteState.ORCHESTRATION_DEPLOYED;
            case CORRELATE_MESSAGE -> CommandDispatchOutcome.RemoteState.MESSAGE_CORRELATED;
        };
    }

    private static Arguments http(
            int status, CommandDispatchOutcome.Acceptance acceptance, EngineCommand.Type type,
            EngineCommandStatus expectedStatus, String code, Duration retryAfter,
            OffsetDateTime expectedRetryAt) {
        return Arguments.of(status, acceptance, type, expectedStatus,
                code, retryAfter, expectedRetryAt);
    }

    private static Expected expected(
            EngineCommandStatus status, String code, OffsetDateTime retryAt) {
        return new Expected(status, code, retryAt);
    }

    private record Scenario(
            String name, int totalDispatchAttempts, CommandDispatchOutcome outcome,
            Map<EngineCommandStatus, Expected> expectedByStatus) {
        @Override public String toString() { return name; }
    }

    private record Expected(
            EngineCommandStatus status, String errorCode, OffsetDateTime nextAttemptAt) { }
}
