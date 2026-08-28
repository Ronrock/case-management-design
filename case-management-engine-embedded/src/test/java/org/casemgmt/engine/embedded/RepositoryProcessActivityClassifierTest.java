package org.casemgmt.engine.embedded;

import org.casemgmt.projection.ActivityObservation;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.RepositoryService;
import org.operaton.bpm.model.bpmn.Bpmn;
import org.operaton.bpm.model.bpmn.BpmnModelInstance;
import org.operaton.bpm.model.xml.instance.ModelElementInstance;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RepositoryProcessActivityClassifierTest {

    private static final String BPMN_NAMESPACE =
            "http://www.omg.org/spec/BPMN/20100524/MODEL";
    private static final String OPERATON_NAMESPACE =
            "http://operaton.org/schema/1.0/bpmn";
    private static final String CASE_MANAGEMENT_NAMESPACE = "https://casemgmt.org/bpmn";

    @Test
    void readsExtensionAttributesByNamespaceUriRegardlessOfPrefix() {
        RepositoryProcessActivityClassifier classifier = classifierFor("""
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

        assertThat(classifier.taskMetadata("definition", "review"))
                .isEqualTo(new ProcessActivityClassifier.TaskMetadata(
                        List.of("reviewers", "managers"), "review-form"));
        assertThat(classifier.classify("definition", "assessment"))
                .contains(new ProcessActivityClassifier.Classification(
                        ActivityObservation.Kind.STAGE, null));
        assertThat(classifier.classify("definition", "approved"))
                .contains(new ProcessActivityClassifier.Classification(
                        ActivityObservation.Kind.MILESTONE, "approved"));
    }

    @Test
    void ignoresSupportedExtensionNamesOutsideTheirRequiredNamespaces() {
        RepositoryProcessActivityClassifier classifier = classifierFor("""
                <definitions xmlns="%s"
                             xmlns:engine="urn:not-operaton"
                             xmlns:case="urn:not-case-management"
                             targetNamespace="urn:test">
                  <process id="case-process">
                    <userTask id="review" engine:candidateGroups="wrong-uri"
                              engine:formKey="wrong-uri-form" />
                    <subProcess id="assessment" case:stage="true" />
                    <intermediateThrowEvent id="approved" case:milestoneId="approved" />
                  </process>
                </definitions>
                """.formatted(BPMN_NAMESPACE));

        assertThat(classifier.taskMetadata("definition", "review"))
                .isEqualTo(new ProcessActivityClassifier.TaskMetadata(List.of(), null));
        assertThat(classifier.classify("definition", "assessment")).isEmpty();
        assertThat(classifier.classify("definition", "approved")).isEmpty();
    }

    @Test
    void ignoresUnnamespacedFallbackValuesForOperatonExtensions() {
        ModelElementInstance element = mock(ModelElementInstance.class);
        when(element.getAttributeValueNs(OPERATON_NAMESPACE, "candidateGroups")).thenReturn(null);
        when(element.getAttributeValueNs(OPERATON_NAMESPACE, "formKey")).thenReturn(null);
        when(element.getAttributeValue("candidateGroups")).thenReturn("unnamespaced");
        when(element.getAttributeValue("formKey")).thenReturn("unnamespaced-form");
        BpmnModelInstance model = mock(BpmnModelInstance.class);
        when(model.getModelElementById("review")).thenReturn(element);
        RepositoryService repositoryService = mock(RepositoryService.class);
        when(repositoryService.getBpmnModelInstance("definition")).thenReturn(model);
        RepositoryProcessActivityClassifier classifier =
                new RepositoryProcessActivityClassifier(repositoryService);

        assertThat(classifier.taskMetadata("definition", "review"))
                .isEqualTo(new ProcessActivityClassifier.TaskMetadata(List.of(), null));
    }

    private static RepositoryProcessActivityClassifier classifierFor(String xml) {
        BpmnModelInstance model = Bpmn.readModelFromStream(new ByteArrayInputStream(
                xml.getBytes(StandardCharsets.UTF_8)));
        RepositoryService repositoryService = mock(RepositoryService.class);
        when(repositoryService.getBpmnModelInstance("definition")).thenReturn(model);
        return new RepositoryProcessActivityClassifier(repositoryService);
    }
}
