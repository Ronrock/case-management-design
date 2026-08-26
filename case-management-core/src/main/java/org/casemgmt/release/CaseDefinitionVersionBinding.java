package org.casemgmt.release;

import java.time.OffsetDateTime;

public record CaseDefinitionVersionBinding(
        String caseDefinitionId,
        String orchestrationReleaseId,
        String orchestrationSha256,
        String contractReleaseId,
        String contractSha256,
        String presentationReleaseId,
        String presentationSha256,
        ReleaseStatus deploymentStatus,
        OffsetDateTime boundAt,
        String boundBy) {
}
