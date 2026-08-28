package org.casemgmt.service;

import org.casemgmt.orchestration.OrchestrationDeploymentPort;
import org.casemgmt.release.CaseDefinitionRelease;
import org.casemgmt.release.JsonSchemaCaseContractValidator;
import org.casemgmt.release.ReleaseKind;
import org.casemgmt.repo.CaseDefinitionReleaseRepository;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
        verify(repository).insert(release);
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
    void rejectsInvalidContractBeforeDigestLookupOrInsert() {
        CaseDefinitionReleaseRepository repository = mock(CaseDefinitionReleaseRepository.class);
        byte[] content = ("{\"key\":\"sample-case\",\"orchestrationMode\":\"BPMN\","
                + "\"fields\":{},\"forms\":{},\"warnngs\":[]}")
                .getBytes(StandardCharsets.UTF_8);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                new CaseDefinitionReleaseService(repository).publish("sample-case", "t1",
                        ReleaseKind.CONTRACT, "application/json", content, "alice"))
                .isInstanceOf(org.casemgmt.error.InvalidCaseDefinitionException.class)
                .hasMessageContaining("/warnngs");

        verifyNoInteractions(repository);
    }

    @Test
    void requiresExplicitModeForNewContractPublication() {
        CaseDefinitionReleaseRepository repository = mock(CaseDefinitionReleaseRepository.class);
        byte[] content = "{\"key\":\"sample-case\",\"forms\":{}}"
                .getBytes(StandardCharsets.UTF_8);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                new CaseDefinitionReleaseService(repository).publish("sample-case", "t1",
                        ReleaseKind.CONTRACT, "application/json", content, "alice"))
                .isInstanceOf(org.casemgmt.error.InvalidCaseDefinitionException.class)
                .hasMessageContaining("orchestrationMode");

        verifyNoInteractions(repository);
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
}
