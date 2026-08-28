package org.casemgmt.observation;

import java.time.Instant;
import java.util.Map;

/** Lifecycle evidence for one engine user task. */
public record UserTaskObservation(
        String observationId,
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

    public UserTaskObservation {
        EngineObservation.validateIdentity(observationId, observationVersion, source, tenantId, caseId, processInstanceId, entityId,
                eventType, engineOccurredAt, receivedAt);
        attributes = EngineObservation.immutableAttributes(attributes);
    }

    public enum EventType { CREATED, ASSIGNED, CLAIMED, UNCLAIMED, COMPLETED, DELETED }
}
