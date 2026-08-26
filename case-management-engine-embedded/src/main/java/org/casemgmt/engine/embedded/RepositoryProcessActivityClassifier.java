package org.casemgmt.engine.embedded;

import org.casemgmt.projection.ActivityObservation;
import org.operaton.bpm.engine.RepositoryService;
import org.operaton.bpm.model.bpmn.instance.SubProcess;
import org.operaton.bpm.model.xml.instance.ModelElementInstance;

import java.util.Optional;
import java.util.Arrays;
import java.util.List;

public final class RepositoryProcessActivityClassifier implements ProcessActivityClassifier {

    static final String CASE_MANAGEMENT_NAMESPACE = "https://casemgmt.org/bpmn";
    static final String OPERATON_NAMESPACE = "http://operaton.org/schema/1.0/bpmn";
    private final RepositoryService repositoryService;

    public RepositoryProcessActivityClassifier(RepositoryService repositoryService) {
        this.repositoryService = repositoryService;
    }

    @Override
    public Optional<Classification> classify(String processDefinitionId, String activityId) {
        if (processDefinitionId == null || activityId == null) return Optional.empty();
        ModelElementInstance element = repositoryService.getBpmnModelInstance(processDefinitionId)
                .getModelElementById(activityId);
        if (element == null) return Optional.empty();
        String milestoneId = element.getAttributeValueNs(CASE_MANAGEMENT_NAMESPACE, "milestoneId");
        if (milestoneId != null && !milestoneId.isBlank()) {
            return Optional.of(new Classification(ActivityObservation.Kind.MILESTONE, milestoneId));
        }
        String stage = element.getAttributeValueNs(CASE_MANAGEMENT_NAMESPACE, "stage");
        if (element instanceof SubProcess && "true".equalsIgnoreCase(stage)) {
            return Optional.of(new Classification(ActivityObservation.Kind.STAGE, null));
        }
        return Optional.empty();
    }

    @Override
    public TaskMetadata taskMetadata(String processDefinitionId, String activityId) {
        if (processDefinitionId == null || activityId == null) {
            return ProcessActivityClassifier.super.taskMetadata(processDefinitionId, activityId);
        }
        ModelElementInstance element = repositoryService.getBpmnModelInstance(processDefinitionId)
                .getModelElementById(activityId);
        if (element == null) {
            return ProcessActivityClassifier.super.taskMetadata(processDefinitionId, activityId);
        }
        String groups = attributeByLocalName(element, "candidateGroups");
        List<String> candidateGroups = groups == null ? List.of() : Arrays.stream(groups.split(","))
                .map(String::trim).filter(value -> !value.isBlank()).toList();
        return new TaskMetadata(candidateGroups, attributeByLocalName(element, "formKey"));
    }

    private static String attributeByLocalName(ModelElementInstance element, String localName) {
        String value = element.getAttributeValueNs(OPERATON_NAMESPACE, localName);
        return value != null ? value : element.getAttributeValue(localName);
    }
}
