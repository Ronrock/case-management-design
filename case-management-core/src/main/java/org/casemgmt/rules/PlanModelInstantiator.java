package org.casemgmt.rules;

import org.casemgmt.domain.*;

import java.time.OffsetDateTime;
import java.util.List;

public class PlanModelInstantiator {

    /** One AVAILABLE runtime item per definition; the evaluator advances them from there. */
    public List<PlanItem> initialItems(String caseId, CaseDefinition definition) {
        OffsetDateTime now = OffsetDateTime.now();
        return definition.planItems().stream()
                .map(d -> new PlanItem(CaseIds.newId(), caseId, d.id(), d.type(), d.defKey(),
                        PlanItemState.AVAILABLE, null, false, 1, null, null, null, 0L, now, now, null))
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
