package org.casemgmt.release;

import org.casemgmt.error.InvalidCaseDefinitionException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
