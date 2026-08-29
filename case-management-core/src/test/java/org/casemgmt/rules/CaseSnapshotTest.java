package org.casemgmt.rules;

import org.casemgmt.domain.*;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.casemgmt.rules.CaseFixtures.*;

class CaseSnapshotTest {

    @Test
    void latestBreaksIdenticalCreatedAtTiesByRepetitionNo() {
        // Each repeat instance carries an independent
        // OffsetDateTime.now(); two repeats created within the same clock tick can share a
        // createdAt. repetitionNo is the monotonic key that actually orders instances of a
        // defKey — createdAt alone must not be trusted to break the tie.
        CaseDefinition def = definition(def("task", PlanItemType.HUMAN_TASK));
        OffsetDateTime sameInstant = OffsetDateTime.now();

        PlanItem higherRepetition = new PlanItem("pi-2", "eng-a:1", "pd-task", PlanItemType.HUMAN_TASK,
                "task", PlanItemState.AVAILABLE, null, false, 2, null, null, null, 0L,
                sameInstant, sameInstant, null);
        PlanItem lowerRepetition = new PlanItem("pi-1", "eng-a:1", "pd-task", PlanItemType.HUMAN_TASK,
                "task", PlanItemState.COMPLETED, null, false, 1, null, null, null, 0L,
                sameInstant, sameInstant, null);

        // Deliberately listed higher-repetitionNo item first: a createdAt-only stable sort
        // would preserve this input order on a tie and report the lower-repetitionNo item as
        // "latest" -- the wrong instance.
        CaseSnapshot snapshot = snapshot(def, List.of(higherRepetition, lowerRepetition), Map.of());

        assertThat(snapshot.latest("task")).isEqualTo(higherRepetition);
    }
}
