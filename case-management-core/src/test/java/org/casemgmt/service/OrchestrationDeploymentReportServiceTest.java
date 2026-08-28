package org.casemgmt.service;

import org.casemgmt.orchestration.EngineDeploymentIdentity;
import org.casemgmt.orchestration.OrchestrationMode;
import org.casemgmt.release.BindingStatus;
import org.casemgmt.release.CaseDefinitionRelease;
import org.casemgmt.release.CaseDefinitionVersionBinding;
import org.casemgmt.release.ReleaseKind;
import org.casemgmt.release.ReleaseStatus;
import org.casemgmt.repo.CaseDefinitionReleaseRepository;
import org.casemgmt.repo.CaseDefinitionVersionBindingRepository;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.when;

class OrchestrationDeploymentReportServiceTest {

    private final CaseDefinitionReleaseRepository releases =
            mock(CaseDefinitionReleaseRepository.class);
    private final CaseDefinitionVersionBindingRepository bindings =
            mock(CaseDefinitionVersionBindingRepository.class);
    private final OrchestrationDeploymentReportService service =
            new OrchestrationDeploymentReportService(releases, bindings);

    @Test
    void activatesTheReleaseAndWaitingBindingsWithTheSameVerifiedIdentity() {
        var identity = new EngineDeploymentIdentity(
                "deployment-7", "invoice:4:991", "invoice", 4, "tenant-a");
        CaseDefinitionVersionBinding draft = draftBinding();
        when(bindings.findDraftByOrchestrationRelease("release-1"))
                .thenReturn(List.of(draft));
        stubPinnedReleases(identity, ReleaseStatus.ACTIVE);

        service.report("release-1", ReleaseStatus.ACTIVE, identity, null);

        verify(releases).markDeployment("release-1", ReleaseStatus.ACTIVE, identity, null);
        verify(bindings).activate(org.mockito.ArgumentMatchers.argThat(active ->
                active.caseDefinitionId().equals("tenant-a:invoice:4")
                        && active.status() == BindingStatus.ACTIVE
                        && identity.equals(active.engineIdentity())));
    }

    @Test
    void rejectsActivationWithoutAVerifiedIdentityBeforeWritingAnything() {
        assertThatThrownBy(() -> service.report(
                "release-1", ReleaseStatus.ACTIVE, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("verified engine identity");

        verifyNoInteractions(releases, bindings);
    }

    @Test
    void storesABoundedFailureDiagnosticForTheReleaseAndWaitingBindings() {
        String oversized = "x".repeat(2_100);

        service.report("release-1", ReleaseStatus.FAILED, null, oversized);

        String bounded = "x".repeat(1_997) + "...";
        verify(releases).markDeployment("release-1", ReleaseStatus.FAILED, null, bounded);
        verify(bindings).failWaitingByRelease("release-1", bounded);
    }

    @Test
    void doesNotPromoteAWaitingBindingWhenAPinnedContractWasRetired() {
        var identity = new EngineDeploymentIdentity(
                "deployment-7", "invoice:4:991", "invoice", 4, "tenant-a");
        when(bindings.findDraftByOrchestrationRelease("release-1"))
                .thenReturn(List.of(draftBinding()));
        stubPinnedReleases(identity, ReleaseStatus.RETIRED);

        service.report("release-1", ReleaseStatus.ACTIVE, identity, null);

        verify(bindings, never()).activate(any());
        verify(bindings).fail(org.mockito.ArgumentMatchers.eq("tenant-a:invoice:4"),
                contains("contract-1"));
    }

    private void stubPinnedReleases(
            EngineDeploymentIdentity identity, ReleaseStatus contractStatus) {
        when(releases.require("release-1", "tenant-a")).thenReturn(release(
                "release-1", ReleaseKind.ORCHESTRATION, ReleaseStatus.ACTIVE, "o", identity));
        when(releases.require("contract-1", "tenant-a")).thenReturn(release(
                "contract-1", ReleaseKind.CONTRACT, contractStatus, "c", null));
        when(releases.require("presentation-1", "tenant-a")).thenReturn(release(
                "presentation-1", ReleaseKind.PRESENTATION, ReleaseStatus.ACTIVE, "p", null));
    }

    private static CaseDefinitionVersionBinding draftBinding() {
        return new CaseDefinitionVersionBinding(
                "tenant-a:invoice:4", "invoice", "tenant-a",
                "release-1", "o".repeat(64), "contract-1", "c".repeat(64),
                "presentation-1", "p".repeat(64), ReleaseStatus.DEPLOYING,
                OrchestrationMode.BPMN, BindingStatus.DRAFT, null, null,
                OffsetDateTime.now(), null, null, "alice");
    }

    private static CaseDefinitionRelease release(
            String id, ReleaseKind kind, ReleaseStatus status, String digestSeed,
            EngineDeploymentIdentity identity) {
        return CaseDefinitionRelease.storedWithEngineIdentity(
                id, "invoice", "tenant-a", kind, "application/json",
                "{}".getBytes(StandardCharsets.UTF_8), digestSeed.repeat(64), status,
                identity, null, "alice");
    }
}
