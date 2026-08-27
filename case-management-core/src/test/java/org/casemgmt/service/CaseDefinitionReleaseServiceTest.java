package org.casemgmt.service;

import org.casemgmt.release.CaseDefinitionRelease;
import org.casemgmt.release.ReleaseKind;
import org.casemgmt.repo.CaseDefinitionReleaseRepository;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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
        byte[] content = "{\"type\":\"object\"}".getBytes(StandardCharsets.UTF_8);
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
}
