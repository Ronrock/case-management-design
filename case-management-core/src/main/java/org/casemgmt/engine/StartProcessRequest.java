package org.casemgmt.engine;

import java.util.Map;

/**
 * {@code correlationId} is the caller's OWN row id — e.g. {@code CM_LINKED_PROCESS.ID_} for
 * {@code LinkedProcessService#start} — not anything the engine knows about. In embedded/remote
 * synchronous mode it is unused: the real {@link EngineProcessRef#processInstanceId()} comes back
 * on the same call. In outbox mode ({@code OutboxEngineGateway}) it travels through the enqueued
 * {@code CM_ENGINE_COMMAND} payload so {@code EngineCommandDispatcher} can report the real id back
 * against the correct row once the engine confirms — {@code planItemId} cannot serve this purpose
 * because a linked process started ad hoc (not as a CMMN {@code PROCESS_TASK}) legitimately has
 * none, and even a plan-item-backed process still needs a 1:1 key back to the specific
 * {@code CM_LINKED_PROCESS} row, not the plan item, since the plan item does not identify the
 * outbox command the way a task's plan item does (a human task has at most one open engine task
 * per plan item; a case can start more than one linked process).
 *
 * <p>The 4-arg constructor defaults {@code correlationId} to {@code null} for every call site that
 * doesn't need it (both {@link org.casemgmt.engine.EngineGateway} conformance tests, and any
 * direct/synchronous gateway call that never goes through the outbox).
 */
public record StartProcessRequest(String caseId, String planItemId,
                                  String processDefinitionKey, Map<String, Object> variables,
                                  String correlationId) {

    public StartProcessRequest(String caseId, String planItemId, String processDefinitionKey,
                               Map<String, Object> variables) {
        this(caseId, planItemId, processDefinitionKey, variables, null);
    }
}
