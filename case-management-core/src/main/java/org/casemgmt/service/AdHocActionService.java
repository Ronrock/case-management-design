package org.casemgmt.service;

import org.casemgmt.domain.*;
import org.casemgmt.engine.*;
import org.casemgmt.error.CaseConflictException;
import org.casemgmt.error.InvalidCaseDefinitionException;
import org.casemgmt.error.NotFoundException;
import org.casemgmt.event.CaseEvent;
import org.casemgmt.event.EventPublisher;
import org.casemgmt.event.EventTypes;
import org.casemgmt.release.CaseDefinitionRelease;
import org.casemgmt.repo.*;
import org.casemgmt.rules.CriterionEvaluator;
import org.casemgmt.rules.EvaluationContext;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.*;

/** Executes only actions declared by the contract pinned to the running case. */
public class AdHocActionService {

    public record Result(String actionId, String type, String planItemId, String taskId,
                         String linkedProcessId, CaseTask.EngineSync engineSync) { }

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

    public AdHocActionService(CaseRepository cases,
                              CaseDefinitionVersionBindingRepository bindings,
                              CaseDefinitionReleaseRepository releases,
                              ParticipantRepository participants, PlanItemRepository planItems,
                              CaseTaskRepository tasks, LinkedProcessService processes,
                              EngineGateway engine, CriterionEvaluator criteria,
                              EventPublisher publisher) {
        this.cases = cases;
        this.bindings = bindings;
        this.releases = releases;
        this.participants = participants;
        this.planItems = planItems;
        this.tasks = tasks;
        this.processes = processes;
        this.engine = engine;
        this.criteria = criteria;
        this.publisher = publisher;
    }

    @Transactional
    public Result execute(String caseId, String actionId, long expectedVersion,
                          Map<String, Object> input, Actor actor) {
        CaseInstance c = cases.require(caseId);
        if (c.version() != expectedVersion) {
            throw new CaseConflictException("version-conflict",
                    "Case version is " + c.version() + "; expected " + expectedVersion,
                    List.of());
        }
        Action action = requireAction(c, actionId);
        authorize(caseId, action, actor);
        if (action.availabilityExpression != null && !criteria.matches(
                action.availabilityExpression, context(c))) {
            throw new CaseConflictException("ad-hoc-action-unavailable",
                    "Ad-hoc action '" + actionId + "' is not currently available", List.of());
        }
        Map<String, Object> variables = input == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(input));
        Result result = switch (action.type) {
            case "TASK" -> createTask(c, action, variables);
            case "PROCESS" -> startProcess(c, action, variables, actor);
            case "MESSAGE" -> correlateMessage(c, action, variables);
            default -> throw invalid(c.caseDefKey(), "Unsupported ad-hoc action type '"
                    + action.type + "'");
        };
        OffsetDateTime now = OffsetDateTime.now();
        publisher.publish(new CaseEvent(CaseIds.newId(), publisher.engineId(),
                EventTypes.AD_HOC_ACTION_EXECUTED, c.id(), c.tenantId(), now,
                Map.of("actionId", action.id, "type", action.type,
                        "planItemId", result.planItemId)));
        publisher.audit(c.id(), c.tenantId(), actor.userId(), "ad-hoc.execute",
                "AdHocAction", action.id, null, Map.of("type", action.type,
                        "planItemId", result.planItemId));
        return result;
    }

    private Result createTask(CaseInstance c, Action action, Map<String, Object> variables) {
        String planItemId = insertPlanItem(c, action, PlanItemType.HUMAN_TASK,
                PlanItemState.ACTIVE);
        EngineTaskRef engineTask = engine.createHumanTask(new HumanTaskRequest(c.id(), planItemId,
                action.name, null, action.candidateGroups, action.formRef, variables));
        CaseTask.EngineSync sync = engineTask.engineTaskId() == null
                ? CaseTask.EngineSync.PENDING : CaseTask.EngineSync.SYNCED;
        String taskId = CaseIds.newId();
        OffsetDateTime now = OffsetDateTime.now();
        tasks.insert(new CaseTask(taskId, c.id(), planItemId, engineTask.engineTaskId(),
                action.name, null, TaskState.OPEN, null, null, action.candidateGroups,
                action.formRef, 50, null, null, sync, 0, now, now, null));
        if (engineTask.engineTaskId() != null) {
            planItems.bindEngineTask(planItemId, engineTask.engineTaskId());
        }
        return new Result(action.id, action.type, planItemId, taskId, null, sync);
    }

    private Result startProcess(CaseInstance c, Action action, Map<String, Object> variables,
                                Actor actor) {
        if (action.processDefinitionKey == null) {
            throw invalid(c.caseDefKey(), "Process action '" + action.id
                    + "' requires processDefinitionKey");
        }
        String planItemId = insertPlanItem(c, action, PlanItemType.PROCESS_TASK,
                PlanItemState.ACTIVE);
        LinkedProcessRepository.LinkedProcessRow process = processes.start(c.id(), planItemId,
                action.processDefinitionKey, variables, actor);
        return new Result(action.id, action.type, planItemId, null, process.id(),
                process.engineSync());
    }

    private Result correlateMessage(CaseInstance c, Action action, Map<String, Object> variables) {
        if (action.messageName == null) {
            throw invalid(c.caseDefKey(), "Message action '" + action.id + "' requires messageName");
        }
        String planItemId = insertPlanItem(c, action, PlanItemType.PROCESS_TASK,
                PlanItemState.COMPLETED);
        engine.correlateMessage(new MessageCorrelationRequest(c.id(), action.messageName, variables));
        return new Result(action.id, action.type, planItemId, null, null,
                engine instanceof OutboxEngineGateway
                        ? CaseTask.EngineSync.PENDING : CaseTask.EngineSync.SYNCED);
    }

    private String insertPlanItem(CaseInstance c, Action action, PlanItemType type,
                                  PlanItemState state) {
        String id = CaseIds.newId();
        OffsetDateTime now = OffsetDateTime.now();
        planItems.insert(new PlanItem(id, c.id(), "adhoc:" + action.id, type, action.name,
                state, null, true, 1, null, null, null, 0, now, now,
                state.isEnded() ? now : null));
        return id;
    }

    private void authorize(String caseId, Action action, Actor actor) {
        Set<String> held = participants.rolesOf(caseId, actor.userId(), actor.groups());
        if (action.roles.isEmpty() || Collections.disjoint(held, action.roles)) {
            throw new org.casemgmt.error.AuthorizationDeniedException(
                    "Caller may not execute ad-hoc action '" + action.id + "'");
        }
    }

    @SuppressWarnings("unchecked")
    private Action requireAction(CaseInstance c, String actionId) {
        var binding = bindings.find(c.caseDefId())
                .orElseThrow(() -> invalid(c.caseDefKey(), "BPMN case has no release binding"));
        CaseDefinitionRelease contract = releases.require(binding.contractReleaseId(), c.tenantId());
        Map<String, Object> doc = JsonCodec.toMap(new String(contract.content(),
                java.nio.charset.StandardCharsets.UTF_8));
        Object raw = doc.get("adHocActions");
        List<Map<String, Object>> actions;
        if (raw instanceof List<?> list) {
            actions = list.stream().filter(Map.class::isInstance)
                    .map(item -> (Map<String, Object>) item).toList();
        } else if (raw instanceof Map<?, ?> map) {
            actions = map.entrySet().stream().filter(entry -> entry.getValue() instanceof Map)
                    .map(entry -> {
                        Map<String, Object> value = new LinkedHashMap<>((Map<String, Object>) entry.getValue());
                        value.putIfAbsent("id", String.valueOf(entry.getKey()));
                        return value;
                    }).toList();
        } else {
            actions = List.of();
        }
        return actions.stream().map(Action::from).filter(action -> action.id.equals(actionId))
                .findFirst().orElseThrow(() -> new NotFoundException("AdHocAction", actionId));
    }

    private EvaluationContext context(CaseInstance c) {
        return new EvaluationContext(Map.of("id", c.id(), "state", c.state().name(),
                "priority", c.priority().name()), c.variables(), Map.of());
    }

    private static InvalidCaseDefinitionException invalid(String key, String message) {
        return new InvalidCaseDefinitionException(key, message);
    }

    private record Action(String id, String type, String name, List<String> roles,
                          String formRef, List<String> candidateGroups,
                          String processDefinitionKey, String messageName,
                          String availabilityExpression) {
        static Action from(Map<String, Object> value) {
            String id = String.valueOf(value.get("id"));
            return new Action(id, String.valueOf(value.get("type")).toUpperCase(Locale.ROOT),
                    String.valueOf(value.getOrDefault("name", id)), strings(value.get("roles")),
                    text(value.getOrDefault("formRef", value.get("formKey"))),
                    strings(value.get("candidateGroups")), text(value.get("processDefinitionKey")),
                    text(value.get("messageName")), text(value.get("availabilityExpression")));
        }

        private static List<String> strings(Object raw) {
            return raw instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of();
        }

        private static String text(Object raw) {
            return raw == null || raw.toString().isBlank() ? null : raw.toString();
        }
    }
}
