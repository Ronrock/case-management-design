package org.casemgmt.service;

import org.casemgmt.domain.*;
import org.casemgmt.engine.*;
import org.casemgmt.error.CaseConflictException;
import org.casemgmt.error.FormValidationException;
import org.casemgmt.error.InvalidCaseDefinitionException;
import org.casemgmt.error.NotFoundException;
import org.casemgmt.event.CaseEvent;
import org.casemgmt.event.EventPublisher;
import org.casemgmt.event.EventTypes;
import org.casemgmt.release.*;
import org.casemgmt.repo.*;
import org.casemgmt.rules.CriterionEvaluator;
import org.casemgmt.rules.EvaluationContext;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.*;

/** Executes only closed-schema actions on the immutable release pinned to a live case. */
public class AdHocActionService {
    public record Result(String actionId, String type, String planItemId, String taskId,
                         String linkedProcessId, CaseTask.EngineSync engineSync,
                         String operationId, String status) { }

    private final CaseRepository cases;
    private final CaseDefinitionVersionBindingRepository bindings;
    private final CaseDefinitionReleaseRepository releases;
    private final ParticipantRepository participants;
    private final PlanItemRepository planItems;
    private final CaseTaskRepository tasks;
    private final LinkedProcessService processes;
    private final EngineGateway engine;
    private final CriterionEvaluator criteria;
    private final EventPublisher publisher;
    private final EngineOperationService operations;
    private final CaseContractValidator contracts;
    private final FormValidator forms;

    public AdHocActionService(CaseRepository cases, CaseDefinitionVersionBindingRepository bindings,
                              CaseDefinitionReleaseRepository releases, ParticipantRepository participants,
                              PlanItemRepository planItems, CaseTaskRepository tasks,
                              LinkedProcessService processes, EngineGateway engine,
                              CriterionEvaluator criteria, EventPublisher publisher) {
        this(cases, bindings, releases, participants, planItems, tasks, processes, engine, criteria,
                publisher, null, new JsonSchemaCaseContractValidator(), new FormValidator());
    }

    public AdHocActionService(CaseRepository cases, CaseDefinitionVersionBindingRepository bindings,
                              CaseDefinitionReleaseRepository releases, ParticipantRepository participants,
                              PlanItemRepository planItems, CaseTaskRepository tasks,
                              LinkedProcessService processes, EngineGateway engine,
                              CriterionEvaluator criteria, EventPublisher publisher,
                              CaseContractValidator contracts, FormValidator forms) {
        this(cases, bindings, releases, participants, planItems, tasks, processes, engine, criteria,
                publisher, null, contracts, forms);
    }

    public AdHocActionService(CaseRepository cases, CaseDefinitionVersionBindingRepository bindings,
                              CaseDefinitionReleaseRepository releases, ParticipantRepository participants,
                              PlanItemRepository planItems, CaseTaskRepository tasks,
                              LinkedProcessService processes, EngineGateway engine,
                              CriterionEvaluator criteria, EventPublisher publisher,
                              EngineOperationService operations,
                              CaseContractValidator contracts, FormValidator forms) {
        this.cases = Objects.requireNonNull(cases); this.bindings = Objects.requireNonNull(bindings);
        this.releases = Objects.requireNonNull(releases); this.participants = Objects.requireNonNull(participants);
        this.planItems = Objects.requireNonNull(planItems); this.tasks = Objects.requireNonNull(tasks);
        this.processes = Objects.requireNonNull(processes); this.engine = Objects.requireNonNull(engine);
        this.criteria = Objects.requireNonNull(criteria); this.publisher = Objects.requireNonNull(publisher);
        this.operations = operations;
        this.contracts = Objects.requireNonNull(contracts); this.forms = Objects.requireNonNull(forms);
    }

    @Transactional
    public Result execute(String caseId, String actionId, long expectedVersion,
                          Map<String, Object> input, Actor actor) {
        return execute(caseId, actionId, expectedVersion, input, actor, null);
    }

    @Transactional
    public Result execute(String caseId, String actionId, long expectedVersion,
                          Map<String, Object> input, Actor actor, String idempotencyKey) {
        CaseInstance c = cases.require(caseId);
        ResolvedAction resolved = resolve(c, actionId);
        var action = resolved.action();
        authorize(c.id(), action, actor);
        if (c.version() != expectedVersion) throw new CaseConflictException("version-conflict",
                "Case version is " + c.version() + "; expected " + expectedVersion, List.of());
        if (c.state() != CaseState.ACTIVE) throw new CaseConflictException("case-not-active",
                "Ad-hoc actions are available only while the case is ACTIVE (was " + c.state() + ")", List.of());
        Map<String, Object> inputValues = immutableInput(input);
        validateInput(resolved.contract(), action, inputValues);
        if (action instanceof ValidatedCaseContract.MessageAction message) {
            validateCorrelation(c, message, inputValues);
        }
        String stableKey = actionKey(c, action, idempotencyKey);
        if (engine.defersTaskMutations()) {
            Optional<EngineOperationService.Operation> replay = requiredOperations()
                    .findAdHocReplay(c.tenantId(), c.id(), action.id(), stableKey);
            if (replay.isPresent()) {
                EngineOperationService.Operation operation = replay.orElseThrow();
                return new Result(action.id(), type(action), null, null, null,
                        CaseTask.EngineSync.PENDING, operation.id(), operation.status());
            }
            // This row lock serializes different idempotency keys through the check/create
            // decision. It is deliberately after replay: a lost HTTP response must be harmless.
            cases.lockForAdHocAction(caseId);
            c = cases.require(caseId);
            resolved = resolve(c, actionId);
            action = resolved.action();
            authorize(c.id(), action, actor);
        }
        if (c.state() != CaseState.ACTIVE) throw new CaseConflictException("case-not-active",
                "Ad-hoc actions are available only while the case is ACTIVE (was " + c.state() + ")", List.of());
        if (action.availabilityExpression() != null && !criteria.matches(action.availabilityExpression(), context(c))) {
            throw new CaseConflictException("ad-hoc-action-unavailable",
                    "Ad-hoc action '" + actionId + "' is not currently available", List.of());
        }
        // Form input is never an implicit engine payload.  A discretionary action can expose
        // engine data only through its contract-declared CASE_TO_ENGINE mappings; this keeps
        // process variables, canonical case data, search and the API from silently diverging.
        Map<String, Object> variables = mappedEngineVariables(c, action);
        Result result = engine.defersTaskMutations()
                ? requestRemote(c, action, variables, actor, stableKey)
                : executeEmbedded(c, action, variables);
        OffsetDateTime now = OffsetDateTime.now();
        if (!engine.defersTaskMutations()) {
            publisher.publish(new CaseEvent(CaseIds.newId(), publisher.engineId(), EventTypes.AD_HOC_ACTION_CONFIRMED,
                    c.id(), c.tenantId(), now, Map.of("actionId", action.id(), "type", type(action))));
            publisher.audit(c.id(), c.tenantId(), actor.userId(), "ad-hoc.confirmed", "AdHocAction", action.id(),
                    null, Map.of("type", type(action)));
        }
        return result;
    }

    /**
     * No discretionary action writes CM_TASK or CM_PLAN_ITEM directly.  Embedded engines emit
     * their normal lifecycle observations synchronously; remote engines use {@link #requestRemote}.
     */
    private Result executeEmbedded(CaseInstance c, ValidatedCaseContract.AdHocActionDefinition action,
                                   Map<String, Object> variables) {
        return switch (action) {
            case ValidatedCaseContract.TaskAction task -> {
                String target = target(c, task, null);
                EngineTaskRef engineTask = engine.createHumanTask(new HumanTaskRequest(c.id(), target,
                        taskName(task), null, task.candidateGroups(), task.formRef(), variables));
                yield new Result(task.id(), type(task), null, engineTask.engineTaskId(), null,
                        CaseTask.EngineSync.SYNCED, null, "CONFIRMED");
            }
            case ValidatedCaseContract.ProcessAction process -> {
                CaseDefinitionRelease release = exactRelease(c, process);
                EngineProcessRef ref = engine.startProcess(new StartProcessRequest(c.id(), null,
                        release.engineIdentity().processDefinitionId(), process.processDefinitionKey(),
                        release.engineTenantId(), variables, target(c, process, null)));
                yield new Result(process.id(), type(process), null, null, ref.processInstanceId(),
                        CaseTask.EngineSync.SYNCED, null, "CONFIRMED");
            }
            case ValidatedCaseContract.MessageAction message -> {
                engine.correlateMessage(new MessageCorrelationRequest(c.id(), message.messageName(), variables));
                yield new Result(message.id(), type(message), null, null, null,
                        CaseTask.EngineSync.SYNCED, null, "CONFIRMED");
            }
        };
    }

    private Result requestRemote(CaseInstance c, ValidatedCaseContract.AdHocActionDefinition action,
                                 Map<String, Object> variables, Actor actor, String idempotencyKey) {
        String stableTarget = target(c, action, idempotencyKey);
        RemoteActionSubmission submission = switch (action) {
            case ValidatedCaseContract.TaskAction task -> new RemoteActionSubmission(requiredOperations().submitAdHoc(c, task.id(),
                    EngineCommand.Type.CREATE_TASK, Map.of("planItemId", stableTarget,
                            "name", taskName(task), "assignee", "", "candidateGroups", task.candidateGroups(),
                            "formKey", task.formRef() == null ? "" : task.formRef(), "variables", variables),
                    stableTarget, c.version(), actor, idempotencyKey), null);
            case ValidatedCaseContract.ProcessAction process -> {
                CaseDefinitionRelease release = exactRelease(c, process);
                var link = processes.registerPendingExact(c.id(), stableTarget,
                        release.engineIdentity());
                EngineOperationService.Operation operation = requiredOperations().submitAdHoc(c, process.id(), EngineCommand.Type.START_PROCESS,
                        Map.of("planItemId", "", "selectionType", "ID",
                                "processDefinitionId", release.engineIdentity().processDefinitionId(),
                                "processDefinitionKey", process.processDefinitionKey(),
                                "tenantId", release.engineTenantId(), "variables", variables,
                                "correlationId", stableTarget),
                        release.engineIdentity().processDefinitionId(), c.version(), actor, idempotencyKey);
                yield new RemoteActionSubmission(operation, link.id());
            }
            case ValidatedCaseContract.MessageAction message -> new RemoteActionSubmission(requiredOperations().submitAdHoc(c, message.id(),
                    EngineCommand.Type.CORRELATE_MESSAGE, Map.of("messageName", message.messageName(),
                            "variables", variables), message.messageName(), c.version(), actor, idempotencyKey), null);
        };
        return new Result(action.id(), type(action), null, null, submission.linkedProcessId(), CaseTask.EngineSync.PENDING,
                submission.operation().id(), submission.operation().status());
    }

    private CaseDefinitionRelease exactRelease(CaseInstance c, ValidatedCaseContract.ProcessAction action) {
        if (action.orchestrationReleaseId() == null || action.orchestrationReleaseId().isBlank()) {
            throw invalid(c.caseDefKey(), "Process action '" + action.id() + "' requires orchestrationReleaseId for exact BPMN selection");
        }
        CaseDefinitionRelease target = releases.require(action.orchestrationReleaseId(), c.tenantId());
        if (target.kind() != ReleaseKind.ORCHESTRATION || target.status() != ReleaseStatus.ACTIVE
                || target.engineIdentity() == null || !action.processDefinitionKey().equals(target.engineProcessDefinitionKey())
                || !Objects.equals(c.tenantId(), target.engineTenantId())) {
            throw invalid(c.caseDefKey(), "Process action '" + action.id() + "' does not name an ACTIVE exact BPMN release for '" + action.processDefinitionKey() + "'");
        }
        return target;
    }

    private ResolvedAction resolve(CaseInstance c, String actionId) {
        CaseDefinitionVersionBinding binding = bindings.find(c.caseDefId()).orElseThrow(() -> invalid(c.caseDefKey(), "BPMN case has no release binding"));
        CaseDefinitionRelease release = releases.require(binding.contractReleaseId(), c.tenantId());
        if (!c.caseDefId().equals(binding.caseDefinitionId()) || !c.caseDefKey().equals(binding.caseDefinitionKey())
                || !Objects.equals(c.tenantId(), binding.tenantId())
                || (binding.status() != BindingStatus.ACTIVE && binding.status() != BindingStatus.RETIRED)
                || !binding.contractReleaseId().equals(release.id()) || !binding.contractSha256().equals(release.sha256())
                || release.kind() != ReleaseKind.CONTRACT || !c.caseDefKey().equals(release.definitionKey())
                || !Objects.equals(c.tenantId(), release.tenantId())) {
            throw new IllegalStateException("Published contract release does not match case '" + c.id() + "'");
        }
        ValidatedCaseContract contract = contracts.validate(c.caseDefKey(), release.content());
        var action = contract.action(actionId);
        if (action == null) throw new NotFoundException("AdHocAction", actionId);
        return new ResolvedAction(contract, action);
    }

    private void authorize(String caseId, ValidatedCaseContract.AdHocActionDefinition action, Actor actor) {
        Set<String> held = participants.rolesOf(caseId, actor.userId(), actor.groups());
        if (action.roles().isEmpty() || Collections.disjoint(held, action.roles())) {
            throw new org.casemgmt.error.AuthorizationDeniedException("Caller may not execute ad-hoc action '" + action.id() + "'");
        }
    }

    private void validateInput(ValidatedCaseContract contract, ValidatedCaseContract.AdHocActionDefinition action,
                               Map<String, Object> input) {
        if (action.formRef() == null) {
            if (!input.isEmpty()) throw invalidInput("Action has no declared form; submitted fields are not allowed");
            return;
        }
        var form = contract.forms().get(action.formRef());
        if (form == null) throw invalid(contract.key(), "Ad-hoc action '" + action.id() + "' references unknown form '" + action.formRef() + "'");
        Object declared = form.schema().get("properties");
        if (declared instanceof Map<?, ?> properties) {
            for (String key : input.keySet()) if (!properties.containsKey(key)) throw invalidInput("Undeclared action field '" + key + "'");
        } else if (!input.isEmpty()) throw invalidInput("Action form declares no fields");
        forms.validate(form.schema(), input);
    }

    private void validateCorrelation(CaseInstance c, ValidatedCaseContract.MessageAction action, Map<String, Object> input) {
        for (String key : action.correlationKeys()) if (!c.variables().containsKey(key) || !input.containsKey(key)
                || !Objects.equals(c.variables().get(key), input.get(key))) throw new CaseConflictException("message-correlation-mismatch",
                "Message action '" + action.id() + "' does not match required correlation '" + key + "'", List.of());
    }

    private Map<String, Object> mappedEngineVariables(CaseInstance c,
                                                       ValidatedCaseContract.AdHocActionDefinition action) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < action.mappings().size(); index++) {
            ValidatedCaseContract.MappingDefinition mapping = action.mappings().get(index);
            if (mapping.direction() != ValidatedCaseContract.MappingDirection.CASE_TO_ENGINE) {
                continue;
            }
            if (!c.variables().containsKey(mapping.source())) {
                if (mapping.required()) {
                    throw invalid(c.caseDefKey(), "Ad-hoc action '" + action.id()
                            + "' requires canonical field '" + mapping.source() + "'");
                }
                continue;
            }
            if (result.putIfAbsent(mapping.target(), c.variables().get(mapping.source())) != null) {
                throw invalid(c.caseDefKey(), "Ad-hoc action '" + action.id()
                        + "' declares duplicate engine mapping target '" + mapping.target() + "'");
            }
        }
        return Collections.unmodifiableMap(result);
    }

    private EngineOperationService requiredOperations() {
        if (operations == null) throw new IllegalStateException(
                "Remote ad-hoc actions require an EngineOperationService");
        return operations;
    }

    private static String target(CaseInstance c, ValidatedCaseContract.AdHocActionDefinition action,
                                 String idempotencyKey) {
        String stableKey = idempotencyKey == null || idempotencyKey.isBlank()
                ? "adhoc:" + c.id() + ":" + action.id() + ":" + c.version() : idempotencyKey;
        return "adhoc:" + action.id() + ":" + java.util.UUID.nameUUIDFromBytes(
                (c.id() + ":" + stableKey).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String actionKey(CaseInstance c, ValidatedCaseContract.AdHocActionDefinition action,
                                    String idempotencyKey) {
        return idempotencyKey == null || idempotencyKey.isBlank()
                ? "adhoc:" + c.id() + ":" + action.id() + ":" + c.version() : idempotencyKey;
    }

    private static String taskName(ValidatedCaseContract.TaskAction task) {
        return task.name() == null || task.name().isBlank() ? task.id() : task.name();
    }

    private EvaluationContext context(CaseInstance c) { return new EvaluationContext(Map.of("id", c.id(),
            "state", c.state().name(), "priority", c.priority().name()), c.variables(), Map.of()); }
    private static Map<String, Object> immutableInput(Map<String, Object> input) { return input == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(input)); }
    private static InvalidCaseDefinitionException invalid(String key, String message) { return new InvalidCaseDefinitionException(key, message); }
    private static FormValidationException invalidInput(String message) { return new FormValidationException(List.of(new FormValidationException.Violation("", message))); }
    private static String type(ValidatedCaseContract.AdHocActionDefinition action) { return switch (action) {
        case ValidatedCaseContract.TaskAction ignored -> "TASK";
        case ValidatedCaseContract.ProcessAction ignored -> "PROCESS";
        case ValidatedCaseContract.MessageAction ignored -> "MESSAGE";
    }; }
    private record ResolvedAction(ValidatedCaseContract contract, ValidatedCaseContract.AdHocActionDefinition action) { }
    private record RemoteActionSubmission(EngineOperationService.Operation operation,
                                           String linkedProcessId) { }
}
