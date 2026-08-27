package org.casemgmt.release;

import java.time.OffsetDateTime;
import java.util.Arrays;

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
                ReleaseStatus.DRAFT, null, null, OffsetDateTime.now(), publishedBy);
    }

    /**
     * The same release advanced to {@code next}, subject to {@link ReleaseStatus}'s guards.
     *
     * @throws IllegalStateException if the transition is not part of the lifecycle
     */
    public CaseDefinitionRelease withStatus(ReleaseStatus next) {
        return new CaseDefinitionRelease(id, definitionKey, tenantId, kind, mediaType, content,
                sha256, status.transitionTo(next), engineDeploymentId, failureDetail, publishedAt,
                publishedBy);
    }

    public static CaseDefinitionRelease stored(
            String id, String key, String tenantId, ReleaseKind kind, String mediaType,
            byte[] content, String sha256, ReleaseStatus status, String engineDeploymentId,
            String failureDetail, String publishedBy) {
        return new CaseDefinitionRelease(id, key, tenantId, kind, mediaType, content, sha256,
                status, engineDeploymentId, failureDetail, OffsetDateTime.now(), publishedBy);
    }
}
