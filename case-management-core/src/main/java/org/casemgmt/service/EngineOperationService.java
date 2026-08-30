package org.casemgmt.service;

import org.casemgmt.domain.CaseIds;
import org.casemgmt.domain.CaseInstance;
import org.casemgmt.domain.CaseTask;
import org.casemgmt.error.CaseConflictException;
import org.casemgmt.engine.CommandDispatchOutcome;
import org.casemgmt.engine.EngineCommand;
import org.casemgmt.engine.EngineCommandPolicy;
import org.casemgmt.engine.EngineCommandStatus;
import org.casemgmt.engine.ProductionEngineCommandStore;
import org.casemgmt.event.EventPublisher;
import org.casemgmt.repo.JsonCodec;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The API-facing view of durable engine commands. A request is an operation, not confirmation:
 * this service intentionally owns no case or task projection write.
 */
public class EngineOperationService {

    public record Operation(String id, String commandId, String caseId, String commandType,
                            String targetId, String status, long version,
                            String safeErrorCode, String safeSummary,
                            List<String> availableActions) { }

    public enum SupportAction { RETRY, RECONCILE, CANCEL }

    private final ProductionEngineCommandStore commands;
    private final EventPublisher events;
    private final Clock clock;

    public EngineOperationService(ProductionEngineCommandStore commands, EventPublisher events) {
        this(commands, events, Clock.systemUTC());
    }

    EngineOperationService(ProductionEngineCommandStore commands, EventPublisher events,
                           Clock clock) {
        this.commands = Objects.requireNonNull(commands, "commands");
        this.events = Objects.requireNonNull(events, "events");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Operation submitClaim(CaseInstance instance, CaseTask task, long expectedTaskVersion,
                                 Actor actor, String idempotencyKey) {
        return submit(instance, task, expectedTaskVersion, actor, idempotencyKey,
                EngineCommand.Type.CLAIM_TASK,
                Map.of("engineTaskId", task.engineTaskId(), "userId", actor.userId()),
                Map.of("requestedAssignee", actor.userId()));
    }

    public Operation submitComplete(CaseInstance instance, CaseTask task, long expectedTaskVersion,
                                    Map<String, Object> variables, Actor actor,
                                    String idempotencyKey) {
        // Map.copyOf rejects intentional null form values. The command codec already canonicalizes
        // and validates the map; retain those values so remote repair sees the same intent.
        Map<String, Object> safeVariables = variables == null ? Map.of()
                : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(variables));
        return submit(instance, task, expectedTaskVersion, actor, idempotencyKey,
                EngineCommand.Type.COMPLETE_TASK,
                Map.of("engineTaskId", task.engineTaskId(), "variables", safeVariables),
                Map.of("requestedVariables", safeVariables));
    }

    public Optional<Operation> find(String tenantId, String operationId) {
        return commands.find(tenantId, operationId).map(EngineOperationService::operation);
    }

    /**
     * Submits a declared discretionary action through the same durable command record used by
     * normal remote task operations.  The action id is retained as immutable command
     * correlation metadata; it is deliberately not added to the engine payload, whose closed
     * contract is owned by {@link org.casemgmt.engine.EngineCommandPayload}.
     */
    public Operation submitAdHoc(CaseInstance instance, String actionId,
                                 EngineCommand.Type type, Map<String, Object> payload,
                                 String expectedTarget, long expectedCaseVersion,
                                 Actor actor, String idempotencyKey) {
        Objects.requireNonNull(instance, "instance");
        if (actionId == null || actionId.isBlank()) {
            throw new IllegalArgumentException("actionId must not be blank");
        }
        String stableKey = idempotencyKey == null || idempotencyKey.isBlank()
                ? "adhoc:" + instance.id() + ":" + actionId + ":" + expectedCaseVersion
                : idempotencyKey;
        String operationId = CaseIds.newId();
        ProductionEngineCommandStore.ProductionCommandRequest request =
                new ProductionEngineCommandStore.ProductionCommandRequest(
                        CaseIds.newId(), instance.id(), instance.tenantId(), operationId,
                        stableKey, type, payload, expectedTarget,
                        JsonCodec.canonicalJson(Map.of("adHocActionId", actionId)),
                        JsonCodec.canonicalJson(Map.of("adHocActionId", actionId,
                                "requestedBy", actor.userId())), expectedCaseVersion,
                        OffsetDateTime.now(clock));
        ProductionEngineCommandStore.Submission submission = commands.submit(request);
        Operation operation = operation(submission.command());
        if (!submission.replayed()) {
            OffsetDateTime now = OffsetDateTime.now(clock);
            events.publish(new org.casemgmt.event.CaseEvent(CaseIds.newId(), events.engineId(),
                    org.casemgmt.event.EventTypes.AD_HOC_ACTION_REQUESTED, instance.id(),
                    instance.tenantId(), now, Map.of("actionId", actionId,
                            "operationId", operation.id(), "type", type.name())));
            events.audit(instance.id(), instance.tenantId(), actor.userId(),
                    "ad-hoc.requested", "AdHocAction", actionId, Map.of(),
                    Map.of("operationId", operation.id(), "commandType", type.name()));
        }
        return operation;
    }

    public boolean hasActiveCommand(String tenantId, CaseTask task) {
        EngineCommand.Type type = task.state().name().equals("OPEN")
                ? EngineCommand.Type.CLAIM_TASK : EngineCommand.Type.COMPLETE_TASK;
        return commands.hasActiveTaskCommand(tenantId, type, task.engineTaskId());
    }

    /** Applies a policy-checked, normalized operator fact and writes an audit record. */
    public Operation support(String tenantId, String operationId, long expectedVersion,
                             SupportAction requested, Actor actor, String actionId,
                             String auditReference, String evidenceReference) {
        ProductionEngineCommandStore.StoredCommand current = commands.require(tenantId, operationId);
        CommandDispatchOutcome.OperatorAction action = new CommandDispatchOutcome.OperatorAction(
                tenantId, operationId, current.commandId(), current.state().command().commandType(),
                current.expectedTargetIdentity(), actionType(requested), actionId, auditReference,
                OffsetDateTime.now(clock), requested == SupportAction.RETRY);
        CommandDispatchOutcome outcome = outcome(current, requested, action, evidenceReference);
        commands.applyOperatorOutcome(tenantId, operationId, expectedVersion, outcome);
        ProductionEngineCommandStore.StoredCommand updated = commands.require(tenantId, operationId);
        events.audit(updated.caseId(), tenantId, actor.userId(),
                "engine-operation." + requested.name().toLowerCase(), "EngineOperation", operationId,
                Map.of("status", current.state().committedDecision().status().name()),
                Map.of("status", updated.state().committedDecision().status().name(),
                        "commandType", updated.state().command().commandType().name()));
        return operation(updated);
    }

    private Operation submit(CaseInstance instance, CaseTask task, long expectedTaskVersion,
                             Actor actor, String idempotencyKey, EngineCommand.Type type,
                             Map<String, Object> payload, Map<String, Object> patch) {
        String operationId = CaseIds.newId();
        String commandId = CaseIds.newId();
        String stableKey = idempotencyKey == null || idempotencyKey.isBlank()
                ? "task:" + task.id() + ":" + type.name() + ":" + expectedTaskVersion
                : idempotencyKey;
        ProductionEngineCommandStore.ProductionCommandRequest request =
                new ProductionEngineCommandStore.ProductionCommandRequest(commandId, instance.id(),
                        instance.tenantId(), operationId, stableKey, type, payload,
                        task.engineTaskId(), null, JsonCodec.canonicalJson(patch),
                        instance.version(), OffsetDateTime.now(clock));
        // Same-key retries must get the command store's exact-intent replay semantics. Only a
        // different key is a competing operation that must be suppressed while confirmation is
        // outstanding. The caller holds CM_TASK's row lock across this decision and submit.
        if (commands.findByIdempotency(instance.tenantId(), stableKey).isEmpty()
                && commands.hasActiveTaskCommand(instance.tenantId(), type, task.engineTaskId())) {
            throw new CaseConflictException("operation-pending",
                    "A conflicting engine operation is still awaiting confirmation for task " + task.id(),
                    List.of());
        }
        ProductionEngineCommandStore.Submission submission = commands.submit(request);
        Operation operation = operation(submission.command());
        if (!submission.replayed()) {
            events.audit(instance.id(), instance.tenantId(), actor.userId(),
                    "engine-operation.requested", "EngineOperation", operation.id(),
                    Map.of(), Map.of("commandType", type.name(), "targetId", task.engineTaskId(),
                            "requestedTaskVersion", expectedTaskVersion));
        }
        return operation;
    }

    private static CommandDispatchOutcome.ActionType actionType(SupportAction action) {
        return switch (action) {
            case RETRY -> CommandDispatchOutcome.ActionType.RETRY_OVERRIDE;
            case RECONCILE -> CommandDispatchOutcome.ActionType.RECONCILE;
            case CANCEL -> CommandDispatchOutcome.ActionType.CANCEL;
        };
    }

    private static CommandDispatchOutcome outcome(ProductionEngineCommandStore.StoredCommand command,
                                                  SupportAction action,
                                                  CommandDispatchOutcome.OperatorAction operator,
                                                  String evidenceReference) {
        return switch (action) {
            case RECONCILE -> CommandDispatchOutcome.reconciliationRequested(operator);
            case CANCEL -> CommandDispatchOutcome.cancelUnsent(operator);
            case RETRY -> CommandDispatchOutcome.retryAfterReviewedAbsence(
                    review(command, evidenceReference), operator);
        };
    }

    private static CommandDispatchOutcome.ReviewEvidence review(
            ProductionEngineCommandStore.StoredCommand command, String evidenceReference) {
        if (evidenceReference == null || evidenceReference.isBlank()) {
            throw new IllegalArgumentException("Retry requires reviewed absence evidence");
        }
        EngineCommandPolicy.CommandContext context = command.state().command();
        return new CommandDispatchOutcome.ReviewEvidence(context.tenantId(), context.operationId(),
                context.commandId(), context.commandType(), context.expectedTargetIdentity(),
                CommandDispatchOutcome.ReviewFinding.DEFINITIVE_ABSENCE,
                CommandDispatchOutcome.ReviewSource.OPERATOR_REVIEW, evidenceReference);
    }

    private static Operation operation(ProductionEngineCommandStore.StoredCommand command) {
        EngineCommandPolicy.Decision decision = command.state().committedDecision();
        return new Operation(command.operationId(), command.commandId(), command.caseId(),
                command.state().command().commandType().name(), command.expectedTargetIdentity(),
                decision.status().name(), command.version(), decision.errorCode(),
                decision.safeSummary(), availableActions(decision.status()));
    }

    private static List<String> availableActions(EngineCommandStatus status) {
        return switch (status) {
            case PENDING, RETRYABLE -> List.of("cancel");
            case AWAITING_CONFIRMATION, CONFLICT, MANUAL_REVIEW -> List.of("reconcile", "retry");
            default -> List.of();
        };
    }
}
