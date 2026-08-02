package org.casemgmt.engine;

import org.casemgmt.domain.CaseTask;
import org.casemgmt.repo.EngineCommandRepository;

import java.util.List;
import java.util.Map;

/**
 * Drains the engine command outbox against the real (remote) gateway and reports the resulting
 * sync state back onto CM_TASK (for {@code CREATE_TASK}) or CM_LINKED_PROCESS (for {@code
 * START_PROCESS}), so {@code availableActions} can withhold {@code claim} until the engine
 * actually has the task, and a linked process's {@code PROC_INST_ID_} can be updated from its
 * locally-minted placeholder to the engine's real id once confirmed.
 *
 * <p><b>Timeout assumption, for Task 25 (the production {@code RestClient} bean):</b> this
 * dispatcher's whole retry-versus-dead-letter decision depends on {@link EngineGateway} calls
 * either succeeding or throwing {@link EngineException} within a bounded time. Task 12's review
 * already flagged that the {@code RestClient} backing the remote gateway has no connect/read
 * timeout configured — a remote engine that is up but hung produces no exception at all, so
 * {@link #drainOnce} blocks forever on that one command instead of retrying or dead-lettering it,
 * and every command behind it in the same batch never gets a chance to run. Whoever wires the
 * production {@code RestClient} MUST set both a connect and a read timeout; this class has no way
 * to defend against an unbounded delegate call from the outside.
 */
public class EngineCommandDispatcher {

    /**
     * Callback: (correlation key, sync state, engine id). For {@code CREATE_TASK} the correlation
     * key is {@code planItemId} (a human task is always backed by exactly one plan item). For
     * {@code START_PROCESS} it is the {@code CM_LINKED_PROCESS} row id ({@code correlationId} in
     * the enqueued payload, originally {@link StartProcessRequest#correlationId()} — Task 18
     * review round 2: {@code planItemId} cannot serve this purpose because an ad hoc linked
     * process legitimately has none).
     */
    public interface SyncReporter {
        void report(String correlationKey, CaseTask.EngineSync sync, String engineId);
    }

    private final EngineCommandRepository commands;
    private final EngineGateway delegate;
    private final SyncReporter syncReporter;

    public EngineCommandDispatcher(EngineCommandRepository commands, EngineGateway delegate,
                                   SyncReporter syncReporter) {
        this.commands = commands;
        this.delegate = delegate;
        this.syncReporter = syncReporter;
    }

    public int drainOnce() {
        List<EngineCommand> due = commands.claimDue(50);
        for (EngineCommand command : due) {
            try {
                execute(command);
                commands.markDone(command.id());
            } catch (RuntimeException e) {
                if (EngineCommand.exhausted(command.attempts())) {
                    commands.markDead(command.id(), e.getMessage());
                    reportFailure(command);
                } else {
                    commands.markRetry(command.id(), e.getMessage(),
                            EngineCommand.nextAttempt(command.attempts()));
                }
            }
        }
        return due.size();
    }

    private void execute(EngineCommand command) {
        Map<String, Object> p = command.payload();
        switch (command.type()) {
            case CREATE_TASK -> {
                EngineTaskRef ref = delegate.createHumanTask(new HumanTaskRequest(
                        command.caseId(), str(p, "planItemId"), str(p, "name"),
                        blankToNull(str(p, "assignee")), strings(p.get("candidateGroups")),
                        blankToNull(str(p, "formKey")), map(p.get("variables"))));
                syncReporter.report(str(p, "planItemId"), CaseTask.EngineSync.SYNCED, ref.engineTaskId());
            }
            case CLAIM_TASK -> delegate.claimTask(str(p, "engineTaskId"), str(p, "userId"));
            case COMPLETE_TASK -> delegate.completeTask(str(p, "engineTaskId"), map(p.get("variables")));
            case START_PROCESS -> {
                EngineProcessRef ref = delegate.startProcess(new StartProcessRequest(
                        command.caseId(), blankToNull(str(p, "planItemId")),
                        str(p, "processDefinitionKey"), map(p.get("variables"))));
                syncReporter.report(str(p, "correlationId"), CaseTask.EngineSync.SYNCED,
                        ref.processInstanceId());
            }
            case CANCEL_PROCESS -> delegate.cancelProcess(str(p, "processInstanceId"), str(p, "reason"));
        }
    }

    /**
     * Reports {@code FAILED} once a command is dead-lettered, so whatever row is waiting on it
     * does not stay {@code PENDING} forever. {@code CREATE_TASK} and {@code START_PROCESS} use
     * different keys in their payload ({@code planItemId} vs. {@code correlationId} — see {@link
     * SyncReporter}'s Javadoc), so both are checked; a command only ever populates one of them,
     * and {@code blankToNull} guards the {@code ""} sentinel {@link OutboxEngineGateway} writes
     * for an absent value (an ad hoc linked process's {@code planItemId}, in particular) from
     * being reported as a real correlation key.
     */
    private void reportFailure(EngineCommand command) {
        Map<String, Object> p = command.payload();
        String planItemId = blankToNull(str(p, "planItemId"));
        if (planItemId != null) {
            syncReporter.report(planItemId, CaseTask.EngineSync.FAILED, null);
        }
        String correlationId = blankToNull(str(p, "correlationId"));
        if (correlationId != null) {
            syncReporter.report(correlationId, CaseTask.EngineSync.FAILED, null);
        }
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? null : v.toString();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<String> strings(Object o) {
        return o instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of();
    }
}
