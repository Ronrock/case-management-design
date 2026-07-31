package org.casemgmt.rules;

import org.casemgmt.domain.*;

import java.util.Comparator;
import java.util.List;

public record CaseSnapshot(CaseInstance caseInstance, CaseDefinition definition, List<PlanItem> planItems) {

    /** All runtime instances of a definition key, oldest first (repetition creates several). */
    public List<PlanItem> items(String defKey) {
        return planItems.stream()
                .filter(i -> defKey.equals(defKeyOf(i)))
                .sorted(Comparator.comparing(PlanItem::createdAt))
                .toList();
    }

    /** The most recent instance of a definition key, which is what criteria see. */
    public PlanItem latest(String defKey) {
        List<PlanItem> all = items(defKey);
        return all.isEmpty() ? null : all.get(all.size() - 1);
    }

    public PlanItemDefinition definitionOf(PlanItem item) {
        return definition.planItems().stream()
                .filter(d -> d.id().equals(item.planItemDefId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Plan item " + item.id() + " references unknown definition " + item.planItemDefId()));
    }

    private String defKeyOf(PlanItem item) {
        return definition.planItems().stream()
                .filter(d -> d.id().equals(item.planItemDefId()))
                .map(PlanItemDefinition::defKey)
                .findFirst().orElse(null);
    }

    public CaseSnapshot withPlanItems(List<PlanItem> updated) {
        return new CaseSnapshot(caseInstance, definition, updated);
    }
}
