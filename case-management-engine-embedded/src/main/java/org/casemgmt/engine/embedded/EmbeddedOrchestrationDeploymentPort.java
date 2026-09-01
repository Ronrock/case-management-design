package org.casemgmt.engine.embedded;

import org.casemgmt.engine.EngineException;
import org.casemgmt.orchestration.DeploymentResourceManifest;
import org.casemgmt.orchestration.EngineDeploymentIdentity;
import org.casemgmt.orchestration.OrchestrationDeploymentPort;
import org.operaton.bpm.engine.RepositoryService;
import org.operaton.bpm.engine.repository.ProcessDefinition;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Objects;
import java.util.zip.ZipInputStream;

public class EmbeddedOrchestrationDeploymentPort implements OrchestrationDeploymentPort {

    private final RepositoryService repositoryService;

    public EmbeddedOrchestrationDeploymentPort(RepositoryService repositoryService) {
        this.repositoryService = repositoryService;
    }

    @Override
    public DeploymentResult deploy(String releaseId, String definitionKey, String tenantId,
                                   byte[] content, String mediaType) {
        DeploymentResourceManifest approved = approvedManifest(
                definitionKey, content, mediaType);
        var builder = repositoryService.createDeployment()
                .name("case-release:" + releaseId)
                .enableDuplicateFiltering(true);
        if (tenantId != null) {
            builder.tenantId(tenantId);
        }
        if ("application/zip".equals(mediaType)) {
            builder.addZipInputStream(new ZipInputStream(new ByteArrayInputStream(content)));
        } else {
            builder.addInputStream(definitionKey + ".bpmn", new ByteArrayInputStream(content));
        }
        String deploymentId = builder.deploy().getId();
        List<ProcessDefinition> deployedDefinitions = repositoryService
                .createProcessDefinitionQuery()
                .deploymentId(deploymentId)
                .list();
        EngineDeploymentIdentity identity = resolveIdentity(
                deploymentId, definitionKey, tenantId, deployedDefinitions);
        verifyResourceManifest(deploymentId, definitionKey, tenantId, approved);
        return DeploymentResult.active(identity);
    }

    private static DeploymentResourceManifest approvedManifest(
            String definitionKey, byte[] content, String mediaType) {
        try {
            return DeploymentResourceManifest.fromArtifact(definitionKey, content, mediaType);
        } catch (IllegalArgumentException e) {
            throw new EngineException("Approved orchestration resource manifest is invalid: "
                    + e.getMessage(), e);
        }
    }

    private void verifyResourceManifest(
            String deploymentId, String definitionKey, String tenantId,
            DeploymentResourceManifest approved) {
        DeploymentResourceManifest.Builder actual = DeploymentResourceManifest.builder();
        List<String> names = repositoryService.getDeploymentResourceNames(deploymentId);
        try {
            for (String name : names == null ? List.<String>of() : names) {
                try (InputStream content = repositoryService.getResourceAsStream(
                        deploymentId, name)) {
                    actual.add(name, content);
                }
            }
        } catch (IOException | IllegalArgumentException e) {
            throw mismatch(definitionKey, tenantId,
                    "could not read the deployed resource manifest: " + e.getMessage());
        }
        DeploymentResourceManifest deployed = actual.build();
        if (!approved.matches(deployed)) {
            throw mismatch(definitionKey, tenantId, "resource manifest mismatch: "
                    + approved.differenceFrom(deployed));
        }
    }

    private static EngineDeploymentIdentity resolveIdentity(
            String deploymentId, String definitionKey, String tenantId,
            List<ProcessDefinition> deployedDefinitions) {
        if (deployedDefinitions.isEmpty()) {
            throw mismatch(definitionKey, tenantId,
                    "expected exactly one executable root but found 0");
        }

        List<ProcessDefinition> matchingKey = deployedDefinitions.stream()
                .filter(definition -> definitionKey.equals(definition.getKey()))
                .toList();
        if (matchingKey.isEmpty()) {
            String actualKeys = deployedDefinitions.stream()
                    .map(ProcessDefinition::getKey).distinct().sorted().toList().toString();
            throw mismatch(definitionKey, tenantId,
                    "definition key '" + definitionKey + "' was not present; found " + actualKeys);
        }

        List<ProcessDefinition> matchingTenant = matchingKey.stream()
                .filter(definition -> Objects.equals(tenantId, definition.getTenantId()))
                .toList();
        if (matchingTenant.isEmpty()) {
            String actualTenants = matchingKey.stream()
                    .map(ProcessDefinition::getTenantId)
                    .map(actual -> actual == null ? "<none>" : actual)
                    .distinct().sorted().toList().toString();
            throw mismatch(definitionKey, tenantId,
                    "tenant '" + displayTenant(tenantId) + "' did not match; found "
                            + actualTenants);
        }
        if (matchingTenant.size() != 1) {
            throw mismatch(definitionKey, tenantId,
                    "expected exactly one executable root but found " + matchingTenant.size());
        }

        ProcessDefinition root = matchingTenant.getFirst();
        return new EngineDeploymentIdentity(deploymentId, root.getId(), root.getKey(),
                root.getVersion(), root.getTenantId());
    }

    private static EngineException mismatch(String definitionKey, String tenantId, String detail) {
        return new EngineException("Deployment verification failed for definition key '"
                + definitionKey + "' and tenant '" + displayTenant(tenantId) + "': " + detail);
    }

    private static String displayTenant(String tenantId) {
        return tenantId == null ? "<none>" : tenantId;
    }
}
