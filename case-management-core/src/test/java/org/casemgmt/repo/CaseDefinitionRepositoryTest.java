package org.casemgmt.repo;

import org.casemgmt.OracleTestBase;
import org.casemgmt.domain.*;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves {@link CaseDefinitionRepository#insert} is atomic: the CM_CASE_DEF row and all of
 * its CM_PLAN_ITEM_DEF rows either all land or none do. Deliberately calls the repository
 * directly rather than going through {@code CaseDefinitionService.deploy}, because the
 * service now rejects a duplicate defKey before any write is attempted (see
 * {@code CaseDefinitionServiceTest.rejectsADefinitionWithDuplicatePlanItemDefKeys}) — this
 * test needs the failure to happen mid-write, at the database, which means bypassing that
 * validation and handing the repository an already-invalid {@link CaseDefinition} directly.
 */
class CaseDefinitionRepositoryTest extends OracleTestBase {

    @Test
    void aFailurePartwayThroughThePlanItemLoopRollsBackTheWholeDefinition() {
        CaseDefinitionRepository repo = new CaseDefinitionRepository(dataSource());
        String id = "broken-def:1";
        OffsetDateTime now = OffsetDateTime.now();

        // Three plan items, the third reusing the first's defKey. CM_CASE_DEF and the first
        // two CM_PLAN_ITEM_DEF rows insert successfully on the shared connection; the third
        // violates UQ_CM_PI_DEF UNIQUE (CASE_DEF_ID_, DEF_KEY_) and throws — exactly the
        // "third plan item duplicated" failure mode the reviewer reproduced against the
        // pre-fix, non-transactional insert().
        PlanItemDefinition step1 = planItem(id, "step1", 10);
        PlanItemDefinition step2 = planItem(id, "step2", 20);
        PlanItemDefinition duplicateOfStep1 = planItem(id, "step1", 30);

        CaseDefinition broken = new CaseDefinition(id, "broken-def", 1, "Broken", "t1",
                null, null, List.of(), List.of(), Map.of(),
                List.of(step1, step2, duplicateOfStep1), now, "alice");

        assertThatThrownBy(() -> repo.insert(broken)).isInstanceOf(RuntimeException.class);

        assertThat(repo.findById(id)).isEmpty();
        Long planItemRows = jdbc()
                .sql("SELECT COUNT(*) FROM CM_PLAN_ITEM_DEF WHERE CASE_DEF_ID_ = :id")
                .param("id", id)
                .query(Long.class).single();
        assertThat(planItemRows).isZero();
    }

    private static PlanItemDefinition planItem(String caseDefId, String defKey, int sortOrder) {
        return new PlanItemDefinition(CaseIds.newId(), caseDefId, defKey, PlanItemType.STAGE,
                defKey, null, false, false, false, List.of(), List.of(), null, null,
                List.of(), sortOrder);
    }
}
