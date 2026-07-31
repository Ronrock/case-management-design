package org.casemgmt.engine.embedded;

import org.casemgmt.engine.*;
import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.TaskService;
import org.operaton.bpm.engine.task.Task;
import org.operaton.bpm.engine.task.TaskQuery;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * In-process gateway. Runs inside the same transaction as the case mutation
 * (spec §3.5 embedded mode), so a rolled-back case change also rolls back the
 * engine task it created.
 */
public class EmbeddedEngineGateway implements EngineGateway {

    /** Process/task variable carrying the owning case. Also the process business key. */
    public static final String CASE_ID_VARIABLE = "caseId";
    private static final String PLAN_ITEM_VARIABLE = "planItemId";

    private final TaskService taskService;
    private final RuntimeService runtimeService;

    public EmbeddedEngineGateway(TaskService taskService, RuntimeService runtimeService) {
        this.taskService = taskService;
        this.runtimeService = runtimeService;
    }

    @Override
    public EngineTaskRef createHumanTask(HumanTaskRequest request) {
        Task task = taskService.newTask();
        task.setName(request.name());
        if (request.assignee() != null) {
            task.setAssignee(request.assignee());
        }
        taskService.saveTask(task);

        if (request.candidateGroups() != null) {
            for (String group : request.candidateGroups()) {
                taskService.addCandidateGroup(task.getId(), group);
            }
        }

        // Standalone tasks (created via newTask(), not attached to a process instance) have no
        // execution to hold "process" variables. setVariables()/setVariable() implicitly land
        // in the task's own local scope for such a task (there is no outer scope to promote
        // to), so taskVariableValueEquals() happens to match either way. setVariablesLocal()
        // is used here anyway to make that scoping explicit and correct by construction rather
        // than by an implicit degrade-to-local behaviour of the plain setters — it is also
        // what still works correctly if createHumanTask is ever used for a task that IS
        // attached to an execution. processVariableValueEquals() would require an execution
        // and silently match nothing for a standalone task either way.
        Map<String, Object> variables = new HashMap<>(
                request.variables() == null ? Map.of() : request.variables());
        variables.put(CASE_ID_VARIABLE, request.caseId());
        variables.put(PLAN_ITEM_VARIABLE, request.planItemId());
        taskService.setVariablesLocal(task.getId(), variables);

        return toRef(taskService.createTaskQuery().taskId(task.getId()).singleResult(),
                request.caseId());
    }

    @Override
    public void claimTask(String engineTaskId, String userId) {
        try {
            taskService.claim(engineTaskId, userId);
        } catch (ProcessEngineException e) {
            throw new EngineException("Could not claim task " + engineTaskId, e);
        }
    }

    @Override
    public void completeTask(String engineTaskId, Map<String, Object> variables) {
        try {
            taskService.complete(engineTaskId, variables == null ? Map.of() : variables);
        } catch (ProcessEngineException e) {
            throw new EngineException("Could not complete task " + engineTaskId, e);
        }
    }

    @Override
    public EngineProcessRef startProcess(StartProcessRequest request) {
        Map<String, Object> variables = new HashMap<>(
                request.variables() == null ? Map.of() : request.variables());
        variables.put(CASE_ID_VARIABLE, request.caseId());
        variables.put(PLAN_ITEM_VARIABLE, request.planItemId());
        try {
            var instance = runtimeService.startProcessInstanceByKey(
                    request.processDefinitionKey(), request.caseId(), variables);
            return new EngineProcessRef(instance.getId(), request.processDefinitionKey());
        } catch (ProcessEngineException e) {
            throw new EngineException(
                    "Could not start process " + request.processDefinitionKey(), e);
        }
    }

    @Override
    public void cancelProcess(String processInstanceId, String reason) {
        try {
            runtimeService.deleteProcessInstance(processInstanceId, reason);
        } catch (ProcessEngineException e) {
            throw new EngineException("Could not cancel process " + processInstanceId, e);
        }
    }

    @Override
    public List<EngineTaskRef> findTasks(EngineTaskQuery query) {
        TaskQuery q = taskService.createTaskQuery();
        if (query.assignee() != null) {
            q = q.taskAssignee(query.assignee());
        }
        if (query.candidateGroups() != null && !query.candidateGroups().isEmpty()) {
            q = q.taskCandidateGroupIn(query.candidateGroups()).includeAssignedTasks();
        }
        if (query.caseId() != null) {
            // A task's caseId variable can live in either of two scopes depending on how the
            // task came to exist: createHumanTask() sets it as a *local* task variable (a
            // standalone task has no execution to promote it to), while startProcess() sets
            // it as a *process* variable on the execution, which the BPMN's own user tasks
            // (e.g. "Wait" in test-process.bpmn) then inherit. taskVariableValueEquals() alone
            // only matches the former; processVariableValueEquals() alone only matches the
            // latter. Querying only one silently drops the other kind of task from every
            // caseId-scoped worklist lookup, so both are queried via .or()/.endOr().
            q = q.or()
                    .taskVariableValueEquals(CASE_ID_VARIABLE, query.caseId())
                    .processVariableValueEquals(CASE_ID_VARIABLE, query.caseId())
                    .endOr();
        }
        return q.list().stream()
                .limit(query.maxResults() <= 0 ? 50 : query.maxResults())
                .map(t -> toRef(t, caseIdOf(t)))
                .toList();
    }

    private String caseIdOf(Task task) {
        Object value = taskService.getVariableLocal(task.getId(), CASE_ID_VARIABLE);
        if (value == null) {
            value = taskService.getVariable(task.getId(), CASE_ID_VARIABLE);
        }
        return value == null ? null : value.toString();
    }

    private EngineTaskRef toRef(Task task, String caseId) {
        return new EngineTaskRef(task.getId(), task.getName(), task.getAssignee(), caseId,
                task.getCreateTime() == null ? null
                        : OffsetDateTime.ofInstant(task.getCreateTime().toInstant(), ZoneId.systemDefault()));
    }
}
