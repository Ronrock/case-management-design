package org.casemgmt.domain;

import java.time.OffsetDateTime;
import java.util.Map;

public record CaseInstance(
        String id, String engineId, String tenantId,
        String caseDefId, String caseDefKey, int caseDefVersion,
        String businessKey, String title,
        CaseState state, CasePriority priority,
        String assignee, String queueId, String initiator,
        String slaStatus, String outcome, String cancelReason,
        Map<String, Object> variables,
        long version,
        OffsetDateTime createdAt, OffsetDateTime updatedAt, OffsetDateTime closedAt) {

    public CaseInstance withState(CaseState newState) {
        return new CaseInstance(id, engineId, tenantId, caseDefId, caseDefKey, caseDefVersion,
                businessKey, title, newState, priority, assignee, queueId, initiator,
                slaStatus, outcome, cancelReason, variables, version, createdAt, updatedAt, closedAt);
    }

    public CaseInstance withVariables(Map<String, Object> newVariables) {
        return new CaseInstance(id, engineId, tenantId, caseDefId, caseDefKey, caseDefVersion,
                businessKey, title, state, priority, assignee, queueId, initiator,
                slaStatus, outcome, cancelReason, newVariables, version, createdAt, updatedAt, closedAt);
    }
}
