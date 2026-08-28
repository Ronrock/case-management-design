package org.casemgmt.observation;

import java.time.Instant;
import java.util.Map;

/** Lifecycle evidence for one process instance. */
public record ProcessObservation(
        String observationId,
        int observationVersion,
        String source,
        String engineId,
        String tenantId,
        String caseId,
        String processInstanceId,
        String entityId,
        Long entityRevision,
        EventType eventType,
        Instant engineOccurredAt,
        Instant receivedAt,
        Map<String, Object> attributes) implements EngineObservation {

    public ProcessObservation {
        EngineObservation.validateIdentity(observationId, observationVersion, source, engineId, tenantId, caseId, processInstanceId, entityId,
                eventType, engineOccurredAt, receivedAt);
        attributes = EngineObservation.normalizedAttributes(engineId, attributes);
    }

    public ProcessObservation(String observationId, int observationVersion, String source,
                              String tenantId, String caseId, String processInstanceId,
                              String entityId, Long entityRevision, EventType eventType,
                              Instant engineOccurredAt, Instant receivedAt,
                              Map<String, Object> attributes) {
        this(observationId, observationVersion, source, EngineObservation.legacyEngineId(attributes),
                tenantId, caseId, processInstanceId, entityId, entityRevision, eventType,
                engineOccurredAt, receivedAt, attributes);
    }

    public enum EventType { STARTED, COMPLETED, TERMINATED, SUSPENDED, RESUMED }
}
