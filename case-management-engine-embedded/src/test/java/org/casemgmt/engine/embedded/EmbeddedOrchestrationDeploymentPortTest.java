package org.casemgmt.engine.embedded;

import org.casemgmt.engine.EngineException;
import org.casemgmt.orchestration.EngineDeploymentIdentity;
import org.casemgmt.release.ReleaseStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.RepositoryService;
import org.operaton.bpm.engine.repository.Deployment;
import org.operaton.bpm.engine.repository.DeploymentBuilder;
import org.operaton.bpm.engine.repository.ProcessDefinition;
import org.operaton.bpm.engine.repository.ProcessDefinitionQuery;

import java.nio.charset.StandardCharsets;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EmbeddedOrchestrationDeploymentPortTest {

    private final RepositoryService repository = mock(RepositoryService.class);
    private final DeploymentBuilder builder = mock(DeploymentBuilder.class);
    private final Deployment deployment = mock(Deployment.class);
    private final ProcessDefinitionQuery query = mock(ProcessDefinitionQuery.class);
    private final EmbeddedOrchestrationDeploymentPort port =
            new EmbeddedOrchestrationDeploymentPort(repository);

    @BeforeEach
    void arrangeDeployment() {
        when(repository.createDeployment()).thenReturn(builder);
        when(builder.name(anyString())).thenReturn(builder);
        when(builder.tenantId(anyString())).thenReturn(builder);
        when(builder.enableDuplicateFiltering(true)).thenReturn(builder);
        when(builder.addInputStream(anyString(), any())).thenReturn(builder);
        when(builder.deploy()).thenReturn(deployment);
        when(deployment.getId()).thenReturn("deployment-7");
        when(repository.createProcessDefinitionQuery()).thenReturn(query);
        when(query.deploymentId("deployment-7")).thenReturn(query);
        when(repository.getDeploymentResourceNames("deployment-7"))
                .thenReturn(List.of("invoice.bpmn"));
        when(repository.getResourceAsStream("deployment-7", "invoice.bpmn"))
                .thenAnswer(ignored -> new ByteArrayInputStream(bpmn()));
    }

    @Test
    void returnsTheOnlyMatchingExecutableRootAsTheExactEngineIdentity() {
        ProcessDefinition root = definition(
                "invoice:4:991", "invoice", 4, "deployment-7", "tenant-a");
        when(query.list()).thenReturn(List.of(root));

        var result = port.deploy("release-1", "invoice", "tenant-a", bpmn(),
                "application/bpmn+xml");

        assertThat(result.status()).isEqualTo(ReleaseStatus.ACTIVE);
        assertThat(result.identity()).isEqualTo(new EngineDeploymentIdentity(
                "deployment-7", "invoice:4:991", "invoice", 4, "tenant-a"));
        assertThat(result.failureDetail()).isNull();
    }

    @Test
    void verifiesAnUnscopedDeploymentWithoutInventingATenant() {
        ProcessDefinition root = definition(
                "invoice:1:100", "invoice", 1, "deployment-7", null);
        when(query.list()).thenReturn(List.of(root));

        var result = port.deploy("release-1", "invoice", null, bpmn(),
                "application/bpmn+xml");

        assertThat(result.identity().tenantId()).isNull();
        assertThat(result.identity().processDefinitionId()).isEqualTo("invoice:1:100");
    }

    @Test
    void rejectsADeploymentWithNoExecutableRoot() {
        when(query.list()).thenReturn(List.of());

        assertThatThrownBy(() -> port.deploy("release-1", "invoice", "tenant-a", bpmn(),
                "application/bpmn+xml"))
                .isInstanceOf(EngineException.class)
                .hasMessageContaining("exactly one")
                .hasMessageContaining("found 0");
    }

    @Test
    void rejectsADeploymentWithMultipleMatchingRoots() {
        ProcessDefinition version4 = definition(
                "invoice:4:991", "invoice", 4, "deployment-7", "tenant-a");
        ProcessDefinition version5 = definition(
                "invoice:5:992", "invoice", 5, "deployment-7", "tenant-a");
        when(query.list()).thenReturn(List.of(version4, version5));

        assertThatThrownBy(() -> port.deploy("release-1", "invoice", "tenant-a", bpmn(),
                "application/bpmn+xml"))
                .isInstanceOf(EngineException.class)
                .hasMessageContaining("exactly one")
                .hasMessageContaining("found 2");
    }

    @Test
    void rejectsADeploymentWhoseOnlyRootHasTheWrongDefinitionKey() {
        ProcessDefinition wrongKey = definition(
                "credit:1:12", "credit", 1, "deployment-7", "tenant-a");
        when(query.list()).thenReturn(List.of(wrongKey));

        assertThatThrownBy(() -> port.deploy("release-1", "invoice", "tenant-a", bpmn(),
                "application/bpmn+xml"))
                .isInstanceOf(EngineException.class)
                .hasMessageContaining("definition key 'invoice'")
                .hasMessageContaining("credit");
    }

    @Test
    void rejectsADeploymentWhoseOnlyRootBelongsToAnotherTenant() {
        ProcessDefinition wrongTenant = definition(
                "invoice:4:991", "invoice", 4, "deployment-7", "tenant-b");
        when(query.list()).thenReturn(List.of(wrongTenant));

        assertThatThrownBy(() -> port.deploy("release-1", "invoice", "tenant-a", bpmn(),
                "application/bpmn+xml"))
                .isInstanceOf(EngineException.class)
                .hasMessageContaining("tenant 'tenant-a'")
                .hasMessageContaining("tenant-b");
    }

    @Test
    void rejectsADeploymentWithAnAddedResource() {
        ProcessDefinition root = definition(
                "invoice:4:991", "invoice", 4, "deployment-7", "tenant-a");
        when(query.list()).thenReturn(List.of(root));
        when(repository.getDeploymentResourceNames("deployment-7"))
                .thenReturn(List.of("invoice.bpmn", "unexpected.dmn"));
        when(repository.getResourceAsStream("deployment-7", "unexpected.dmn"))
                .thenAnswer(ignored -> new ByteArrayInputStream("<definitions/>"
                        .getBytes(StandardCharsets.UTF_8)));

        assertThatThrownBy(() -> port.deploy("release-1", "invoice", "tenant-a", bpmn(),
                "application/bpmn+xml"))
                .isInstanceOf(EngineException.class)
                .hasMessageContaining("resource manifest")
                .hasMessageContaining("unexpected.dmn");
    }

    @Test
    void rejectsADeploymentWithAMissingResource() {
        ProcessDefinition root = definition(
                "invoice:4:991", "invoice", 4, "deployment-7", "tenant-a");
        when(query.list()).thenReturn(List.of(root));
        when(repository.getDeploymentResourceNames("deployment-7")).thenReturn(List.of());

        assertThatThrownBy(() -> port.deploy("release-1", "invoice", "tenant-a", bpmn(),
                "application/bpmn+xml"))
                .isInstanceOf(EngineException.class)
                .hasMessageContaining("resource manifest")
                .hasMessageContaining("invoice.bpmn");
    }

    @Test
    void rejectsADeploymentWithChangedResourceBytes() {
        ProcessDefinition root = definition(
                "invoice:4:991", "invoice", 4, "deployment-7", "tenant-a");
        when(query.list()).thenReturn(List.of(root));
        when(repository.getResourceAsStream("deployment-7", "invoice.bpmn"))
                .thenAnswer(ignored -> new ByteArrayInputStream("<definitions changed='true'/>"
                        .getBytes(StandardCharsets.UTF_8)));

        assertThatThrownBy(() -> port.deploy("release-1", "invoice", "tenant-a", bpmn(),
                "application/bpmn+xml"))
                .isInstanceOf(EngineException.class)
                .hasMessageContaining("resource manifest")
                .hasMessageContaining("invoice.bpmn");
    }

    @Test
    void rejectsAChangedResourceInsideAZipArtifact() {
        ProcessDefinition root = definition(
                "invoice:4:991", "invoice", 4, "deployment-7", "tenant-a");
        when(query.list()).thenReturn(List.of(root));
        when(repository.getDeploymentResourceNames("deployment-7"))
                .thenReturn(List.of("decision.dmn", "invoice.bpmn"));
        when(repository.getResourceAsStream("deployment-7", "invoice.bpmn"))
                .thenAnswer(ignored -> new ByteArrayInputStream(bpmn()));
        when(repository.getResourceAsStream("deployment-7", "decision.dmn"))
                .thenAnswer(ignored -> new ByteArrayInputStream("changed-decision"
                        .getBytes(StandardCharsets.UTF_8)));
        byte[] archive = zip(Map.of(
                "invoice.bpmn", bpmn(),
                "decision.dmn", "approved-decision".getBytes(StandardCharsets.UTF_8)));

        assertThatThrownBy(() -> port.deploy("release-1", "invoice", "tenant-a", archive,
                "application/zip"))
                .isInstanceOf(EngineException.class)
                .hasMessageContaining("resource manifest")
                .hasMessageContaining("decision.dmn");
    }

    private static ProcessDefinition definition(String id, String key, int version,
                                                String deploymentId, String tenantId) {
        ProcessDefinition definition = mock(ProcessDefinition.class);
        when(definition.getId()).thenReturn(id);
        when(definition.getKey()).thenReturn(key);
        when(definition.getVersion()).thenReturn(version);
        when(definition.getDeploymentId()).thenReturn(deploymentId);
        when(definition.getTenantId()).thenReturn(tenantId);
        return definition;
    }

    private static byte[] bpmn() {
        return "<definitions/>".getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] zip(Map<String, byte[]> resources) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
                for (var resource : new LinkedHashMap<>(resources).entrySet()) {
                    zip.putNextEntry(new ZipEntry(resource.getKey()));
                    zip.write(resource.getValue());
                    zip.closeEntry();
                }
            }
            return bytes.toByteArray();
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }
}
