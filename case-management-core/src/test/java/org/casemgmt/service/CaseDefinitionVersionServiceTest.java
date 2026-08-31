package org.casemgmt.service;

import org.casemgmt.domain.CaseDefinition;
import org.casemgmt.error.InvalidCaseDefinitionException;
import org.casemgmt.error.NotFoundException;
import org.casemgmt.orchestration.OrchestrationMode;
import org.casemgmt.release.CaseDefinitionRelease;
import org.casemgmt.release.CaseDefinitionVersionBinding;
import org.casemgmt.release.ReleaseKind;
import org.casemgmt.release.ReleaseStatus;
import org.casemgmt.repo.CaseDefinitionReleaseRepository;
import org.casemgmt.repo.CaseDefinitionVersionBindingRepository;
import org.casemgmt.sla.SlaCalendarCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CaseDefinitionVersionServiceTest {

    @Test
    void directBindingRejectsAMissingTenantCalendarRevisionBeforeDefinitionOrBindingWrites() {
        CaseDefinitionReleaseRepository releases = mock(CaseDefinitionReleaseRepository.class);
        CaseDefinitionVersionBindingRepository bindings =
                mock(CaseDefinitionVersionBindingRepository.class);
        CaseDefinitionService definitions = mock(CaseDefinitionService.class);
        SlaCalendarCatalog calendars = mock(SlaCalendarCatalog.class);
        stubOrchestrationAndPresentation(releases);
        when(releases.require("contract-1", "t1")).thenReturn(release("contract-1",
                ReleaseKind.CONTRACT,
                "{\"key\":\"sample-case\",\"orchestrationMode\":\"BPMN\","
                        + "\"fields\":{},\"forms\":{},\"slaBindings\":{\"resolution\":{"
                        + "\"scope\":\"CASE\",\"calendarId\":\"support\",\"calendarRevision\":4,"
                        + "\"duration\":\"PT1H\",\"startAnchor\":\"CASE_CREATED\","
                        + "\"meetAnchor\":\"CASE_CLOSED\"}}}", "2"));
        when(calendars.require("t1", "support", 4))
                .thenThrow(new NotFoundException("SlaCalendarRevision", "t1/support/4"));

        assertThatThrownBy(() -> new CaseDefinitionVersionService(
                releases, bindings, definitions, calendars).bind(
                "sample-case", "t1", "orch-1", "contract-1", "presentation-1", "alice"))
                .isInstanceOf(InvalidCaseDefinitionException.class)
                .hasMessageContaining("support")
                .hasMessageContaining("revision 4")
                .hasMessageContaining("tenant 't1'");

        verifyNoInteractions(bindings, definitions);
    }

    @ParameterizedTest(name = "rejects {0} contract release")
    @EnumSource(value = ReleaseStatus.class, names = "ACTIVE", mode = EnumSource.Mode.EXCLUDE)
    void publicBindingRejectsEveryNonActiveConstituentRelease(ReleaseStatus status) {
        CaseDefinitionReleaseRepository releases = mock(CaseDefinitionReleaseRepository.class);
        CaseDefinitionVersionBindingRepository bindings =
                mock(CaseDefinitionVersionBindingRepository.class);
        CaseDefinitionService definitions = mock(CaseDefinitionService.class);
        when(releases.require("orch-1", "t1")).thenReturn(release("orch-1",
                ReleaseKind.ORCHESTRATION, """
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL">
                  <process id="sample-case" isExecutable="true"/>
                </definitions>""", "1", "application/bpmn+xml"));
        when(releases.require("contract-1", "t1")).thenReturn(releaseWithStatus(
                "contract-1", ReleaseKind.CONTRACT,
                "{\"key\":\"sample-case\",\"orchestrationMode\":\"BPMN\"," +
                        "\"roles\":[],\"forms\":{},\"fields\":{}}", "2", status));
        when(releases.require("presentation-1", "t1")).thenReturn(release("presentation-1",
                ReleaseKind.PRESENTATION, "{\"version\":\"1.0\",\"sections\":[]}", "3"));

        assertThatThrownBy(() -> new CaseDefinitionVersionService(
                releases, bindings, definitions, noSlaBindings())
                .bind("sample-case", "t1", "orch-1", "contract-1",
                        "presentation-1", "alice"))
                .isInstanceOf(InvalidCaseDefinitionException.class)
                .hasMessageContaining("contract-1")
                .hasMessageContaining(status.name())
                .hasMessageContaining("ACTIVE");

        verifyNoInteractions(bindings, definitions);
    }

    @Test
    void bindsExactReleaseReferencesIntoABpmnDefinitionVersion() {
        CaseDefinitionReleaseRepository releases = mock(CaseDefinitionReleaseRepository.class);
        CaseDefinitionVersionBindingRepository bindings =
                mock(CaseDefinitionVersionBindingRepository.class);
        CaseDefinitionService definitions = mock(CaseDefinitionService.class);
        when(releases.require("orch-1", "t1")).thenReturn(release("orch-1", ReleaseKind.ORCHESTRATION,
                """
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL">
                  <process id="sample-case" isExecutable="true"/>
                </definitions>""", "1", "application/bpmn+xml"));
        when(releases.require("contract-1", "t1")).thenReturn(release("contract-1", ReleaseKind.CONTRACT,
                "{\"key\":\"sample-case\",\"orchestrationMode\":\"BPMN\",\"roles\":[],"
                        + "\"forms\":{},\"fields\":{}}", "2"));
        when(releases.require("presentation-1", "t1")).thenReturn(release("presentation-1",
                ReleaseKind.PRESENTATION, "{\"version\":\"1.0\",\"sections\":[]}", "3"));
        CaseDefinition definition = new CaseDefinition("t1:sample-case:1", "sample-case", 1,
                "Sample case", "t1", null, null, List.of(), List.of(), Map.of(), List.of(),
                OrchestrationMode.BPMN, OffsetDateTime.now(), "alice");
        when(definitions.deployBpmn(eq("sample-case"), any(), eq("alice"), eq("t1")))
                .thenReturn(definition);

        CaseDefinitionVersionBinding bound = new CaseDefinitionVersionService(
                releases, bindings, definitions, noSlaBindings()).bind("sample-case", "t1", "orch-1",
                "contract-1", "presentation-1", "alice");

        assertThat(bound.caseDefinitionId()).isEqualTo("t1:sample-case:1");
        assertThat(bound.orchestrationReleaseId()).isEqualTo("orch-1");
        assertThat(bound.status()).isEqualTo(org.casemgmt.release.BindingStatus.ACTIVE);
        verify(bindings).insert(org.mockito.ArgumentMatchers.argThat(
                draft -> draft.status() == org.casemgmt.release.BindingStatus.DRAFT
                        && draft.engineIdentity() == null));
        verify(bindings).activate(bound);
    }

    @Test
    void combinedRemotePublicationMayCreateOnlyADraftBindingWhileDeploymentIsPending() {
        CaseDefinitionReleaseRepository releases = mock(CaseDefinitionReleaseRepository.class);
        CaseDefinitionVersionBindingRepository bindings =
                mock(CaseDefinitionVersionBindingRepository.class);
        CaseDefinitionService definitions = mock(CaseDefinitionService.class);
        when(releases.require("orch-1", "t1")).thenReturn(releaseWithStatus(
                "orch-1", ReleaseKind.ORCHESTRATION, """
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL">
                  <process id="sample-case" isExecutable="true"/>
                </definitions>""", "1", ReleaseStatus.DEPLOYING));
        when(releases.require("contract-1", "t1")).thenReturn(release("contract-1",
                ReleaseKind.CONTRACT,
                "{\"key\":\"sample-case\",\"orchestrationMode\":\"BPMN\"," +
                        "\"roles\":[],\"forms\":{},\"fields\":{}}", "2"));
        when(releases.require("presentation-1", "t1")).thenReturn(release("presentation-1",
                ReleaseKind.PRESENTATION, "{\"version\":\"1.0\",\"sections\":[]}", "3"));
        CaseDefinition definition = new CaseDefinition("t1:sample-case:2", "sample-case", 2,
                "Sample case", "t1", null, null, List.of(), List.of(), Map.of(), List.of(),
                OrchestrationMode.BPMN, OffsetDateTime.now(), "alice");
        when(definitions.deployBpmn(eq("sample-case"), any(), eq("alice"), eq("t1")))
                .thenReturn(definition);

        CaseDefinitionVersionBinding bound = new CaseDefinitionVersionService(
                releases, bindings, definitions, noSlaBindings()).bindPendingDeployment(
                "sample-case", "t1", "orch-1", "contract-1", "presentation-1", "alice");

        assertThat(bound.status()).isEqualTo(org.casemgmt.release.BindingStatus.DRAFT);
        assertThat(bound.caseDefinitionKey()).isEqualTo("sample-case");
        assertThat(bound.tenantId()).isEqualTo("t1");
        verify(bindings).insert(bound);
        verify(bindings, org.mockito.Mockito.never()).activate(any());
    }

    @Test
    void rejectsAnActiveBpmnReleaseThatHasNoVerifiedEngineIdentity() {
        CaseDefinitionReleaseRepository releases = mock(CaseDefinitionReleaseRepository.class);
        CaseDefinitionVersionBindingRepository bindings =
                mock(CaseDefinitionVersionBindingRepository.class);
        CaseDefinitionService definitions = mock(CaseDefinitionService.class);
        when(releases.require("orch-1", "t1")).thenReturn(CaseDefinitionRelease.stored(
                "orch-1", "sample-case", "t1", ReleaseKind.ORCHESTRATION,
                "application/bpmn+xml", """
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL">
                  <process id="sample-case" isExecutable="true"/>
                </definitions>""".getBytes(StandardCharsets.UTF_8), "1".repeat(64),
                org.casemgmt.release.ReleaseStatus.ACTIVE, "deployment-legacy", null, "alice"));
        when(releases.require("contract-1", "t1")).thenReturn(release("contract-1",
                ReleaseKind.CONTRACT,
                "{\"key\":\"sample-case\",\"orchestrationMode\":\"BPMN\","
                        + "\"roles\":[],\"forms\":{},\"fields\":{}}", "2"));
        when(releases.require("presentation-1", "t1")).thenReturn(release("presentation-1",
                ReleaseKind.PRESENTATION, "{\"version\":\"1.0\",\"sections\":[]}", "3"));

        assertThatThrownBy(() -> new CaseDefinitionVersionService(
                releases, bindings, definitions, noSlaBindings())
                .bind("sample-case", "t1", "orch-1", "contract-1",
                        "presentation-1", "alice"))
                .isInstanceOf(InvalidCaseDefinitionException.class)
                .hasMessageContaining("verified engine identity");
        verifyNoInteractions(bindings, definitions);
    }

    @Test
    void rejectsCandidateGroupNotDeclaredByContract() {
        CaseDefinitionReleaseRepository releases = mock(CaseDefinitionReleaseRepository.class);
        when(releases.require("orch-1", "t1")).thenReturn(release("orch-1",
                ReleaseKind.ORCHESTRATION, """
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:operaton="http://operaton.org/schema/1.0/bpmn">
                  <process id="sample-case" isExecutable="true">
                    <userTask id="review" operaton:candidateGroups="secret-reviewers"/>
                  </process>
                </definitions>""", "1", "application/bpmn+xml"));
        when(releases.require("contract-1", "t1")).thenReturn(release("contract-1",
                ReleaseKind.CONTRACT,
                "{\"key\":\"sample-case\",\"orchestrationMode\":\"BPMN\",\"roles\":[],"
                        + "\"candidateGroups\":[\"handlers\"],\"forms\":{},\"fields\":{}}", "2"));
        when(releases.require("presentation-1", "t1")).thenReturn(release("presentation-1",
                ReleaseKind.PRESENTATION, "{\"version\":\"1.0\",\"sections\":[]}", "3"));

        assertThatThrownBy(() -> new CaseDefinitionVersionService(releases,
                mock(CaseDefinitionVersionBindingRepository.class),
                mock(CaseDefinitionService.class), noSlaBindings()).bind("sample-case", "t1", "orch-1",
                "contract-1", "presentation-1", "alice"))
                .hasMessageContaining("undeclared candidate group 'secret-reviewers'");
    }

    /**
     * Workstream 1: schema validation is a publication gate, not a runtime surprise. A contract
     * whose {@code slaBindings} entry carries a misspelled property (WS1-AC3) must fail here —
     * before {@code deployBpmn} runs and before a binding row exists — with the JSON path the
     * author has to edit. Verifying the collaborators were never touched is the point: a
     * half-applied publication is what leaves an unusable release selectable.
     */
    @Test
    void rejectsAContractThatFailsSchemaValidationBeforeAnythingIsDeployedOrBound() {
        CaseDefinitionReleaseRepository releases = mock(CaseDefinitionReleaseRepository.class);
        CaseDefinitionVersionBindingRepository bindings =
                mock(CaseDefinitionVersionBindingRepository.class);
        CaseDefinitionService definitions = mock(CaseDefinitionService.class);
        stubOrchestrationAndPresentation(releases);
        when(releases.require("contract-1", "t1")).thenReturn(release("contract-1",
                ReleaseKind.CONTRACT,
                "{\"key\":\"sample-case\",\"orchestrationMode\":\"BPMN\",\"forms\":{},"
                        + "\"fields\":{},\"slaBindings\":{\"resolution\":{\"scope\":\"CASE\","
                        + "\"calendarId\":\"nl-business\",\"duration\":\"P5D\","
                        + "\"startAnchor\":\"CASE_CREATED\",\"meetAnchor\":\"CASE_CLOSED\","
                        + "\"warnngs\":[\"P4D\"]}}}", "2"));

        assertThatThrownBy(() -> new CaseDefinitionVersionService(
                releases, bindings, definitions, noSlaBindings())
                .bind("sample-case", "t1", "orch-1", "contract-1", "presentation-1", "alice"))
                .isInstanceOf(InvalidCaseDefinitionException.class)
                .hasMessageContaining("/slaBindings/resolution/warnngs");

        verifyNoInteractions(bindings, definitions);
    }

    /**
     * The bundle declares its orchestration mode; the platform never infers one from which
     * properties happen to be present (design §9.9). Binding a BPMN release to a contract that
     * stays silent is the ambiguity that produces two process authorities, so it fails.
     */
    @Test
    void rejectsAContractThatDoesNotDeclareItsOrchestrationMode() {
        CaseDefinitionReleaseRepository releases = mock(CaseDefinitionReleaseRepository.class);
        CaseDefinitionVersionBindingRepository bindings =
                mock(CaseDefinitionVersionBindingRepository.class);
        CaseDefinitionService definitions = mock(CaseDefinitionService.class);
        stubOrchestrationAndPresentation(releases);
        when(releases.require("contract-1", "t1")).thenReturn(release("contract-1",
                ReleaseKind.CONTRACT,
                "{\"key\":\"sample-case\",\"roles\":[],\"forms\":{},\"fields\":{}}", "2"));

        assertThatThrownBy(() -> new CaseDefinitionVersionService(
                releases, bindings, definitions, noSlaBindings())
                .bind("sample-case", "t1", "orch-1", "contract-1", "presentation-1", "alice"))
                .isInstanceOf(InvalidCaseDefinitionException.class)
                .hasMessageContaining("orchestrationMode");

        verifyNoInteractions(bindings, definitions);
    }

    /**
     * WS1-AC4. Lifecycle and task activation belong to BPMN in BPMN-first mode; a contract that
     * also declares them is rejected with an explanation of which side is authoritative, not a
     * bare "unknown property".
     */
    @Test
    void rejectsABpmnContractThatDeclaresItsOwnLifecycle() {
        CaseDefinitionReleaseRepository releases = mock(CaseDefinitionReleaseRepository.class);
        CaseDefinitionVersionBindingRepository bindings =
                mock(CaseDefinitionVersionBindingRepository.class);
        CaseDefinitionService definitions = mock(CaseDefinitionService.class);
        stubOrchestrationAndPresentation(releases);
        when(releases.require("contract-1", "t1")).thenReturn(release("contract-1",
                ReleaseKind.CONTRACT,
                "{\"key\":\"sample-case\",\"orchestrationMode\":\"BPMN\",\"forms\":{},"
                        + "\"fields\":{},\"planItems\":[{\"defKey\":\"intake\","
                        + "\"type\":\"STAGE\"}]}", "2"));

        assertThatThrownBy(() -> new CaseDefinitionVersionService(
                releases, bindings, definitions, noSlaBindings())
                .bind("sample-case", "t1", "orch-1", "contract-1", "presentation-1", "alice"))
                .isInstanceOf(InvalidCaseDefinitionException.class)
                .hasMessageContaining("/planItems")
                .hasMessageContaining("BPMN orchestration is authoritative");

        verifyNoInteractions(bindings, definitions);
    }

    @Test
    void rejectsPlanModelContractOnBpmnReleaseBindingPath() {
        CaseDefinitionReleaseRepository releases = mock(CaseDefinitionReleaseRepository.class);
        CaseDefinitionVersionBindingRepository bindings =
                mock(CaseDefinitionVersionBindingRepository.class);
        CaseDefinitionService definitions = mock(CaseDefinitionService.class);
        stubOrchestrationAndPresentation(releases);
        when(releases.require("contract-1", "t1")).thenReturn(release("contract-1",
                ReleaseKind.CONTRACT,
                "{\"key\":\"sample-case\",\"orchestrationMode\":\"PLAN_MODEL\",\"forms\":{}}",
                "2"));

        assertThatThrownBy(() -> new CaseDefinitionVersionService(
                releases, bindings, definitions, noSlaBindings())
                .bind("sample-case", "t1", "orch-1", "contract-1", "presentation-1", "alice"))
                .isInstanceOf(InvalidCaseDefinitionException.class)
                .hasMessageContaining("PLAN_MODEL")
                .hasMessageContaining("BPMN");

        verifyNoInteractions(bindings, definitions);
    }

    @Test
    void reportsAllDeterministicCrossArtifactReferenceErrorsTogether() {
        CaseDefinitionReleaseRepository releases = mock(CaseDefinitionReleaseRepository.class);
        CaseDefinitionVersionBindingRepository bindings =
                mock(CaseDefinitionVersionBindingRepository.class);
        CaseDefinitionService definitions = mock(CaseDefinitionService.class);
        when(releases.require("orch-1", "t1")).thenReturn(release("orch-1",
                ReleaseKind.ORCHESTRATION, """
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:operaton="http://operaton.org/schema/1.0/bpmn"
                             xmlns:casemgmt="https://casemgmt.org/bpmn">
                  <process id="sample-case" isExecutable="true">
                    <userTask id="review" operaton:formKey="missingForm"
                              operaton:candidateGroups="missingGroup"
                              casemgmt:slaTargetId="missingSla"/>
                  </process>
                </definitions>""", "1", "application/bpmn+xml"));
        when(releases.require("contract-1", "t1")).thenReturn(release("contract-1",
                ReleaseKind.CONTRACT,
                "{\"key\":\"sample-case\",\"orchestrationMode\":\"BPMN\","
                        + "\"candidateGroups\":[],\"fields\":{},\"forms\":{},\"slaBindings\":{}}",
                "2"));
        when(releases.require("presentation-1", "t1")).thenReturn(release("presentation-1",
                ReleaseKind.PRESENTATION,
                "{\"version\":\"1.0\",\"sections\":[{\"fields\":[\"missingField\"]}]}",
                "3"));

        assertThatThrownBy(() -> new CaseDefinitionVersionService(
                releases, bindings, definitions, noSlaBindings())
                .bind("sample-case", "t1", "orch-1", "contract-1", "presentation-1", "alice"))
                .isInstanceOf(InvalidCaseDefinitionException.class)
                .hasMessageContaining("missingForm")
                .hasMessageContaining("missingGroup")
                .hasMessageContaining("missingSla")
                .hasMessageContaining("missingField");

        verifyNoInteractions(bindings, definitions);
    }

    @Test
    void boundsAndSummarizesLargeNumbersOfCrossArtifactReferenceErrors() {
        CaseDefinitionVersionService service = new CaseDefinitionVersionService(
                mock(CaseDefinitionReleaseRepository.class),
                mock(CaseDefinitionVersionBindingRepository.class),
                mock(CaseDefinitionService.class), noSlaBindings());
        String missingFields = IntStream.range(0, 25)
                .mapToObj(index -> "\"missing-%02d\"".formatted(index))
                .reduce((left, right) -> left + "," + right)
                .orElseThrow();

        assertThatThrownBy(() -> service.validateArtifacts("sample-case", "t1", """
                        <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL">
                          <process id="sample-case" isExecutable="true"/>
                        </definitions>""".getBytes(StandardCharsets.UTF_8),
                "application/bpmn+xml",
                ("{\"key\":\"sample-case\",\"orchestrationMode\":\"BPMN\","
                        + "\"forms\":{},\"fields\":{}}").getBytes(StandardCharsets.UTF_8),
                ("{\"version\":\"1.0\",\"sections\":[{\"fields\":["
                        + missingFields + "]}]}").getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(InvalidCaseDefinitionException.class)
                .hasMessageContaining("missing-00")
                .hasMessageContaining("missing-19")
                .hasMessageNotContaining("missing-20")
                .hasMessageContaining("...and 5 additional reference findings");
    }

    private static SlaCalendarCatalog noSlaBindings() {
        return (tenantId, calendarId, revision) -> {
            throw new AssertionError("test contract unexpectedly referenced an SLA calendar");
        };
    }

    private static void stubOrchestrationAndPresentation(CaseDefinitionReleaseRepository releases) {
        when(releases.require("orch-1", "t1")).thenReturn(release("orch-1",
                ReleaseKind.ORCHESTRATION, """
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL">
                  <process id="sample-case" isExecutable="true"/>
                </definitions>""", "1", "application/bpmn+xml"));
        when(releases.require("presentation-1", "t1")).thenReturn(release("presentation-1",
                ReleaseKind.PRESENTATION, "{\"version\":\"1.0\",\"sections\":[]}", "3"));
    }

    private static CaseDefinitionRelease release(String id, ReleaseKind kind, String content,
                                                  String digestSeed) {
        return release(id, kind, content, digestSeed, "application/json");
    }

    private static CaseDefinitionRelease release(String id, ReleaseKind kind, String content,
                                                  String digestSeed, String mediaType) {
        // Binding is only legal against releases that reached ACTIVE, so these fixtures say so
        // explicitly rather than relying on a factory that used to assume it.
        if (kind == ReleaseKind.ORCHESTRATION) {
            return CaseDefinitionRelease.storedWithEngineIdentity(
                    id, "sample-case", "t1", kind, mediaType,
                    content.getBytes(StandardCharsets.UTF_8), digestSeed.repeat(64),
                    org.casemgmt.release.ReleaseStatus.ACTIVE,
                    new org.casemgmt.orchestration.EngineDeploymentIdentity(
                            "deployment-1", "sample-case:1:100", "sample-case", 1, "t1"),
                    null, "alice");
        }
        return CaseDefinitionRelease.stored(id, "sample-case", "t1", kind, mediaType,
                content.getBytes(StandardCharsets.UTF_8), digestSeed.repeat(64),
                org.casemgmt.release.ReleaseStatus.ACTIVE, null, null, "alice");
    }

    private static CaseDefinitionRelease releaseWithStatus(
            String id, ReleaseKind kind, String content, String digestSeed, ReleaseStatus status) {
        return CaseDefinitionRelease.stored(id, "sample-case", "t1", kind, "application/json",
                content.getBytes(StandardCharsets.UTF_8), digestSeed.repeat(64), status,
                null, null, "alice");
    }
}
