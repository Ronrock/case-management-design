package org.casemgmt.domain;

import java.time.OffsetDateTime;
import org.casemgmt.projection.ProjectionStatus;

public record PlanItem(
        String id, String caseId, String planItemDefId,
        PlanItemType type, String name, PlanItemState state,
        String parentStageId, boolean adHoc, int repetitionNo,
        String engineTaskId, String processInstanceId, String terminationReason,
        long version,
        OffsetDateTime createdAt, OffsetDateTime updatedAt, OffsetDateTime endedAt,
        String engineActivityId, ProjectionStatus projectionStatus,
        OffsetDateTime lastEngineUpdateAt, OffsetDateTime lastProjectedAt) {

    public PlanItem(String id, String caseId, String planItemDefId, PlanItemType type, String name,
                    PlanItemState state, String parentStageId, boolean adHoc, int repetitionNo,
                    String engineTaskId, String processInstanceId, String terminationReason,
                    long version, OffsetDateTime createdAt, OffsetDateTime updatedAt,
                    OffsetDateTime endedAt) {
        this(id, caseId, planItemDefId, type, name, state, parentStageId, adHoc, repetitionNo,
                engineTaskId, processInstanceId, terminationReason, version, createdAt, updatedAt,
                endedAt, null, ProjectionStatus.CURRENT, null, null);
    }

    public PlanItem withState(PlanItemState newState) {
        return new PlanItem(id, caseId, planItemDefId, type, name, newState, parentStageId,
                adHoc, repetitionNo, engineTaskId, processInstanceId, terminationReason,
                version, createdAt, updatedAt, endedAt, engineActivityId, projectionStatus,
                lastEngineUpdateAt, lastProjectedAt);
    }
}
