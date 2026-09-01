package org.casemgmt.domain;

import java.time.OffsetDateTime;
import java.util.List;
import org.casemgmt.projection.ProjectionStatus;

public record CaseTask(
        String id, String caseId, String planItemId, String engineTaskId,
        String name, String description, TaskState state,
        String assignee, String delegatedBy, List<String> candidateGroups,
        String formKey, int priority, OffsetDateTime dueAt, String outcome,
        EngineSync engineSync,
        long version,
        OffsetDateTime createdAt, OffsetDateTime updatedAt, OffsetDateTime completedAt,
        ProjectionStatus projectionStatus, OffsetDateTime lastEngineUpdateAt,
        OffsetDateTime lastProjectedAt) {

    public CaseTask(String id, String caseId, String planItemId, String engineTaskId,
                    String name, String description, TaskState state, String assignee,
                    String delegatedBy, List<String> candidateGroups, String formKey, int priority,
                    OffsetDateTime dueAt, String outcome, EngineSync engineSync, long version,
                    OffsetDateTime createdAt, OffsetDateTime updatedAt,
                    OffsetDateTime completedAt) {
        this(id, caseId, planItemId, engineTaskId, name, description, state, assignee,
                delegatedBy, candidateGroups, formKey, priority, dueAt, outcome, engineSync,
                version, createdAt, updatedAt, completedAt, ProjectionStatus.CURRENT, null, null);
    }

    /** PoC-only: remote mode cannot create the engine task in the local transaction (spec §3.5). */
    public enum EngineSync { PENDING, SYNCED, FAILED }
}
