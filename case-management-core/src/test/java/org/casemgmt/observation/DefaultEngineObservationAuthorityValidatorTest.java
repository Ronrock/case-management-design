package org.casemgmt.observation;

import org.casemgmt.domain.CaseInstance;
import org.casemgmt.domain.CasePriority;
import org.casemgmt.domain.CaseState;
import org.casemgmt.domain.CaseTask;
import org.casemgmt.orchestration.EngineDeploymentIdentity;
import org.casemgmt.orchestration.OrchestrationMode;
import org.casemgmt.projection.ProjectionStatus;
import org.casemgmt.release.BindingStatus;
import org.casemgmt.release.CaseDefinitionVersionBinding;
import org.casemgmt.release.ReleaseStatus;
import org.casemgmt.repo.CaseDefinitionVersionBindingRepository;
import org.casemgmt.repo.LinkedProcessRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefaultEngineObservationAuthorityValidatorTest {

    private CaseDefinitionVersionBindingRepository bindings;
    private LinkedProcessRepository processes;
    private DefaultEngineObservationAuthorityValidator validator;

    @BeforeEach
    void setUp() {
        bindings = mock(CaseDefinitionVersionBindingRepository.class);
        processes = mock(LinkedProcessRepository.class);
        validator = new DefaultEngineObservationAuthorityValidator(bindings, processes, "engine-a");
    }

    @Test
    void acceptsExactRootIdentityForActiveOrRetiredInFlightBinding() {
        CaseInstance caseInstance = activeCase();
        when(processes.findByCase("case-1")).thenReturn(List.of(rootLink()));
        for (BindingStatus status : List.of(BindingStatus.ACTIVE, BindingStatus.RETIRED)) {
            when(bindings.find("claim:1")).thenReturn(Optional.of(binding(status,
                    OrchestrationMode.BPMN)));

            assertThatCode(() -> validator.validate(observation("root-process", "claim-process",
                    "claim-process:7", "engine-a"), caseInstance)).doesNotThrowAnyException();
        }
    }

    @Test
    void rejectsPlanModelAndEveryInexactRootAuthorityCoordinate() {
        CaseInstance caseInstance = activeCase();
        when(processes.findByCase("case-1")).thenReturn(List.of(rootLink()));
        when(bindings.find("claim:1")).thenReturn(Optional.of(binding(BindingStatus.ACTIVE,
                OrchestrationMode.PLAN_MODEL)));
        assertRejected(observation("root-process", "claim-process", "claim-process:7", "engine-a"),
                caseInstance, ObservationRejectionReason.NON_BPMN_BINDING);

        when(bindings.find("claim:1")).thenReturn(Optional.of(binding(BindingStatus.ACTIVE,
                OrchestrationMode.BPMN)));
        assertRejected(observation("root-process", "claim-process", "wrong-definition", "engine-a"),
                caseInstance, ObservationRejectionReason.PROCESS_DEFINITION_MISMATCH);
        assertRejected(observation("root-process", "claim-process", "claim-process:7", "engine-b"),
                caseInstance, ObservationRejectionReason.ENGINE_MISMATCH);
    }

    @Test
    void linkedChildUsesItsPersistedDefinitionKeyWithoutPretendingItIsTheRootDefinition() {
        CaseInstance caseInstance = activeCase();
        when(bindings.find("claim:1")).thenReturn(Optional.of(binding(BindingStatus.RETIRED,
                OrchestrationMode.BPMN)));
        when(processes.findByCase("case-1")).thenReturn(List.of(rootLink(), new LinkedProcessRepository.LinkedProcessRow(
                "child-link", "case-1", null, "child-correlation", "child-process",
                "child-work:3", "child-work", "ACTIVE", CaseTask.EngineSync.SYNCED, false)));

        assertThatCode(() -> validator.validate(observation("child-process", "child-work",
                "child-work:3", "engine-a"), caseInstance)).doesNotThrowAnyException();
        assertRejected(observation("child-process", "other-child", "child-work:3", "engine-a"),
                caseInstance, ObservationRejectionReason.PROCESS_DEFINITION_MISMATCH);
        assertRejected(observation("child-process", "child-work", "child-work:4", "engine-a"),
                caseInstance, ObservationRejectionReason.PROCESS_DEFINITION_MISMATCH);
    }

    @Test
    void rejectsLegacyChildWithoutExactDefinitionIdentityForReconciliation() {
        CaseInstance caseInstance = activeCase();
        when(bindings.find("claim:1")).thenReturn(Optional.of(binding(BindingStatus.ACTIVE,
                OrchestrationMode.BPMN)));
        when(processes.findByCase("case-1")).thenReturn(List.of(rootLink(),
                new LinkedProcessRepository.LinkedProcessRow("child-link", "case-1", null,
                        "child-correlation", "child-process", null, "child-work", "ACTIVE",
                        CaseTask.EngineSync.SYNCED, false)));

        assertRejected(observation("child-process", "child-work", "child-work:3", "engine-a"),
                caseInstance, ObservationRejectionReason.RECONCILIATION_REQUIRED);
    }

    private void assertRejected(ProcessObservation observation, CaseInstance caseInstance,
                                ObservationRejectionReason reason) {
        assertThatThrownBy(() -> validator.validate(observation, caseInstance))
                .isInstanceOf(ObservationAuthorityException.class)
                .extracting(error -> ((ObservationAuthorityException) error).reason())
                .isEqualTo(reason);
    }

    private static ProcessObservation observation(String processId, String definitionKey,
                                                  String definitionId, String engineId) {
        Instant at = Instant.parse("2026-08-28T08:30:00Z");
        return new ProcessObservation("obs-1", 1, "adapter:embedded", engineId, "tenant-a", "case-1",
                processId, processId, 1L, ProcessObservation.EventType.STARTED, at, at,
                Map.of("processDefinitionKey", definitionKey,
                        "processDefinitionId", definitionId));
    }

    private static CaseDefinitionVersionBinding binding(BindingStatus status,
                                                        OrchestrationMode mode) {
        OffsetDateTime at = OffsetDateTime.parse("2026-08-27T10:00:00Z");
        return new CaseDefinitionVersionBinding("claim:1", "claim", "tenant-a",
                "orchestration", "sha-o", "contract", "sha-c", "presentation", "sha-p",
                ReleaseStatus.ACTIVE, mode, status,
                mode == OrchestrationMode.BPMN
                        ? new EngineDeploymentIdentity("deployment-7", "claim-process:7",
                                "claim-process", 7, "tenant-a") : null,
                null, at, at, status == BindingStatus.RETIRED ? at.plusDays(1) : null, "admin");
    }

    private static LinkedProcessRepository.LinkedProcessRow rootLink() {
        return new LinkedProcessRepository.LinkedProcessRow("root-link", "case-1", null,
                "root-correlation", "root-process", "claim-process:7", "claim-process", "ACTIVE",
                CaseTask.EngineSync.SYNCED, true);
    }

    private static CaseInstance activeCase() {
        OffsetDateTime at = Instant.parse("2026-08-28T08:00:00Z").atOffset(ZoneOffset.UTC);
        return new CaseInstance("case-1", "engine-a", "tenant-a", "claim:1", "claim", 1,
                "business-1", "Claim", CaseState.ACTIVE, CasePriority.MEDIUM, null, null,
                "starter", "NONE", null, null, Map.of(), 7, at, at, null,
                "root-process", ProjectionStatus.CURRENT, null, at);
    }
}
