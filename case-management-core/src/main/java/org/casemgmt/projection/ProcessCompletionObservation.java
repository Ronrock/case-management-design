package org.casemgmt.projection;

import java.time.OffsetDateTime;

public record ProcessCompletionObservation(
        String caseId,
        String processInstanceId,
        String processDefinitionKey,
        String endState,
        OffsetDateTime engineUpdatedAt,
        OffsetDateTime observedAt) {
}
