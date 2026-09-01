package org.casemgmt.rest.dto;

import org.casemgmt.service.EngineOperationService;

import java.util.List;

/** Safe, tenant-scoped operation state. Command payloads and remote response bodies stay private. */
public record EngineOperationResponse(String id, String caseId, String commandType,
                                      String targetId, String status, long version,
                                      String errorCode, String summary,
                                      List<String> availableActions) {
    public static EngineOperationResponse of(EngineOperationService.Operation operation) {
        return new EngineOperationResponse(operation.id(), operation.caseId(),
                operation.commandType(), operation.targetId(), operation.status(),
                operation.version(), operation.safeErrorCode(), operation.safeSummary(),
                operation.availableActions());
    }
}
