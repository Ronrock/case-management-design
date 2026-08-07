package org.casemgmt.repo;

import org.casemgmt.OracleTestBase;
import org.casemgmt.domain.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
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

    /**
     * Final whole-branch review, Important 7: {@code insert} used to take a raw
     * {@code dataSource.getConnection()}, which is a SECOND physical connection whenever the
     * caller is already inside a Spring transaction — so the definition committed on its own,
     * outside the enclosing transaction, and survived that transaction's rollback. Three
     * Javadocs asserted this was safe on the grounds that "this module has no transaction
     * manager", which stopped being true at Task 5; the code was safe only by the accident that
     * {@code CaseDefinitionService.deploy} is not {@code @Transactional}.
     *
     * <p>This test is what makes adding that annotation safe: it stands in for the "someone
     * adds {@code @Transactional} to deploy" change by putting {@code insert} inside a REAL
     * proxied transaction (the same {@code springContext}/{@code TransactionManagerConfig}
     * pattern the service-level transactional tests use — a plain {@code new} would never open
     * one) and then rolling that transaction back. Both tables must come out empty.
     *
     * <p>Attribution: the deployer writes a marker row into {@code CM_AUDIT_LOG} in the same
     * transaction, exactly as a future {@code @Transactional deploy} that "wants an audit row"
     * would. Asserting the marker is gone as well proves the transaction genuinely rolled back
     * rather than never having written anything — without it, "no definition rows" is satisfied
     * by an insert that simply failed for an unrelated reason.
     */
    @Test
    void insertJoinsAnEnclosingTransactionAndRollsBackWithIt() {
        try (var ctx = springContext(TransactionalDeployer.class)) {
            TransactionalDeployer deployer = ctx.getBean(TransactionalDeployer.class);
            CaseDefinition good = new CaseDefinition("enlisted-def:1", "enlisted-def", 1,
                    "Enlisted", "t1", null, null, List.of(), List.of(), Map.of(),
                    List.of(planItem("enlisted-def:1", "step1", 10),
                            planItem("enlisted-def:1", "step2", 20)),
                    OffsetDateTime.now(), "alice");

            assertThatThrownBy(() -> deployer.deployThenFail(good))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("rollback me");

            assertThat(new CaseDefinitionRepository(dataSource()).findById("enlisted-def:1"))
                    .as("the definition committed on a second connection and outlived the "
                            + "enclosing transaction's rollback")
                    .isEmpty();
            assertThat(jdbc().sql("SELECT COUNT(*) FROM CM_PLAN_ITEM_DEF WHERE CASE_DEF_ID_ = :id")
                    .param("id", "enlisted-def:1").query(Long.class).single()).isZero();
            assertThat(jdbc().sql("SELECT COUNT(*) FROM CM_AUDIT_LOG WHERE CASE_ID_ = :id")
                    .param("id", "enlisted-def:1").query(Long.class).single())
                    .as("attribution: the same transaction's other write must be gone too, "
                            + "or this test proves nothing about rollback")
                    .isZero();
        }
    }

    /**
     * Negative control for the test above: with NO enclosing transaction — the way every caller
     * reaches this repository today — {@code insert} must still commit on its own. Otherwise
     * "nothing was written" is satisfiable by an {@code insert} that has quietly stopped
     * committing at all, which the rollback test would happily accept.
     */
    @Test
    void insertStillCommitsOnItsOwnWithNoEnclosingTransaction() {
        CaseDefinitionRepository repo = new CaseDefinitionRepository(dataSource());
        CaseDefinition good = new CaseDefinition("standalone-def:1", "standalone-def", 1,
                "Standalone", "t1", null, null, List.of(), List.of(), Map.of(),
                List.of(planItem("standalone-def:1", "step1", 10)),
                OffsetDateTime.now(), "alice");

        repo.insert(good);

        assertThat(repo.findById("standalone-def:1")).isPresent();
        assertThat(jdbc().sql("SELECT COUNT(*) FROM CM_PLAN_ITEM_DEF WHERE CASE_DEF_ID_ = :id")
                .param("id", "standalone-def:1").query(Long.class).single()).isEqualTo(1L);
    }

    /**
     * Stands in for a future {@code @Transactional CaseDefinitionService.deploy}: it writes the
     * definition and one audit row, then throws, so the whole transaction rolls back.
     */
    @Component
    static class TransactionalDeployer {

        private final DataSource dataSource;

        TransactionalDeployer(DataSource dataSource) {
            this.dataSource = dataSource;
        }

        @Transactional
        public void deployThenFail(CaseDefinition definition) {
            new CaseDefinitionRepository(dataSource).insert(definition);
            JdbcClient.create(dataSource).sql("""
                    INSERT INTO CM_AUDIT_LOG (ID_, CASE_ID_, TENANT_ID_, ACTOR_, ACTION_,
                        RESOURCE_TYPE_, RESOURCE_ID_)
                    VALUES (:id, :caseId, 't1', 'alice', 'casedef.deploy', 'CaseDefinition', :caseId)""")
                .param("id", CaseIds.newId()).param("caseId", definition.id())
                .update();
            throw new IllegalStateException("rollback me");
        }
    }

    private static PlanItemDefinition planItem(String caseDefId, String defKey, int sortOrder) {
        return new PlanItemDefinition(CaseIds.newId(), caseDefId, defKey, PlanItemType.STAGE,
                defKey, null, false, false, false, List.of(), List.of(), null, null,
                List.of(), sortOrder);
    }
}
