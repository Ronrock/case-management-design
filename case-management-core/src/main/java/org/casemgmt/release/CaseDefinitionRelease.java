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

    public static CaseDefinitionRelease stored(
            String id, String key, String tenantId, ReleaseKind kind, String mediaType,
            byte[] content, String sha256, String publishedBy) {
        return new CaseDefinitionRelease(id, key, tenantId, kind, mediaType, content, sha256,
                ReleaseStatus.ACTIVE, null, null, OffsetDateTime.now(), publishedBy);
    }

    public static CaseDefinitionRelease stored(
            String id, String key, String tenantId, ReleaseKind kind, String mediaType,
            byte[] content, String sha256, ReleaseStatus status, String engineDeploymentId,
            String failureDetail, String publishedBy) {
        return new CaseDefinitionRelease(id, key, tenantId, kind, mediaType, content, sha256,
                status, engineDeploymentId, failureDetail, OffsetDateTime.now(), publishedBy);
    }
}
