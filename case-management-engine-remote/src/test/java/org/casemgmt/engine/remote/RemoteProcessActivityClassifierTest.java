package org.casemgmt.engine.remote;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.casemgmt.projection.ActivityObservation;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class RemoteProcessActivityClassifierTest {

    private static final String BPMN_NAMESPACE =
            "http://www.omg.org/spec/BPMN/20100524/MODEL";
    private static final String OPERATON_NAMESPACE =
            "http://operaton.org/schema/1.0/bpmn";
    private static final String CASE_MANAGEMENT_NAMESPACE = "https://casemgmt.org/bpmn";

    @Test
    void readsExtensionAttributesByNamespaceUriRegardlessOfPrefix() {
        Fixture fixture = fixtureFor("""
                <definitions xmlns="%s"
                             xmlns:engine="%s"
                             xmlns:case="%s"
                             targetNamespace="urn:test">
                  <process id="case-process">
                    <userTask id="review" engine:candidateGroups="reviewers, managers"
                              engine:formKey="review-form" />
                    <subProcess id="assessment" case:stage="true" />
                    <intermediateThrowEvent id="approved" case:milestoneId="approved" />
                  </process>
                </definitions>
                """.formatted(BPMN_NAMESPACE, OPERATON_NAMESPACE, CASE_MANAGEMENT_NAMESPACE));

        assertThat(fixture.classifier().taskMetadata("definition", "review"))
                .isEqualTo(new RemoteProcessActivityClassifier.TaskMetadata(
                        List.of("reviewers", "managers"), "review-form"));
        assertThat(fixture.classifier().classify("definition", "assessment"))
                .contains(new RemoteProcessActivityClassifier.Classification(
                        ActivityObservation.Kind.STAGE, null));
        assertThat(fixture.classifier().classify("definition", "approved"))
                .contains(new RemoteProcessActivityClassifier.Classification(
                        ActivityObservation.Kind.MILESTONE, "approved"));
        fixture.server().verify();
    }

    @Test
    void ignoresSupportedExtensionNamesOutsideTheirRequiredNamespaces() {
        Fixture fixture = fixtureFor("""
                <definitions xmlns="%s"
                             xmlns:engine="urn:not-operaton"
                             xmlns:case="urn:not-case-management"
                             targetNamespace="urn:test">
                  <process id="case-process">
                    <userTask id="review"
                              candidateGroups="unnamespaced"
                              formKey="unnamespaced-form"
                              engine:candidateGroups="wrong-uri"
                              engine:formKey="wrong-uri-form" />
                    <subProcess id="assessment" case:stage="true" />
                    <intermediateThrowEvent id="approved" case:milestoneId="approved" />
                  </process>
                </definitions>
                """.formatted(BPMN_NAMESPACE));

        assertThat(fixture.classifier().taskMetadata("definition", "review"))
                .isEqualTo(new RemoteProcessActivityClassifier.TaskMetadata(List.of(), null));
        assertThat(fixture.classifier().classify("definition", "assessment")).isEmpty();
        assertThat(fixture.classifier().classify("definition", "approved")).isEmpty();
        fixture.server().verify();
    }

    @Test
    void rejectsDefinitionsRootOutsideTheBpmnNamespace() {
        Fixture fixture = fixtureFor("""
                <definitions xmlns="urn:not-bpmn"
                             xmlns:bpmn="%s"
                             xmlns:engine="%s"
                             targetNamespace="urn:test">
                  <bpmn:process id="case-process">
                    <bpmn:userTask id="review" engine:formKey="review-form" />
                  </bpmn:process>
                </definitions>
                """.formatted(BPMN_NAMESPACE, OPERATON_NAMESPACE));

        assertThatThrownBy(() -> fixture.classifier().taskMetadata("definition", "review"))
                .isInstanceOf(org.casemgmt.engine.EngineException.class)
                .hasMessageContaining("definitions")
                .hasMessageContaining(BPMN_NAMESPACE);
        fixture.server().verify();
    }

    @Test
    void ignoresActivityElementsOutsideTheBpmnNamespace() {
        Fixture fixture = fixtureFor("""
                <definitions xmlns="%s"
                             xmlns:foreign="urn:not-bpmn"
                             xmlns:engine="%s"
                             xmlns:case="%s"
                             targetNamespace="urn:test">
                  <process id="case-process">
                    <foreign:userTask id="foreign-review"
                                      engine:candidateGroups="reviewers"
                                      engine:formKey="review-form"
                                      case:milestoneId="foreign-milestone" />
                    <foreign:subProcess id="foreign-stage" case:stage="true" />
                  </process>
                </definitions>
                """.formatted(BPMN_NAMESPACE, OPERATON_NAMESPACE, CASE_MANAGEMENT_NAMESPACE));

        assertThat(fixture.classifier().taskMetadata("definition", "foreign-review"))
                .isEqualTo(new RemoteProcessActivityClassifier.TaskMetadata(List.of(), null));
        assertThat(fixture.classifier().classify("definition", "foreign-review")).isEmpty();
        assertThat(fixture.classifier().classify("definition", "foreign-stage")).isEmpty();
        fixture.server().verify();
    }

    private static Fixture fixtureFor(String xml) {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://engine.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://engine.test/process-definition/definition/xml"))
                .andRespond(withSuccess(jsonResponse(xml), MediaType.APPLICATION_JSON));
        return new Fixture(new RemoteProcessActivityClassifier(builder.build()), server);
    }

    private static String jsonResponse(String xml) {
        try {
            return new ObjectMapper().writeValueAsString(Map.of("bpmn20Xml", xml));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private record Fixture(RemoteProcessActivityClassifier classifier,
                           MockRestServiceServer server) { }
}
