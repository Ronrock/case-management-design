package org.casemgmt.service;

import org.casemgmt.orchestration.OrchestrationDeploymentPort;
import org.casemgmt.orchestration.EngineDeploymentIdentity;
import org.casemgmt.release.CaseDefinitionRelease;
import org.casemgmt.release.JsonSchemaCaseContractValidator;
import org.casemgmt.release.ReleaseKind;
import org.casemgmt.release.ReleaseStatus;
import org.casemgmt.repo.CaseDefinitionReleaseRepository;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CaseDefinitionReleaseServiceTest {

    @Test
    void publishesContentAddressedImmutableReleases() {
        CaseDefinitionReleaseRepository repository = mock(CaseDefinitionReleaseRepository.class);
        when(repository.findByDigest(eq("t1"), eq("sample-case"),
                eq(ReleaseKind.PRESENTATION), any()))
                .thenReturn(Optional.empty());
        CaseDefinitionReleaseService service = new CaseDefinitionReleaseService(repository);

        CaseDefinitionRelease release = service.publish("sample-case", "t1",
                ReleaseKind.PRESENTATION, "application/json",
                "{\"version\":\"1.0\",\"sections\":[]}".getBytes(StandardCharsets.UTF_8),
                "alice");

        assertThat(release.id()).startsWith("presentation:");
        assertThat(release.sha256()).hasSize(64);
        assertThat(release.content()).isNotEmpty();
        var order = inOrder(repository);
        order.verify(repository).insert(org.mockito.ArgumentMatchers.argThat(
                inserted -> inserted.status() == ReleaseStatus.DRAFT));
        order.verify(repository).transition(
                release.id(), ReleaseStatus.DRAFT, ReleaseStatus.VALIDATED, null, null);
        order.verify(repository).transition(
                release.id(), ReleaseStatus.VALIDATED, ReleaseStatus.ACTIVE, null, null);
    }

    @Test
    void returnsExistingReleaseForIdenticalContent() {
        byte[] content = ("{\"key\":\"sample-case\",\"orchestrationMode\":\"BPMN\","
                + "\"fields\":{},\"forms\":{}}").getBytes(StandardCharsets.UTF_8);
        CaseDefinitionRelease existing = CaseDefinitionRelease.stored(
                "contract:existing", "sample-case", "t1", ReleaseKind.CONTRACT,
                "application/schema+json", content, "a".repeat(64),
                org.casemgmt.release.ReleaseStatus.ACTIVE, null, null, "alice");
        CaseDefinitionReleaseRepository repository = mock(CaseDefinitionReleaseRepository.class);
        when(repository.findByDigest(eq("t1"), eq("sample-case"),
                eq(ReleaseKind.CONTRACT), any()))
                .thenReturn(Optional.of(existing));

        assertThat(new CaseDefinitionReleaseService(repository).publish("sample-case", "t1",
                ReleaseKind.CONTRACT, "application/schema+json", content, "bob"))
                .isSameAs(existing);
    }

    @Test
    void persistsInvalidStandaloneContractAsAFailedContentAddressedRelease() {
        CaseDefinitionReleaseRepository repository = mock(CaseDefinitionReleaseRepository.class);
        byte[] content = ("{\"key\":\"sample-case\",\"orchestrationMode\":\"BPMN\","
                + "\"fields\":{},\"forms\":{},\"warnngs\":[]}")
                .getBytes(StandardCharsets.UTF_8);

        CaseDefinitionRelease release = new CaseDefinitionReleaseService(repository).publish(
                "sample-case", "t1", ReleaseKind.CONTRACT,
                "application/json", content, "alice");

        assertThat(release.status()).isEqualTo(ReleaseStatus.FAILED);
        assertThat(release.failureDetail()).contains("/warnngs");
        verify(repository).insert(org.mockito.ArgumentMatchers.argThat(
                inserted -> inserted.status() == ReleaseStatus.DRAFT
                        && inserted.sha256().length() == 64));
        verify(repository).transition(eq(release.id()), eq(ReleaseStatus.DRAFT),
                eq(ReleaseStatus.FAILED), eq(null),
                org.mockito.ArgumentMatchers.contains("/warnngs"));
    }

    @Test
    void persistsMissingContractModeAsAStandalonePublicationFailure() {
        CaseDefinitionReleaseRepository repository = mock(CaseDefinitionReleaseRepository.class);
        byte[] content = "{\"key\":\"sample-case\",\"forms\":{}}"
                .getBytes(StandardCharsets.UTF_8);

        CaseDefinitionRelease release = new CaseDefinitionReleaseService(repository).publish(
                "sample-case", "t1", ReleaseKind.CONTRACT,
                "application/json", content, "alice");

        assertThat(release.status()).isEqualTo(ReleaseStatus.FAILED);
        assertThat(release.failureDetail()).contains("orchestrationMode");
    }

    @Test
    void validatesHashesAndStoresOneImmutableSnapshotOfCallerOwnedContent() {
        byte[] callerContent = ("{\"key\":\"sample-case\",\"orchestrationMode\":\"BPMN\","
                + "\"fields\":{},\"forms\":{}}").getBytes(StandardCharsets.UTF_8);
        byte[] expectedSnapshot = callerContent.clone();
        CaseDefinitionReleaseRepository repository = mock(CaseDefinitionReleaseRepository.class);
        when(repository.findByDigest(eq("t1"), eq("sample-case"),
                eq(ReleaseKind.CONTRACT), any())).thenReturn(Optional.empty());
        JsonSchemaCaseContractValidator delegate = new JsonSchemaCaseContractValidator();
        CaseDefinitionReleaseService service = new CaseDefinitionReleaseService(repository,
                (releaseId, definitionKey, tenantId, content, mediaType) ->
                        OrchestrationDeploymentPort.DeploymentResult.active(null),
                (definitionKey, validationBytes) -> {
                    Arrays.fill(callerContent, (byte) 'x');
                    return delegate.validate(definitionKey, validationBytes);
                });

        CaseDefinitionRelease release = service.publish("sample-case", "t1",
                ReleaseKind.CONTRACT, "application/json", callerContent, "alice");

        assertThat(release.content()).isEqualTo(expectedSnapshot);
    }

    @Test
    void persistsTheVerifiedExactEngineIdentityOnAnOrchestrationRelease() {
        byte[] content = ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<bpmn:definitions xmlns:bpmn=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" "
                + "id=\"defs\" targetNamespace=\"urn:test\">"
                + "<bpmn:process id=\"invoice\" isExecutable=\"true\">"
                + "<bpmn:startEvent id=\"start\"/><bpmn:endEvent id=\"end\"/>"
                + "<bpmn:sequenceFlow id=\"flow\" sourceRef=\"start\" targetRef=\"end\"/>"
                + "</bpmn:process></bpmn:definitions>").getBytes(StandardCharsets.UTF_8);
        CaseDefinitionReleaseRepository repository = mock(CaseDefinitionReleaseRepository.class);
        when(repository.findByDigest(eq("tenant-a"), eq("invoice"),
                eq(ReleaseKind.ORCHESTRATION), any())).thenReturn(Optional.empty());
        EngineDeploymentIdentity identity = new EngineDeploymentIdentity(
                "deployment-7", "invoice:4:991", "invoice", 4, "tenant-a");
        CaseDefinitionReleaseService service = new CaseDefinitionReleaseService(repository,
                (releaseId, definitionKey, tenantId, bytes, mediaType) ->
                        OrchestrationDeploymentPort.DeploymentResult.active(identity));

        CaseDefinitionRelease release = service.publish("invoice", "tenant-a",
                ReleaseKind.ORCHESTRATION, "application/bpmn+xml", content, "alice");

        assertThat(release.engineDeploymentId()).isEqualTo("deployment-7");
        assertThat(release.engineProcessDefinitionId()).isEqualTo("invoice:4:991");
        assertThat(release.engineProcessDefinitionKey()).isEqualTo("invoice");
        assertThat(release.engineProcessDefinitionVersion()).isEqualTo(4);
        assertThat(release.engineTenantId()).isEqualTo("tenant-a");
        assertThat(release.status()).isEqualTo(org.casemgmt.release.ReleaseStatus.ACTIVE);
    }

    @Test
    void neverActivatesAnOrchestrationReleaseWithoutADeploymentAdapter() {
        byte[] content = ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<bpmn:definitions xmlns:bpmn=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" "
                + "id=\"defs\" targetNamespace=\"urn:test\">"
                + "<bpmn:process id=\"invoice\" isExecutable=\"true\"/>"
                + "</bpmn:definitions>").getBytes(StandardCharsets.UTF_8);
        CaseDefinitionReleaseRepository repository = mock(CaseDefinitionReleaseRepository.class);
        when(repository.findByDigest(eq("tenant-a"), eq("invoice"),
                eq(ReleaseKind.ORCHESTRATION), any())).thenReturn(Optional.empty());

        CaseDefinitionRelease release = new CaseDefinitionReleaseService(repository).publish(
                "invoice", "tenant-a", ReleaseKind.ORCHESTRATION,
                "application/bpmn+xml", content, "alice");

        assertThat(release.status()).isEqualTo(ReleaseStatus.FAILED);
        assertThat(release.failureDetail()).contains("deployment adapter");
        var order = inOrder(repository);
        order.verify(repository).insert(org.mockito.ArgumentMatchers.argThat(
                inserted -> inserted.status() == ReleaseStatus.DRAFT));
        order.verify(repository).transition(
                release.id(), ReleaseStatus.DRAFT, ReleaseStatus.VALIDATED, null, null);
        order.verify(repository).transition(
                release.id(), ReleaseStatus.VALIDATED, ReleaseStatus.DEPLOYING, null, null);
        order.verify(repository).transition(eq(release.id()), eq(ReleaseStatus.DEPLOYING),
                eq(ReleaseStatus.FAILED), eq(null),
                org.mockito.ArgumentMatchers.contains("deployment adapter"));
    }

    @Test
    void rejectsAnAdapterThatClaimsActivationWithoutAnExactIdentity() {
        byte[] content = ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<bpmn:definitions xmlns:bpmn=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" "
                + "id=\"defs\" targetNamespace=\"urn:test\">"
                + "<bpmn:process id=\"invoice\" isExecutable=\"true\"/>"
                + "</bpmn:definitions>").getBytes(StandardCharsets.UTF_8);
        CaseDefinitionReleaseRepository repository = mock(CaseDefinitionReleaseRepository.class);
        when(repository.findByDigest(eq("tenant-a"), eq("invoice"),
                eq(ReleaseKind.ORCHESTRATION), any())).thenReturn(Optional.empty());
        CaseDefinitionReleaseService service = new CaseDefinitionReleaseService(repository,
                (releaseId, definitionKey, tenantId, bytes, mediaType) ->
                        OrchestrationDeploymentPort.DeploymentResult.active(null));

        CaseDefinitionRelease release = service.publish("invoice", "tenant-a",
                ReleaseKind.ORCHESTRATION, "application/bpmn+xml", content, "alice");

        assertThat(release.status()).isEqualTo(ReleaseStatus.FAILED);
        assertThat(release.failureDetail()).contains("verified engine identity");
        verify(repository).transition(eq(release.id()), eq(ReleaseStatus.DEPLOYING),
                eq(ReleaseStatus.FAILED), eq(null),
                org.mockito.ArgumentMatchers.contains("verified engine identity"));
    }
}
