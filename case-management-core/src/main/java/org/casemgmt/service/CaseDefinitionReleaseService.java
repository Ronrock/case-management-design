package org.casemgmt.service;

import org.casemgmt.error.InvalidCaseDefinitionException;
import org.casemgmt.domain.CaseIds;
import org.casemgmt.orchestration.OrchestrationDeploymentPort;
import org.casemgmt.release.CaseDefinitionRelease;
import org.casemgmt.release.BpmnReleaseValidator;
import org.casemgmt.release.ReleaseKind;
import org.casemgmt.repo.CaseDefinitionReleaseRepository;
import org.casemgmt.repo.JsonCodec;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.transaction.annotation.Transactional;

public class CaseDefinitionReleaseService {

    public static final int MAX_RELEASE_BYTES = 25 * 1024 * 1024;

    private static final Map<ReleaseKind, Set<String>> MEDIA_TYPES = Map.of(
            ReleaseKind.ORCHESTRATION, Set.of("application/zip", "application/xml",
                    "application/bpmn+xml"),
            ReleaseKind.CONTRACT, Set.of("application/json", "application/schema+json"),
            ReleaseKind.PRESENTATION, Set.of("application/json"));

    private final CaseDefinitionReleaseRepository repository;
    private final OrchestrationDeploymentPort deployments;

    public CaseDefinitionReleaseService(CaseDefinitionReleaseRepository repository) {
        this(repository, (releaseId, definitionKey, tenantId, content, mediaType) ->
                OrchestrationDeploymentPort.DeploymentResult.active(null));
    }

    public CaseDefinitionReleaseService(CaseDefinitionReleaseRepository repository,
                                        OrchestrationDeploymentPort deployments) {
        this.repository = repository;
        this.deployments = deployments;
    }

    @Transactional
    public CaseDefinitionRelease publish(String key, String tenantId, ReleaseKind kind,
                                         String mediaType, byte[] content, String publishedBy) {
        if (key == null || key.isBlank()) {
            throw invalid("<unknown>", "Release requires a case-definition key");
        }
        byte[] bytes = content == null ? new byte[0] : content.clone();
        if (bytes.length == 0 || bytes.length > MAX_RELEASE_BYTES) {
            throw invalid(key, "Release content must contain between 1 and "
                    + MAX_RELEASE_BYTES + " bytes");
        }
        String normalizedMediaType = normalizeMediaType(mediaType);
        if (!MEDIA_TYPES.get(kind).contains(normalizedMediaType)) {
            throw invalid(key, "Unsupported media type '" + normalizedMediaType
                    + "' for " + kind.name().toLowerCase(Locale.ROOT) + " release");
        }
        validateContent(key, kind, normalizedMediaType, bytes);
        String digest = sha256(bytes);
        return repository.findByDigest(tenantId, key, kind, digest).orElseGet(() -> {
            String id = kind.name().toLowerCase(Locale.ROOT) + ":" + CaseIds.newId();
            OrchestrationDeploymentPort.DeploymentResult deployment = kind == ReleaseKind.ORCHESTRATION
                    ? deployments.deploy(id, key, tenantId, bytes, normalizedMediaType)
                    : OrchestrationDeploymentPort.DeploymentResult.active(null);
            CaseDefinitionRelease release = CaseDefinitionRelease.stored(
                    id, key, tenantId, kind, normalizedMediaType, bytes, digest,
                    deployment.status(), deployment.engineDeploymentId(),
                    deployment.failureDetail(), publishedBy);
            repository.insert(release);
            return release;
        });
    }

    private static void validateContent(String key, ReleaseKind kind, String mediaType,
                                        byte[] content) {
        if (kind == ReleaseKind.CONTRACT || kind == ReleaseKind.PRESENTATION) {
            Map<String, Object> json;
            try {
                json = JsonCodec.toMap(new String(content, StandardCharsets.UTF_8));
            } catch (RuntimeException e) {
                throw invalid(key, kind + " release must contain a JSON object");
            }
            if (kind == ReleaseKind.PRESENTATION) {
                Object version = json.getOrDefault("version", json.get("schemaVersion"));
                String text = String.valueOf(version);
                if (version == null || !text.matches("1(?:\\.\\d+)?")) {
                    throw invalid(key, "Presentation release requires supported version 1.x");
                }
                if (!(json.get("sections") instanceof java.util.List<?>)) {
                    throw invalid(key, "Presentation release requires a sections array");
                }
            }
        } else if (!mediaType.equals("application/zip")) {
            String xml = new String(content, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
            if (xml.contains("<!doctype") || xml.contains("<!entity")) {
                throw invalid(key, "BPMN XML must not declare a DOCTYPE or external entity");
            }
            if (!xml.contains("<bpmn:definitions") && !xml.contains("<definitions")) {
                throw invalid(key, "Orchestration release does not contain BPMN definitions");
            }
        }
        if (kind == ReleaseKind.ORCHESTRATION) {
            BpmnReleaseValidator.validate(key, content, mediaType);
        }
    }

    private static String normalizeMediaType(String mediaType) {
        return mediaType == null ? "application/octet-stream"
                : mediaType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM does not provide SHA-256", e);
        }
    }

    private static InvalidCaseDefinitionException invalid(String key, String message) {
        return new InvalidCaseDefinitionException(key, message);
    }
}
