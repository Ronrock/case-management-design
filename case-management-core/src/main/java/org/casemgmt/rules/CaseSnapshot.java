package org.casemgmt.rules;

import org.casemgmt.domain.*;

import java.util.Comparator;
import java.util.List;

public record CaseSnapshot(CaseInstance caseInstance, CaseDefinition definition, List<PlanItem> planItems) {

    /**
     * Orders instances of the same defKey by repetitionNo, the monotonic counter both
     * The case runtime assigns repetition numbers (starting at 1, incrementing
     * by 1 per repeat) — never null, never clock-dependent. createdAt is kept only as a
     * tiebreaker for the case where repetitionNo is somehow equal; it must not be the
     * primary key, because two repeats created within the same clock tick can share a
     * createdAt, which would otherwise make "latest" depend on incidental stream order.
     */
    private static final Comparator<PlanItem> INSTANCE_ORDER =
            Comparator.comparingInt(PlanItem::repetitionNo).thenComparing(PlanItem::createdAt);

    /** All runtime instances of a definition key, earliest repetition first. */
    public List<PlanItem> items(String defKey) {
        return planItems.stream()
                .filter(i -> defKey.equals(defKeyOf(i)))
                .sorted(INSTANCE_ORDER)
                .toList();
    }

    /** The highest-repetitionNo instance of a definition key, which is what criteria see. */
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
