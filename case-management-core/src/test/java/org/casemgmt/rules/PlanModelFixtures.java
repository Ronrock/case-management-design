package org.casemgmt.rules;

import org.casemgmt.domain.*;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Domain-free plan models for evaluator tests. No case type appears here. */
public final class PlanModelFixtures {

    private PlanModelFixtures() {}

    // Each item() call gets a strictly later createdAt than the previous one. Without this,
    // CaseSnapshot.latest(defKey) — which orders by createdAt — would see ties resolved
    // nondeterministically by whatever order the JVM's clock or list happens to produce,
    // and repetition-style tests (several PlanItems sharing a defKey) would flake.
    private static final OffsetDateTime BASE = OffsetDateTime.now();
    private static final AtomicLong SEQUENCE = new AtomicLong();

    public static PlanItemDefinition def(String key, PlanItemType type) {
        return new PlanItemDefinition("pd-" + key, "d:1", key, type, key, null,
                false, false, false, List.of(), List.of(), null, null, List.of(), 10);
    }

    public static PlanItemDefinition def(String key, PlanItemType type, String parentStageKey,
                                         boolean manualActivation, boolean required, boolean repetition,
                                         List<String> entryCriteria, List<String> exitCriteria,
                                         int sortOrder) {
        return new PlanItemDefinition("pd-" + key, "d:1", key, type, key, parentStageKey,
                manualActivation, required, repetition, entryCriteria, exitCriteria,
                null, null, List.of(), sortOrder);
    }

    public static CaseDefinition definition(PlanItemDefinition... items) {
        return new CaseDefinition("d:1", "d", 1, "D", "t1", null, null,
                List.of(), List.of(), Map.of(), List.of(items), OffsetDateTime.now(), "test");
    }

    public static PlanItem item(String id, String defKey, PlanItemType type, PlanItemState state) {
        return item(id, defKey, type, state, null);
    }

    public static PlanItem item(String id, String defKey, PlanItemType type,
                                PlanItemState state, String parentStageId) {
        OffsetDateTime createdAt = BASE.plusNanos(SEQUENCE.getAndIncrement() * 1_000L);
        return new PlanItem(id, "eng-a:1", "pd-" + defKey, type, defKey, state, parentStageId,
                false, 1, null, null, null, 0L, createdAt, createdAt, null);
    }

    public static CaseInstance caseInstance(Map<String, Object> variables) {
        return new CaseInstance("eng-a:1", "eng-a", "t1", "d:1", "d", 1, null, "T",
                CaseState.ACTIVE, CasePriority.MEDIUM, null, null, "alice", "NONE", null, null,
                variables, 0L, OffsetDateTime.now(), OffsetDateTime.now(), null);
    }

    public static CaseSnapshot snapshot(CaseDefinition def, List<PlanItem> items,
                                        Map<String, Object> variables) {
        return new CaseSnapshot(caseInstance(variables), def, items);
    }
}
