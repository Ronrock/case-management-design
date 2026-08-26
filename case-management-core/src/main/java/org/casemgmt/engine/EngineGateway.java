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

    EngineTaskRef createHumanTask(HumanTaskRequest request);

    void claimTask(String engineTaskId, String userId);

    void completeTask(String engineTaskId, Map<String, Object> variables);

    EngineProcessRef startProcess(StartProcessRequest request);

    void cancelProcess(String processInstanceId, String reason);

    default void correlateMessage(MessageCorrelationRequest request) {
        throw new EngineException("Configured engine does not support message correlation");
    }

    List<EngineTaskRef> findTasks(EngineTaskQuery query);
}
