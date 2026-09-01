package org.casemgmt.release;

import org.casemgmt.error.InvalidCaseDefinitionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BpmnReleaseValidatorHardeningTest {

    private static final String KEY = "namespace-case";
    private static final String BPMN_NS = "http://www.omg.org/spec/BPMN/20100524/MODEL";
    private static final String DMN_13_NS = "https://www.omg.org/spec/DMN/20191111/MODEL/";

    @Test
    void rejectsForeignBpmnDocumentRootEvenWhenItContainsANamespacedProcess() {
        String bpmn = """
                <foreign:definitions xmlns:foreign="https://example.invalid/bpmn"
                                     xmlns:bpmn="%s">
                  <bpmn:process id="%s"/>
                </foreign:definitions>
                """.formatted(BPMN_NS, KEY);

        assertThatThrownBy(() -> validateBpmn(bpmn))
                .isInstanceOf(InvalidCaseDefinitionException.class)
                .hasMessageContaining("BPMN document root")
                .hasMessageContaining(BPMN_NS);
    }

    @Test
    void doesNotIndexForeignNamespaceProcessesOrUserTasks() {
        String bpmn = """
                <bpmn:definitions xmlns:bpmn="%s"
                                  xmlns:foreign="https://example.invalid/bpmn"
                                  xmlns:operaton="%s">
                  <bpmn:process id="%s">
                    <foreign:userTask id="poison-task" operaton:formKey="poison-form"/>
                  </bpmn:process>
                  <foreign:process id="poison-process"/>
                </bpmn:definitions>
                """.formatted(BPMN_NS, BpmnReleaseValidator.OPERATON_NS, KEY);

        BpmnReleaseValidator.Index index = validateBpmn(bpmn);

        assertThat(index.processIds()).containsExactly(KEY);
        assertThat(index.formRefs()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://www.omg.org/spec/DMN/20151101/dmn.xsd",
            "http://www.omg.org/spec/DMN/20151101/dmn11.xsd",
            "http://www.omg.org/spec/DMN/20180521/MODEL/",
            "https://www.omg.org/spec/DMN/20191111/MODEL/",
            "https://www.omg.org/spec/DMN/20191111/DMN13.xsd",
            "https://www.omg.org/spec/DMN/20211108/MODEL/",
            "https://www.omg.org/spec/DMN/20230324/MODEL/"
    })
    void indexesDecisionsFromEveryDmnNamespaceSupportedByOperaton(String dmnNamespace)
            throws Exception {
        BpmnReleaseValidator.Index index = validateArchive(dmnNamespace, "dmn:decision");

        assertThat(index.decisionIds()).containsExactly("risk-decision");
    }

    @Test
    void rejectsForeignDmnDocumentRootEvenWhenItContainsANamespacedDecision() throws Exception {
        byte[] archive = archive(bpmnReferencingDecision(), """
                <foreign:definitions xmlns:foreign="https://example.invalid/dmn"
                                     xmlns:dmn="%s">
                  <dmn:decision id="risk-decision"/>
                </foreign:definitions>
                """.formatted(DMN_13_NS));

        assertThatThrownBy(() -> BpmnReleaseValidator.validate(KEY, archive, "application/zip"))
                .isInstanceOf(InvalidCaseDefinitionException.class)
                .hasMessageContaining("DMN document root")
                .hasMessageContaining(DMN_13_NS);
    }

    @Test
    void doesNotResolveDecisionFromAForeignNamespace() throws Exception {
        assertThatThrownBy(() -> validateArchive(DMN_13_NS, "foreign:decision"))
                .isInstanceOf(InvalidCaseDefinitionException.class)
                .hasMessageContaining("unbundled decision 'risk-decision'");
    }

    @Test
    void stopsAtDecompressedSizeLimitBeforeReadingTheRestOfTheEntry() throws Exception {
        byte[] archive = oversizedArchiveWithCorruptTrailingCrc();

        assertThatThrownBy(() -> BpmnReleaseValidator.validate(KEY, archive, "application/zip"))
                .isInstanceOf(InvalidCaseDefinitionException.class)
                .hasMessageContaining("decompressed-size limit")
                .hasMessageNotContaining("CRC");
    }

    private static BpmnReleaseValidator.Index validateBpmn(String bpmn) {
        return BpmnReleaseValidator.validate(KEY, bpmn.getBytes(StandardCharsets.UTF_8),
                "application/bpmn+xml");
    }

    private static BpmnReleaseValidator.Index validateArchive(String dmnNamespace,
                                                               String decisionElement)
            throws Exception {
        String dmn = """
                <dmn:definitions xmlns:dmn="%s" xmlns:foreign="https://example.invalid/dmn">
                  <%s id="risk-decision"/>
                </dmn:definitions>
                """.formatted(dmnNamespace, decisionElement);
        return BpmnReleaseValidator.validate(KEY, archive(bpmnReferencingDecision(), dmn),
                "application/zip");
    }

    private static String bpmnReferencingDecision() {
        return """
                <bpmn:definitions xmlns:bpmn="%s" xmlns:operaton="%s">
                  <bpmn:process id="%s">
                    <bpmn:businessRuleTask id="assess-risk"
                                           operaton:decisionRef="risk-decision"/>
                  </bpmn:process>
                </bpmn:definitions>
                """.formatted(BPMN_NS, BpmnReleaseValidator.OPERATON_NS, KEY);
    }

    private static byte[] archive(String bpmn, String dmn) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            write(zip, "process.bpmn", bpmn.getBytes(StandardCharsets.UTF_8));
            write(zip, "decision.dmn", dmn.getBytes(StandardCharsets.UTF_8));
        }
        return bytes.toByteArray();
    }

    private static byte[] oversizedArchiveWithCorruptTrailingCrc() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("oversized.bpmn"));
            byte[] block = new byte[8192];
            int remaining = BpmnReleaseValidator.MAX_DECOMPRESSED_BYTES + 1;
            while (remaining > 0) {
                int written = Math.min(remaining, block.length);
                zip.write(block, 0, written);
                remaining -= written;
            }
            zip.closeEntry();
        }
        byte[] archive = bytes.toByteArray();
        int descriptor = lastIndexOf(archive, new byte[] {0x50, 0x4b, 0x07, 0x08});
        assertThat(descriptor).as("ZIP data descriptor").isGreaterThanOrEqualTo(0);
        archive[descriptor + 4] ^= 1;
        return archive;
    }

    private static int lastIndexOf(byte[] value, byte[] needle) {
        for (int i = value.length - needle.length; i >= 0; i--) {
            boolean found = true;
            for (int j = 0; j < needle.length; j++) {
                if (value[i + j] != needle[j]) {
                    found = false;
                    break;
                }
            }
            if (found) return i;
        }
        return -1;
    }

    private static void write(ZipOutputStream zip, String name, byte[] content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content);
        zip.closeEntry();
    }
}
