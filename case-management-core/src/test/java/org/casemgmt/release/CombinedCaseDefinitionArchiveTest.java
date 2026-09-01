package org.casemgmt.release;

import org.casemgmt.error.InvalidCaseDefinitionException;
import org.casemgmt.error.NotFoundException;
import org.casemgmt.repo.CaseDefinitionReleaseRepository;
import org.casemgmt.repo.CaseDefinitionVersionBindingRepository;
import org.casemgmt.service.CaseDefinitionService;
import org.casemgmt.service.CaseDefinitionReleaseService;
import org.casemgmt.service.CaseDefinitionVersionService;
import org.casemgmt.service.CombinedCaseDefinitionDeploymentService;
import org.casemgmt.sla.SlaCalendarCatalog;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CombinedCaseDefinitionArchiveTest {

    @Test
    void missingCalendarRevisionRejectsCombinedBundleBeforeAnyReleaseEvidence() throws Exception {
        CaseDefinitionReleaseRepository releaseRepository = mock(CaseDefinitionReleaseRepository.class);
        var deployments = mock(org.casemgmt.orchestration.OrchestrationDeploymentPort.class);
        SlaCalendarCatalog calendars = mock(SlaCalendarCatalog.class);
        when(calendars.require("t1", "support", 4))
                .thenThrow(new NotFoundException("SlaCalendarRevision", "t1/support/4"));
        CaseDefinitionReleaseService releases = new CaseDefinitionReleaseService(
                releaseRepository, deployments, new JsonSchemaCaseContractValidator(), calendars);
        CaseDefinitionVersionService versions = new CaseDefinitionVersionService(
                mock(CaseDefinitionReleaseRepository.class),
                mock(CaseDefinitionVersionBindingRepository.class),
                mock(CaseDefinitionService.class), calendars);
        byte[] archive = zip(Map.of(
                "processes/sample-case.bpmn", """
                        <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL">
                          <process id="sample-case" isExecutable="true"/>
                        </definitions>""",
                "contract.json", "{\"key\":\"sample-case\",\"orchestrationMode\":\"BPMN\","
                        + "\"fields\":{},\"forms\":{},\"slaBindings\":{\"resolution\":{"
                        + "\"scope\":\"CASE\",\"calendarId\":\"support\",\"calendarRevision\":4,"
                        + "\"duration\":\"PT1H\",\"startAnchor\":\"CASE_CREATED\","
                        + "\"meetAnchor\":\"CASE_CLOSED\"}}}",
                "presentation.json", "{\"version\":\"1.0\",\"sections\":[]}"));

        assertThatThrownBy(() -> new CombinedCaseDefinitionDeploymentService(releases, versions)
                .deploy("t1", archive, "alice"))
                .isInstanceOf(InvalidCaseDefinitionException.class)
                .hasMessageContaining("support")
                .hasMessageContaining("revision 4");

        verifyNoInteractions(releaseRepository, deployments);
    }

    @Test
    void readsRequiredArtifactsAndBuildsAnOrchestrationOnlyArchive() throws Exception {
        byte[] archive = zip(Map.of(
                "processes/sample-case.bpmn", "<definitions/>",
                "decisions/priority.dmn", "<definitions/>",
                "contract.json", "{\"key\":\"sample-case\"}",
                "presentation.json", "{\"version\":\"1.0\",\"sections\":[]}"
        ));

        CombinedCaseDefinitionArchive parsed = CombinedCaseDefinitionArchive.read(
                "sample-case", archive);

        assertThat(parsed.contractJson()).contains("sample-case");
        assertThat(parsed.presentationJson()).contains("sections");
        assertThat(parsed.orchestrationZip()).isNotEmpty();
    }

    @Test
    void rejectsZipTraversal() throws Exception {
        byte[] archive = zip(Map.of(
                "../process.bpmn", "<definitions/>",
                "contract.json", "{}",
                "presentation.json", "{}"));

        assertThatThrownBy(() -> CombinedCaseDefinitionArchive.read("sample-case", archive))
                .isInstanceOf(InvalidCaseDefinitionException.class)
                .hasMessageContaining("unsafe ZIP path");
    }

    /**
     * Workstream 1, Task 4: an invalid combined bundle must leave nothing behind.
     *
     * <p>Rolling back inside the transaction is not the same guarantee. Publication happens
     * before binding, so validating only at bind time means three release rows are written and
     * then withdrawn — an operator watching the release list sees them appear, and any
     * non-transactional side effect of publication has already happened. Verifying that neither
     * collaborator is touched at all is what pins validation ahead of the first write.
     */
    @Test
    void rejectsAnInvalidCombinedContractBeforePublishingAnyRelease() throws Exception {
        CaseDefinitionReleaseService releases = mock(CaseDefinitionReleaseService.class);
        CaseDefinitionVersionService versions = mock(CaseDefinitionVersionService.class);
        byte[] archive = zip(Map.of(
                "processes/sample-case.bpmn", "<definitions/>",
                "contract.json", "{\"key\":\"sample-case\",\"orchestrationMode\":\"BPMN\","
                        + "\"forms\":{},\"fields\":{},\"slaBindings\":{\"resolution\":{"
                        + "\"scope\":\"CASE\",\"calendarId\":\"nl-business\",\"duration\":\"P5D\","
                        + "\"startAnchor\":\"CASE_CREATED\",\"meetAnchor\":\"CASE_CLOSED\","
                        + "\"warnngs\":[\"P4D\"]}}}",
                "presentation.json", "{\"version\":\"1.0\",\"sections\":[]}"));

        assertThatThrownBy(() -> new CombinedCaseDefinitionDeploymentService(releases, versions)
                .deploy("t1", archive, "alice"))
                .isInstanceOf(InvalidCaseDefinitionException.class)
                .hasMessageContaining("/slaBindings/resolution/warnngs");

        verifyNoInteractions(releases, versions);
    }

    @Test
    void rejectsAnUndeclaredOrchestrationModeBeforePublishingAnyRelease() throws Exception {
        CaseDefinitionReleaseService releases = mock(CaseDefinitionReleaseService.class);
        CaseDefinitionVersionService versions = mock(CaseDefinitionVersionService.class);
        byte[] archive = zip(Map.of(
                "processes/sample-case.bpmn", "<definitions/>",
                "contract.json", "{\"key\":\"sample-case\",\"forms\":{}}",
                "presentation.json", "{\"version\":\"1.0\",\"sections\":[]}"));

        assertThatThrownBy(() -> new CombinedCaseDefinitionDeploymentService(releases, versions)
                .deploy("t1", archive, "alice"))
                .isInstanceOf(InvalidCaseDefinitionException.class)
                .hasMessageContaining("orchestrationMode");

        verifyNoInteractions(releases, versions);
    }

    @Test
    void rejectsCrossArtifactMismatchBeforePublishingOrDeployingAnything() throws Exception {
        var releaseRepository = mock(org.casemgmt.repo.CaseDefinitionReleaseRepository.class);
        var deployments = mock(org.casemgmt.orchestration.OrchestrationDeploymentPort.class);
        CaseDefinitionReleaseService releases = new CaseDefinitionReleaseService(
                releaseRepository, deployments);
        CaseDefinitionVersionService versions = new CaseDefinitionVersionService(
                mock(org.casemgmt.repo.CaseDefinitionReleaseRepository.class),
                mock(org.casemgmt.repo.CaseDefinitionVersionBindingRepository.class),
                mock(org.casemgmt.service.CaseDefinitionService.class),
                (tenantId, calendarId, revision) -> {
                    throw new AssertionError("test contract unexpectedly referenced an SLA calendar");
                });
        byte[] archive = zip(Map.of(
                "processes/sample-case.bpmn", """
                        <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                     xmlns:operaton="http://operaton.org/schema/1.0/bpmn">
                          <process id="sample-case" isExecutable="true">
                            <userTask id="review" operaton:formKey="missingForm"/>
                          </process>
                        </definitions>""",
                "contract.json", "{\"key\":\"sample-case\",\"orchestrationMode\":\"BPMN\","
                        + "\"fields\":{},\"forms\":{}}",
                "presentation.json", "{\"version\":\"1.0\",\"sections\":[]}"));

        assertThatThrownBy(() -> new CombinedCaseDefinitionDeploymentService(releases, versions)
                .deploy("t1", archive, "alice"))
                .isInstanceOf(InvalidCaseDefinitionException.class)
                .hasMessageContaining("missingForm");

        verifyNoInteractions(releaseRepository, deployments);
    }

    @Test
    void rejectsUnsupportedPresentationVersionBeforePublishingOrDeployingAnything() throws Exception {
        var releaseRepository = mock(org.casemgmt.repo.CaseDefinitionReleaseRepository.class);
        var deployments = mock(org.casemgmt.orchestration.OrchestrationDeploymentPort.class);
        CaseDefinitionReleaseService releases = new CaseDefinitionReleaseService(
                releaseRepository, deployments);
        CaseDefinitionVersionService versions = mock(CaseDefinitionVersionService.class);
        byte[] archive = zip(Map.of(
                "processes/sample-case.bpmn", """
                        <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL">
                          <process id="sample-case" isExecutable="true"/>
                        </definitions>""",
                "contract.json", "{\"key\":\"sample-case\",\"orchestrationMode\":\"BPMN\","
                        + "\"fields\":{},\"forms\":{}}",
                "presentation.json", "{\"version\":\"2.0\",\"sections\":[]}"));

        assertThatThrownBy(() -> new CombinedCaseDefinitionDeploymentService(releases, versions)
                .deploy("t1", archive, "alice"))
                .isInstanceOf(InvalidCaseDefinitionException.class)
                .hasMessageContaining("version");

        verifyNoInteractions(releaseRepository, deployments, versions);
    }

    private static byte[] zip(Map<String, String> entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (var entry : new LinkedHashMap<>(entries).entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}
