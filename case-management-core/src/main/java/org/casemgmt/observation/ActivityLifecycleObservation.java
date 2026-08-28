package org.casemgmt.observation;

import java.time.Instant;
import java.util.Map;

/** Lifecycle evidence for one BPMN activity instance. */
public record ActivityLifecycleObservation(
        int observationVersion,
        String source,
        String tenantId,
        String caseId,
        String processInstanceId,
        String entityId,
        Long entityRevision,
        EventType eventType,
        Instant engineOccurredAt,
        Instant receivedAt,
        Map<String, Object> attributes) implements EngineObservation {

    public ActivityLifecycleObservation {
        EngineObservation.validateIdentity(observationVersion, source, caseId, processInstanceId, entityId,
                eventType, engineOccurredAt, receivedAt);
        attributes = EngineObservation.immutableAttributes(attributes);
    }

    public enum EventType { STARTED, COMPLETED, CANCELLED }
}
