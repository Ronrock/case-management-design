package org.casemgmt.poc;

import org.casemgmt.orchestration.OrchestrationMode;
import org.casemgmt.release.BpmnReleaseValidator;
import org.casemgmt.release.JsonSchemaCaseContractValidator;
import org.casemgmt.release.ValidatedCaseContract;
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
        assertThat(index.slaRefs()).contains("resolution");
        assertThat(Bpmn.readModelFromStream(new java.io.ByteArrayInputStream(process)))
                .isNotNull();

        var contract = JsonCodec.toMap(text("definitions/complaint-bpmn-contract.json"));
        var presentation = JsonCodec.toMap(text("definitions/complaint-bpmn-presentation.json"));
        assertThat(contract.get("key")).isEqualTo("complaint-bpmn");
        assertThat(presentation.get("version")).isEqualTo("1.0");
    }

    /**
     * WS1-AC1: the shipped example is the reference for what a valid BPMN-first bundle looks
     * like, so it has to survive the same publication gate a customer bundle does — schema
     * validation, an explicitly declared mode, and every BPMN reference resolving into the
     * contract. Asserting only that the JSON parses would let the example drift out of
     * conformance while still looking fine.
     */
    @Test
    void complaintContractPassesPublicationValidationAndResolvesEveryBpmnReference() throws Exception {
        ValidatedCaseContract contract = new JsonSchemaCaseContractValidator()
                .validate("complaint-bpmn", bytes("definitions/complaint-bpmn-contract.json"));

        assertThat(contract.orchestrationMode()).isEqualTo(OrchestrationMode.BPMN);

        var index = BpmnReleaseValidator.validate("complaint-bpmn",
                bytes("processes/complaint-bpmn.bpmn"), "application/bpmn+xml");

        assertThat(contract.forms().keySet()).containsAll(index.formRefs());
        assertThat(contract.candidateGroups()).containsAll(index.candidateGroups());
        assertThat(contract.slaTargetIds()).containsAll(index.slaRefs());
    }

    private static byte[] bytes(String path) throws Exception {
        return new ClassPathResource(path).getInputStream().readAllBytes();
    }

    private static String text(String path) throws Exception {
        return new String(bytes(path), StandardCharsets.UTF_8);
    }
}
