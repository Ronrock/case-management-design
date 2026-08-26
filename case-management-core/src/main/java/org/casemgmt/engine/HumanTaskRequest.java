package org.casemgmt.engine;

import java.util.List;
import java.util.Map;

public record HumanTaskRequest(String caseId, String planItemId, String name,
                               String assignee, List<String> candidateGroups,
                               String formKey, Map<String, Object> variables,
                               String requestId) {

    public HumanTaskRequest(String caseId, String planItemId, String name,
                            String assignee, List<String> candidateGroups,
                            String formKey, Map<String, Object> variables) {
        this(caseId, planItemId, name, assignee, candidateGroups, formKey, variables, null);
    }
}
