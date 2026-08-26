package org.casemgmt.engine;

import org.casemgmt.domain.CaseIds;
import org.casemgmt.repo.EngineCommandRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Remote-mode gateway (spec §3.5). Writes a command row in the caller's transaction instead of
 * calling the engine, so a rolled-back case change never leaves an orphan task on a remote
 * engine. The returned ids are locally minted (or null, where the caller has no id to mint);
 * {@link EngineCommandDispatcher} reconciles them once the engine confirms.
 */
public class OutboxEngineGateway implements EngineGateway {

    /**
     * Placeholder for commands whose {@link EngineGateway} method carries no case id at all
     * ({@link #claimTask}, {@link #completeTask}, {@link #cancelProcess} all take only an engine
     * task/process id, per the Task 10 interface). CM_ENGINE_COMMAND.CASE_ID_ is NOT NULL, so
     * this cannot simply be {@code ""}: Oracle silently normalises a zero-length VARCHAR2 bind
     * to SQL NULL on INSERT (confirmed directly — the original {@code caseId == null ? "" : caseId}
     * failed with ORA-01400 "cannot insert NULL into CASE_ID_" even though the Java value was a
     * non-null empty string), so an actually non-empty sentinel is required.
     */
    private static final String NO_CASE_ID = "-";

    private final EngineCommandRepository commands;
    private final Consumer<String> onEnqueued;

    public OutboxEngineGateway(EngineCommandRepository commands, Consumer<String> onEnqueued) {
        this.commands = commands;
        this.onEnqueued = onEnqueued;
    }

    @Override
    public EngineTaskRef createHumanTask(HumanTaskRequest request) {
        String commandId = CaseIds.newId();
        commands.enqueue(new EngineCommand(commandId, request.caseId(),
                EngineCommand.Type.CREATE_TASK,
                Map.of("planItemId", request.planItemId(), "name", request.name(),
                        "assignee", request.assignee() == null ? "" : request.assignee(),
                        "candidateGroups", request.candidateGroups(),
                        "formKey", request.formKey() == null ? "" : request.formKey(),
                        "variables", request.variables()),
                "PENDING", 0, OffsetDateTime.now(), null));
        onEnqueued.accept(commandId);
        return new EngineTaskRef(null, request.name(), request.assignee(),
                request.caseId(), OffsetDateTime.now());
    }

    @Override
    public void claimTask(String engineTaskId, String userId) {
        enqueue(EngineCommand.Type.CLAIM_TASK, null,
                Map.of("engineTaskId", engineTaskId, "userId", userId));
    }

    @Override
    public void completeTask(String engineTaskId, Map<String, Object> variables) {
        enqueue(EngineCommand.Type.COMPLETE_TASK, null,
                Map.of("engineTaskId", engineTaskId, "variables", variables == null ? Map.of() : variables));
    }

    @Override
    public EngineProcessRef startProcess(StartProcessRequest request) {
        // "planItemId" is guarded like createHumanTask's assignee/formKey above: unlike a human
        // task, a linked process legitimately has no plan item at all (an ad hoc process — see
        // LinkedProcessService's Javadoc), and Map.of() throws NPE on a null value, not just a
        // missing key. "correlationId" carries the CM_LINKED_PROCESS row id through so
        // EngineCommandDispatcher can report the engine's confirmation back against THAT row
        // instead of planItemId, which for an ad hoc process would have nothing to correlate on.
        enqueue(EngineCommand.Type.START_PROCESS, request.caseId(),
                Map.of("planItemId", request.planItemId() == null ? "" : request.planItemId(),
                        "processDefinitionKey", request.processDefinitionKey(),
                        "variables", request.variables() == null ? Map.of() : request.variables(),
                        "correlationId", request.correlationId() == null ? "" : request.correlationId()));
        return new EngineProcessRef(null, request.processDefinitionKey(), request.caseId());
    }

    @Override
    public void cancelProcess(String processInstanceId, String reason) {
        enqueue(EngineCommand.Type.CANCEL_PROCESS, null,
                Map.of("processInstanceId", processInstanceId, "reason", reason == null ? "" : reason));
    }

    @Override
    public void correlateMessage(MessageCorrelationRequest request) {
        enqueue(EngineCommand.Type.CORRELATE_MESSAGE, request.caseId(),
                Map.of("messageName", request.messageName(), "variables",
                        request.variables() == null ? Map.of() : request.variables()));
    }

    /**
     * Queries are NOT deferred: reading a remote engine synchronously is safe, and the worklist
     * is served from CM_TASK anyway.
     */
    @Override
    public List<EngineTaskRef> findTasks(EngineTaskQuery query) {
        return List.of();
    }

    private void enqueue(EngineCommand.Type type, String caseId, Map<String, Object> payload) {
        String id = CaseIds.newId();
        commands.enqueue(new EngineCommand(id, caseId == null ? NO_CASE_ID : caseId, type, payload,
                "PENDING", 0, OffsetDateTime.now(), null));
        onEnqueued.accept(id);
    }
}
