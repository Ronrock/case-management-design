package org.casemgmt.domain;

import java.time.OffsetDateTime;
import java.util.List;

public record CaseTask(
        String id, String caseId, String planItemId, String engineTaskId,
        String name, String description, TaskState state,
        String assignee, String delegatedBy, List<String> candidateGroups,
        String formKey, int priority, OffsetDateTime dueAt, String outcome,
        EngineSync engineSync,
        long version,
        OffsetDateTime createdAt, OffsetDateTime updatedAt, OffsetDateTime completedAt) {

    /** PoC-only: remote mode cannot create the engine task in the local transaction (spec §3.5). */
    public enum EngineSync { PENDING, SYNCED, FAILED }
}
