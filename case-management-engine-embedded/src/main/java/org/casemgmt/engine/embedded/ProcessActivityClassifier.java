package org.casemgmt.engine.embedded;

import java.util.Optional;
import java.util.List;

public interface ProcessActivityClassifier {

    enum Kind { STAGE, MILESTONE }

    record Classification(Kind kind, String milestoneId, String slaTargetId) {
        public Classification(Kind kind, String milestoneId) {
            this(kind, milestoneId, null);
        }
    }
    record TaskMetadata(List<String> candidateGroups, String formKey, String slaTargetId) {
        public TaskMetadata {
            candidateGroups = candidateGroups == null ? List.of() : List.copyOf(candidateGroups);
        }
        public TaskMetadata(List<String> candidateGroups, String formKey) {
            this(candidateGroups, formKey, null);
        }
    }

    Optional<Classification> classify(String processDefinitionId, String activityId);

    default TaskMetadata taskMetadata(String processDefinitionId, String activityId) {
        return new TaskMetadata(List.of(), null, null);
    }
}
