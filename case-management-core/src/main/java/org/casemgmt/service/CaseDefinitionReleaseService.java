package org.casemgmt.service;

import org.casemgmt.error.InvalidCaseDefinitionException;
import org.casemgmt.domain.CaseIds;
import org.casemgmt.orchestration.OrchestrationDeploymentPort;
import org.casemgmt.release.CaseDefinitionRelease;
import org.casemgmt.release.BpmnReleaseValidator;
import org.casemgmt.release.CaseContractValidator;
import org.casemgmt.release.JsonSchemaCaseContractValidator;
import org.casemgmt.release.ReleaseKind;
import org.casemgmt.release.ReleaseStatus;
import org.casemgmt.repo.CaseDefinitionReleaseRepository;
import org.casemgmt.repo.JsonCodec;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.transaction.annotation.Transactional;

public class CaseDefinitionReleaseService {

    public static final int MAX_RELEASE_BYTES = 25 * 1024 * 1024;
    private static final int MAX_FAILURE_DETAIL = 2_000;

    private static final Map<ReleaseKind, Set<String>> MEDIA_TYPES = Map.of(
            ReleaseKind.ORCHESTRATION, Set.of("application/zip", "application/xml",
                    "application/bpmn+xml"),
            ReleaseKind.CONTRACT, Set.of("application/json", "application/schema+json"),
            ReleaseKind.PRESENTATION, Set.of("application/json"));

    private final CaseDefinitionReleaseRepository repository;
    private final OrchestrationDeploymentPort deployments;
    private final CaseContractValidator contracts;

    public CaseDefinitionReleaseService(CaseDefinitionReleaseRepository repository) {
        this(repository, (releaseId, definitionKey, tenantId, content, mediaType) -> {
            throw new org.casemgmt.engine.EngineException(
                    "No orchestration deployment adapter is configured");
        });
    }

    public CaseDefinitionReleaseService(CaseDefinitionReleaseRepository repository,
                                        OrchestrationDeploymentPort deployments) {
        this(repository, deployments, new JsonSchemaCaseContractValidator());
    }

    public CaseDefinitionReleaseService(CaseDefinitionReleaseRepository repository,
                                        OrchestrationDeploymentPort deployments,
                                        CaseContractValidator contracts) {
        this.repository = repository;
        this.deployments = deployments;
        this.contracts = contracts;
    }

    @Transactional
    public CaseDefinitionRelease publish(String key, String tenantId, ReleaseKind kind,
                                         String mediaType, byte[] content, String publishedBy) {
        byte[] bytes = snapshot(content);
        String normalizedMediaType = validateEnvelope(key, kind, mediaType, bytes);
        String digest = sha256(bytes);
        return repository.findByDigest(tenantId, key, kind, digest).orElseGet(() -> {
            String id = kind.name().toLowerCase(Locale.ROOT) + ":" + CaseIds.newId();
            CaseDefinitionRelease draft = CaseDefinitionRelease.draft(
                    id, key, tenantId, kind, normalizedMediaType, bytes, digest, publishedBy);
            repository.insert(draft);
            try {
                validateContent(key, kind, normalizedMediaType, bytes);
            } catch (RuntimeException invalidContent) {
                return transition(draft, ReleaseStatus.FAILED, null,
                        boundFailure(invalidContent.getMessage()));
            }
            CaseDefinitionRelease validated = transition(
                    draft, ReleaseStatus.VALIDATED, null, null);
            if (kind != ReleaseKind.ORCHESTRATION) {
                return transition(validated, ReleaseStatus.ACTIVE, null, null);
            }

            CaseDefinitionRelease deploying = transition(
                    validated, ReleaseStatus.DEPLOYING, null, null);
            OrchestrationDeploymentPort.DeploymentResult deployment;
            try {
                deployment = deployments.deploy(
                        id, key, tenantId, bytes, normalizedMediaType);
                validateDeploymentResult(key, tenantId, deployment);
            } catch (RuntimeException failure) {
                return transition(deploying, ReleaseStatus.FAILED, null,
                        boundFailure(failure.getMessage()));
            }
            if (deployment.status() == ReleaseStatus.DEPLOYING) {
                return deploying;
            }
            if (deployment.status() == ReleaseStatus.FAILED) {
                return transition(deploying, ReleaseStatus.FAILED, null,
                        boundFailure(deployment.failureDetail()));
            }
            return transition(deploying, ReleaseStatus.ACTIVE,
                    deployment.identity(), null);
        });
    }

    private CaseDefinitionRelease transition(
            CaseDefinitionRelease release, ReleaseStatus next,
            org.casemgmt.orchestration.EngineDeploymentIdentity identity, String failureDetail) {
        CaseDefinitionRelease transitioned = release.transitionTo(next, identity, failureDetail);
        repository.transition(release.id(), release.status(), next, identity, failureDetail);
        return transitioned;
    }

    private static String boundFailure(String detail) {
        String diagnostic = detail == null || detail.isBlank()
                ? "Orchestration deployment failed without a diagnostic" : detail;
        if (diagnostic.length() <= MAX_FAILURE_DETAIL) return diagnostic;
        return diagnostic.substring(0, MAX_FAILURE_DETAIL - 3) + "...";
    }

    private static void validateDeploymentResult(
            String definitionKey, String tenantId,
            OrchestrationDeploymentPort.DeploymentResult deployment) {
        if (deployment == null) {
            throw new org.casemgmt.engine.EngineException(
                    "Orchestration deployment adapter returned no result");
        }
        if (deployment.status() == ReleaseStatus.ACTIVE && deployment.identity() == null) {
            throw new org.casemgmt.engine.EngineException(
                    "An active orchestration release requires a verified engine identity");
        }
        if (deployment.identity() != null
                && (!definitionKey.equals(deployment.identity().processDefinitionKey())
                || !Objects.equals(tenantId, deployment.identity().tenantId()))) {
            throw new org.casemgmt.engine.EngineException(
                    "Verified engine identity does not match the release key and tenant");
        }
        if (deployment.status() != ReleaseStatus.ACTIVE
                && deployment.status() != ReleaseStatus.DEPLOYING
                && deployment.status() != ReleaseStatus.FAILED) {
            throw new org.casemgmt.engine.EngineException(
                    "Orchestration deployment adapter returned invalid status "
                            + deployment.status());
        }
        if (deployment.status() != ReleaseStatus.ACTIVE && deployment.identity() != null) {
            throw new org.casemgmt.engine.EngineException(
                    "Only an active orchestration deployment may carry an engine identity");
        }
    }

    /** Runs every deterministic release check without persisting or deploying anything. */
    public void validateForPublication(String key, ReleaseKind kind, String mediaType, byte[] content) {
        byte[] bytes = snapshot(content);
        String normalizedMediaType = validateEnvelope(key, kind, mediaType, bytes);
        validateContent(key, kind, normalizedMediaType, bytes);
    }

    private String validateEnvelope(String key, ReleaseKind kind, String mediaType, byte[] bytes) {
        if (key == null || key.isBlank()) {
            throw invalid("<unknown>", "Release requires a case-definition key");
        }
        if (bytes.length == 0 || bytes.length > MAX_RELEASE_BYTES) {
            throw invalid(key, "Release content must contain between 1 and "
                    + MAX_RELEASE_BYTES + " bytes");
        }
        String normalizedMediaType = normalizeMediaType(mediaType);
        if (!MEDIA_TYPES.get(kind).contains(normalizedMediaType)) {
            throw invalid(key, "Unsupported media type '" + normalizedMediaType
                    + "' for " + kind.name().toLowerCase(Locale.ROOT) + " release");
        }
        return normalizedMediaType;
    }

    private static byte[] snapshot(byte[] content) {
        return content == null ? new byte[0] : content.clone();
    }

    private void validateContent(String key, ReleaseKind kind, String mediaType, byte[] content) {
        if (kind == ReleaseKind.CONTRACT || kind == ReleaseKind.PRESENTATION) {
            Map<String, Object> json;
            try {
                json = JsonCodec.toMap(new String(content, StandardCharsets.UTF_8));
            } catch (RuntimeException e) {
                throw invalid(key, kind + " release must contain a JSON object");
            }
            if (kind == ReleaseKind.CONTRACT) {
                Object mode = json.get("orchestrationMode");
                if (mode == null || mode.toString().isBlank()) {
                    throw invalid(key, "Contract release requires orchestrationMode");
                }
                contracts.validate(key, content);
            } else {
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
