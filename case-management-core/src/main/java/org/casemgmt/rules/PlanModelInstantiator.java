package org.casemgmt.rules;

import org.casemgmt.domain.*;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class PlanModelInstantiator {

    /**
     * One AVAILABLE runtime item per definition; the evaluator advances them from there.
     *
     * Instance ids are minted up front (rather than inline in the mapping stream) so that a
     * child's {@code parentStageId} can be resolved to its parent stage's actual runtime id
     * regardless of declaration order in {@code definition.planItems()}. Without this, every
     * child would instantiate with a null parentStageId and {@link StageCompletion#isContained}
     * would treat it as top-level — silently defeating containment for every case created
     * through this method, even though it works fine on hand-built snapshots in tests.
     */
    public List<PlanItem> initialItems(String caseId, CaseDefinition definition) {
        OffsetDateTime now = OffsetDateTime.now();
        Map<String, String> instanceIdByDefKey = new LinkedHashMap<>();
        for (PlanItemDefinition d : definition.planItems()) {
            instanceIdByDefKey.put(d.defKey(), CaseIds.newId());
        }
        return definition.planItems().stream()
                .map(d -> new PlanItem(instanceIdByDefKey.get(d.defKey()), caseId, d.id(), d.type(), d.defKey(),
                        PlanItemState.AVAILABLE,
                        d.parentStageKey() == null ? null : instanceIdByDefKey.get(d.parentStageKey()),
                        false, 1, null, null, null, 0L, now, now, null))
                .toList();
    }

    /** A further instance of a repeatable item (spec §3.2 repetition). */
    public PlanItem repeat(PlanItem previous, PlanItemDefinition definition) {
        OffsetDateTime now = OffsetDateTime.now();
        return new PlanItem(CaseIds.newId(), previous.caseId(), definition.id(), definition.type(),
                definition.defKey(), PlanItemState.AVAILABLE, previous.parentStageId(), false,
                previous.repetitionNo() + 1, null, null, null, 0L, now, now, null);
    }
}
