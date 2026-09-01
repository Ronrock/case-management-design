package org.casemgmt.domain;

import org.casemgmt.orchestration.OrchestrationMode;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public record CaseDefinition(
        String id, String key, int versionNo, String name, String tenantId,
        String description, String slaPolicyId,
        List<String> roles, List<String> attachmentCategories,
        Map<String, Object> forms,
        List<PlanItemDefinition> planItems,
        OrchestrationMode orchestrationMode,
        OffsetDateTime deployedAt, String deployedBy) {

    public CaseDefinition(String id, String key, int versionNo, String name, String tenantId,
                          String description, String slaPolicyId, List<String> roles,
                          List<String> attachmentCategories, Map<String, Object> forms,
                          List<PlanItemDefinition> planItems, OffsetDateTime deployedAt,
                          String deployedBy) {
        this(id, key, versionNo, name, tenantId, description, slaPolicyId, roles,
                attachmentCategories, forms, planItems, OrchestrationMode.BPMN,
                deployedAt, deployedBy);
    }

    public PlanItemDefinition planItem(String defKey) {
        return planItems.stream().filter(p -> p.defKey().equals(defKey)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No plan item '" + defKey + "' in " + id));
    }
}
