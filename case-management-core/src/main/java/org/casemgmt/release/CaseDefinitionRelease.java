package org.casemgmt.release;

import org.casemgmt.orchestration.EngineDeploymentIdentity;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Objects;

public record CaseDefinitionRelease(
        String id,
        String definitionKey,
        String tenantId,
        ReleaseKind kind,
        String mediaType,
        byte[] content,
        String sha256,
        ReleaseStatus status,
        String engineDeploymentId,
        String engineProcessDefinitionId,
        String engineProcessDefinitionKey,
        Integer engineProcessDefinitionVersion,
        String engineTenantId,
        String failureDetail,
        OffsetDateTime publishedAt,
        String publishedBy) {

    public CaseDefinitionRelease {
        content = content == null ? new byte[0] : Arrays.copyOf(content, content.length);
    }

    @Override
    public byte[] content() {
        return Arrays.copyOf(content, content.length);
    }

    /**
     * A newly published release, before anything has been checked or deployed.
     *
     * <p>This replaces a factory that constructed straight into {@link ReleaseStatus#ACTIVE}.
     * Storing bytes is not a statement that the artifact is usable — for an orchestration
     * release that is only true once an engine deployment has been verified behind it, which in
     * remote mode happens after the publishing request has already returned.
     */
    public static CaseDefinitionRelease draft(
            String id, String key, String tenantId, ReleaseKind kind, String mediaType,
            byte[] content, String sha256, String publishedBy) {
        return new CaseDefinitionRelease(id, key, tenantId, kind, mediaType, content, sha256,
                ReleaseStatus.DRAFT, null, null, null, null, null, null,
                OffsetDateTime.now(), publishedBy);
    }

    /**
     * The same release advanced to {@code next}, subject to {@link ReleaseStatus}'s guards.
     *
     * @throws IllegalStateException if the transition is not part of the lifecycle
     */
    public CaseDefinitionRelease withStatus(ReleaseStatus next) {
        return transitionTo(next, engineIdentity(), failureDetail);
    }

    public CaseDefinitionRelease transitionTo(
            ReleaseStatus next, EngineDeploymentIdentity identity, String failure) {
        ReleaseStatus transitioned = status.transitionTo(next);
        if (transitioned == ReleaseStatus.ACTIVE && kind == ReleaseKind.ORCHESTRATION
                && identity == null) {
            throw new IllegalStateException(
                    "An active orchestration release requires a verified engine identity");
        }
        if (identity != null && (!definitionKey.equals(identity.processDefinitionKey())
                || !Objects.equals(tenantId, identity.tenantId()))) {
            throw new IllegalStateException(
                    "Engine identity does not match the immutable release key and tenant");
        }
        return new CaseDefinitionRelease(id, definitionKey, tenantId, kind, mediaType, content,
                sha256, transitioned,
                identity == null ? null : identity.deploymentId(),
                identity == null ? null : identity.processDefinitionId(),
                identity == null ? null : identity.processDefinitionKey(),
                identity == null ? null : identity.processDefinitionVersion(),
                identity == null ? null : identity.tenantId(),
                failure, publishedAt, publishedBy);
    }

    public static CaseDefinitionRelease stored(
            String id, String key, String tenantId, ReleaseKind kind, String mediaType,
            byte[] content, String sha256, ReleaseStatus status, String engineDeploymentId,
            String failureDetail, String publishedBy) {
        return new CaseDefinitionRelease(id, key, tenantId, kind, mediaType, content, sha256,
                status, engineDeploymentId, null, null, null, null, failureDetail,
                OffsetDateTime.now(), publishedBy);
    }

    public static CaseDefinitionRelease storedWithEngineIdentity(
            String id, String key, String tenantId, ReleaseKind kind, String mediaType,
            byte[] content, String sha256, ReleaseStatus status,
            org.casemgmt.orchestration.EngineDeploymentIdentity identity,
            String failureDetail, String publishedBy) {
        return new CaseDefinitionRelease(id, key, tenantId, kind, mediaType, content, sha256,
                status,
                identity == null ? null : identity.deploymentId(),
                identity == null ? null : identity.processDefinitionId(),
                identity == null ? null : identity.processDefinitionKey(),
                identity == null ? null : identity.processDefinitionVersion(),
                identity == null ? null : identity.tenantId(),
                failureDetail, OffsetDateTime.now(), publishedBy);
    }

    public org.casemgmt.orchestration.EngineDeploymentIdentity engineIdentity() {
        if (engineDeploymentId == null || engineProcessDefinitionId == null
                || engineProcessDefinitionKey == null
                || engineProcessDefinitionVersion == null) {
            return null;
        }
        return new org.casemgmt.orchestration.EngineDeploymentIdentity(engineDeploymentId,
                engineProcessDefinitionId, engineProcessDefinitionKey,
                engineProcessDefinitionVersion, engineTenantId);
    }
}
