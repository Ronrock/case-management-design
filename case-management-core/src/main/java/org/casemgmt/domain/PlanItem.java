package org.casemgmt.domain;

import java.time.OffsetDateTime;

public record PlanItem(
        String id, String caseId, String planItemDefId,
        PlanItemType type, String name, PlanItemState state,
        String parentStageId, boolean adHoc, int repetitionNo,
        String engineTaskId, String processInstanceId, String terminationReason,
        long version,
        OffsetDateTime createdAt, OffsetDateTime updatedAt, OffsetDateTime endedAt) {

    public PlanItem withState(PlanItemState newState) {
        return new PlanItem(id, caseId, planItemDefId, type, name, newState, parentStageId,
                adHoc, repetitionNo, engineTaskId, processInstanceId, terminationReason,
                version, createdAt, updatedAt, endedAt);
    }
}
