package org.casemgmt.projection;

import java.time.OffsetDateTime;

public record ActivityObservation(
        String caseId,
        String processInstanceId,
        String activityInstanceId,
        String activityId,
        String name,
        Kind kind,
        String milestoneId,
        String eventName,
        OffsetDateTime engineUpdatedAt,
        OffsetDateTime observedAt) {

    public enum Kind { STAGE, MILESTONE }

    /** Compatibility constructor for direct adapters until Task 6 supplies process identity. */
    public ActivityObservation(String caseId, String activityInstanceId, String activityId,
                               String name, Kind kind, String milestoneId, String eventName,
                               OffsetDateTime engineUpdatedAt, OffsetDateTime observedAt) {
        this(caseId, null, activityInstanceId, activityId, name, kind, milestoneId, eventName,
                engineUpdatedAt, observedAt);
    }
}
