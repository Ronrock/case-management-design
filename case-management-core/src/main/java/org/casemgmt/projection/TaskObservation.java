package org.casemgmt.projection;

import java.time.OffsetDateTime;
import java.util.List;

public record TaskObservation(
        String caseId,
        String processInstanceId,
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

    /** Compatibility constructor for direct adapters until Task 6 supplies process identity. */
    public TaskObservation(String caseId, String engineTaskId, String activityInstanceId,
                           String activityId, String name, String eventName, String assignee,
                           List<String> candidateGroups, String formKey, int priority,
                           OffsetDateTime dueAt, OffsetDateTime engineUpdatedAt,
                           OffsetDateTime observedAt) {
        this(caseId, null, engineTaskId, activityInstanceId, activityId, name, eventName,
                assignee, candidateGroups, formKey, priority, dueAt, engineUpdatedAt, observedAt);
    }
}
