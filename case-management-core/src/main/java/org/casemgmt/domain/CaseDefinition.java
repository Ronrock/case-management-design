package org.casemgmt.domain;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public record CaseDefinition(
        String id, String key, int versionNo, String name, String tenantId,
        String description, String slaPolicyId,
        List<String> roles, List<String> attachmentCategories,
        Map<String, Object> forms,
        List<PlanItemDefinition> planItems,
        OffsetDateTime deployedAt, String deployedBy) {

    public PlanItemDefinition planItem(String defKey) {
        return planItems.stream().filter(p -> p.defKey().equals(defKey)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No plan item '" + defKey + "' in " + id));
    }
}
