package org.casemgmt.release;

import org.casemgmt.error.InvalidCaseDefinitionException;
import org.casemgmt.service.CaseDefinitionReleaseService;
import org.casemgmt.service.CaseDefinitionVersionService;
import org.casemgmt.service.CombinedCaseDefinitionDeploymentService;
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

class CombinedCaseDefinitionArchiveTest {

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
