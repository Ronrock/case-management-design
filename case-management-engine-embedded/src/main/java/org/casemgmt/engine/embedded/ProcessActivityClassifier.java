package org.casemgmt.engine.embedded;

import org.casemgmt.projection.ActivityObservation;

import java.util.Optional;
import java.util.List;

public interface ProcessActivityClassifier {

    record Classification(ActivityObservation.Kind kind, String milestoneId) { }
    record TaskMetadata(List<String> candidateGroups, String formKey) {
        public TaskMetadata {
            candidateGroups = candidateGroups == null ? List.of() : List.copyOf(candidateGroups);
        }
    }

    Optional<Classification> classify(String processDefinitionId, String activityId);

    default TaskMetadata taskMetadata(String processDefinitionId, String activityId) {
        return new TaskMetadata(List.of(), null);
    }
}
