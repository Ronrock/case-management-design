package org.casemgmt.projection;

import java.time.OffsetDateTime;

public record ActivityObservation(
        String caseId,
        String activityInstanceId,
        String activityId,
        String name,
        Kind kind,
        String milestoneId,
        String eventName,
        OffsetDateTime engineUpdatedAt,
        OffsetDateTime observedAt) {

    public enum Kind { STAGE, MILESTONE }
}
