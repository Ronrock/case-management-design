package org.casemgmt.poc;

import org.casemgmt.release.BpmnReleaseValidator;
import org.casemgmt.repo.JsonCodec;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.model.bpmn.Bpmn;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class BpmnComplaintResourcesTest {

    @Test
    void complaintBpmnAndScenarioAMetadataAreStructurallyLoadable() throws Exception {
        byte[] process = bytes("processes/complaint-bpmn.bpmn");
        var index = BpmnReleaseValidator.validate("complaint-bpmn", process,
                "application/bpmn+xml");

        assertThat(index.processIds()).contains("complaint-bpmn");
        assertThat(index.formRefs()).contains("registerForm", "assessForm", "closeForm");
        assertThat(index.milestoneIds()).contains("acknowledged", "decided");
        assertThat(index.candidateGroups()).contains("intake", "handlers");
        assertThat(Bpmn.readModelFromStream(new java.io.ByteArrayInputStream(process)))
                .isNotNull();

        var contract = JsonCodec.toMap(text("definitions/complaint-bpmn-contract.json"));
        var presentation = JsonCodec.toMap(text("definitions/complaint-bpmn-presentation.json"));
        assertThat(contract.get("key")).isEqualTo("complaint-bpmn");
        assertThat(presentation.get("version")).isEqualTo("1.0");
    }

    private static byte[] bytes(String path) throws Exception {
        return new ClassPathResource(path).getInputStream().readAllBytes();
    }

    private static String text(String path) throws Exception {
        return new String(bytes(path), StandardCharsets.UTF_8);
    }
}
