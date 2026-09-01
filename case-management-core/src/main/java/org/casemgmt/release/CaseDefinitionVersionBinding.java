package org.casemgmt.release;

import org.casemgmt.orchestration.EngineDeploymentIdentity;
import org.casemgmt.orchestration.OrchestrationMode;

import java.time.OffsetDateTime;
import java.util.Objects;

public record CaseDefinitionVersionBinding(
        String caseDefinitionId,
        String caseDefinitionKey,
        String tenantId,
        String orchestrationReleaseId,
        String orchestrationSha256,
        String contractReleaseId,
        String contractSha256,
        String presentationReleaseId,
        String presentationSha256,
        ReleaseStatus deploymentStatus,
        OrchestrationMode orchestrationMode,
        BindingStatus status,
        EngineDeploymentIdentity engineIdentity,
        String failureDetail,
        OffsetDateTime boundAt,
        OffsetDateTime activatedAt,
        OffsetDateTime retiredAt,
        String boundBy) {

    public CaseDefinitionVersionBinding activate(
            CaseDefinitionRelease orchestration,
            CaseDefinitionRelease contract,
            CaseDefinitionRelease presentation,
            EngineDeploymentIdentity verifiedIdentity,
            OffsetDateTime activationTime) {
        status.transitionTo(BindingStatus.ACTIVE);
        requirePinned(orchestration, orchestrationReleaseId, orchestrationSha256,
                ReleaseKind.ORCHESTRATION);
        requirePinned(contract, contractReleaseId, contractSha256, ReleaseKind.CONTRACT);
        requirePinned(presentation, presentationReleaseId, presentationSha256,
                ReleaseKind.PRESENTATION);
        if (orchestrationMode != OrchestrationMode.BPMN) {
            throw new IllegalStateException("Only BPMN bindings carry an exact engine identity");
        }
        if (verifiedIdentity == null || !verifiedIdentity.equals(orchestration.engineIdentity())
                || !caseDefinitionKey.equals(verifiedIdentity.processDefinitionKey())
                || !Objects.equals(tenantId, verifiedIdentity.tenantId())) {
            throw new IllegalStateException(
                    "Binding activation requires the release's exact engine identity, key and tenant");
        }
        return new CaseDefinitionVersionBinding(caseDefinitionId, caseDefinitionKey, tenantId,
                orchestrationReleaseId, orchestrationSha256,
                contractReleaseId, contractSha256,
                presentationReleaseId, presentationSha256,
                ReleaseStatus.ACTIVE, orchestrationMode, BindingStatus.ACTIVE,
                verifiedIdentity, null, boundAt, activationTime, null, boundBy);
    }

    private void requirePinned(CaseDefinitionRelease release, String expectedId,
                               String expectedSha, ReleaseKind expectedKind) {
        if (!expectedId.equals(release.id()) || !expectedSha.equals(release.sha256())
                || release.kind() != expectedKind
                || !caseDefinitionKey.equals(release.definitionKey())
                || !Objects.equals(tenantId, release.tenantId())) {
            throw new IllegalStateException(
                    "Release '" + release.id() + "' does not match the immutable binding");
        }
        if (release.status() != ReleaseStatus.ACTIVE) {
            throw new IllegalStateException("Release '" + release.id() + "' is "
                    + release.status() + "; binding activation requires ACTIVE");
        }
    }
}
