package org.casemgmt.engine;

import java.util.List;
import java.util.Map;

/**
 * Everything the case service needs from a BPMN engine — and nothing else.
 *
 * Implementations live in case-management-engine-embedded (in-process Operaton Java API)
 * and case-management-engine-remote (engine-rest over HTTP). Core code depends only on
 * this interface, which is what allows either deployment mode (spec §3.4).
 */
public interface EngineGateway {

    /** True only when starts synchronously emit authoritative lifecycle observations. */
    default boolean emitsSynchronousLifecycleObservations() {
        return false;
    }

    /**
     * True when task mutations are accepted as durable remote commands rather than completed in
     * the request transaction. Callers must then leave their confirmed projections untouched
     * until command evidence is confirmed by the common lifecycle handler.
     */
    default boolean defersTaskMutations() {
        return false;
    }

    EngineTaskRef createHumanTask(HumanTaskRequest request);

    void claimTask(String engineTaskId, String userId);

    void completeTask(String engineTaskId, Map<String, Object> variables);

    EngineProcessRef startProcess(StartProcessRequest request);

    /** Explicit latest-by-key path for non-root linked-process starts. */
    default EngineProcessRef startProcessByKey(StartProcessByKeyRequest request) {
        throw new EngineException("Configured engine does not support start by key");
    }

    void cancelProcess(String processInstanceId, String reason);

    default void correlateMessage(MessageCorrelationRequest request) {
        throw new EngineException("Configured engine does not support message correlation");
    }

    List<EngineTaskRef> findTasks(EngineTaskQuery query);
}
