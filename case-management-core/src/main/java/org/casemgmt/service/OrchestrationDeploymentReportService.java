package org.casemgmt.service;

import org.casemgmt.orchestration.EngineDeploymentIdentity;
import org.casemgmt.release.ReleaseStatus;
import org.casemgmt.repo.CaseDefinitionReleaseRepository;
import org.casemgmt.repo.CaseDefinitionVersionBindingRepository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

/** Applies a definitive remote deployment result to the release and all waiting bindings. */
public class OrchestrationDeploymentReportService {

    private static final int MAX_FAILURE_DETAIL = 2_000;

    private final CaseDefinitionReleaseRepository releases;
    private final CaseDefinitionVersionBindingRepository bindings;

    public OrchestrationDeploymentReportService(
            CaseDefinitionReleaseRepository releases,
            CaseDefinitionVersionBindingRepository bindings) {
        this.releases = releases;
        this.bindings = bindings;
    }

    @Transactional
    public void report(String releaseId, ReleaseStatus status,
                       EngineDeploymentIdentity identity, String failureDetail) {
        if (status != ReleaseStatus.ACTIVE && status != ReleaseStatus.FAILED) {
            throw new IllegalArgumentException(
                    "Definitive deployment report must be ACTIVE or FAILED");
        }
        if (status == ReleaseStatus.ACTIVE && identity == null) {
            throw new IllegalArgumentException(
                    "An active orchestration deployment requires a verified engine identity");
        }
        String boundedFailure = bound(failureDetail);
        releases.markDeployment(releaseId, status, identity, boundedFailure);
        if (status == ReleaseStatus.FAILED) {
            bindings.failWaitingByRelease(releaseId, boundedFailure);
            return;
        }
        for (var draft : bindings.findDraftByOrchestrationRelease(releaseId)) {
            try {
                var orchestration = releases.require(
                        draft.orchestrationReleaseId(), draft.tenantId());
                var contract = releases.require(draft.contractReleaseId(), draft.tenantId());
                var presentation = releases.require(
                        draft.presentationReleaseId(), draft.tenantId());
                bindings.activate(draft.activate(orchestration, contract, presentation,
                        identity, OffsetDateTime.now()));
            } catch (IllegalStateException invalid) {
                bindings.fail(draft.caseDefinitionId(), bound(invalid.getMessage()));
            }
        }
    }

    private static String bound(String detail) {
        if (detail == null || detail.length() <= MAX_FAILURE_DETAIL) return detail;
        return detail.substring(0, MAX_FAILURE_DETAIL - 3) + "...";
    }
}
