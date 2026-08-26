package org.casemgmt.projection;

import java.time.OffsetDateTime;
import java.util.List;

public record TaskObservation(
        String caseId,
        String engineTaskId,
        String activityInstanceId,
        String activityId,
        String name,
        String eventName,
        String assignee,
        List<String> candidateGroups,
        String formKey,
        int priority,
        OffsetDateTime dueAt,
        OffsetDateTime engineUpdatedAt,
        OffsetDateTime observedAt) {
}
