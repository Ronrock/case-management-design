package org.casemgmt.engine;

import org.casemgmt.OracleTestBase;
import org.casemgmt.repo.EngineCommandRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 13 review round 2 (IMPORTANT): the property Tasks 14/15/18/19 lean on most had no test.
 * {@link OutboxEngineGateway}'s Javadoc claims it "writes a command row in the caller's
 * transaction" — this proves that claim rather than leaving it asserted-but-untested.
 *
 * <p>A {@code @Transactional} method here does two things a real case-mutating service will
 * later do together: writes a row to a real table (CM_AUDIT_LOG stands in for spec §6.1's "the
 * entity change" — it needs no other row to exist, unlike CM_CASE) and enqueues an engine
 * command through {@link OutboxEngineGateway#createHumanTask}. Both must roll back together on
 * failure and commit together on success. This is the connective tissue between Part 1
 * ({@code TransactionManagerConfig}/{@code TransactionManagerTest}, which proves
 * {@code @Transactional} works in isolation) and Part 2 (the outbox itself) — without Part 1,
 * this test would be proving nothing, since {@code @Transactional} would be a silent no-op and
 * BOTH assertions below would happen to pass for the wrong reason on the failure path (nothing
 * would ever roll back, but the audit row failing to appear would look like "correctly rolled
 * back" instead of "silently never committed to begin with" unless the success test is also
 * green) — running both tests together is what actually pins the behaviour.
 */
class OutboxTransactionalIntegrationTest extends OracleTestBase {

    private AnnotationConfigApplicationContext ctx;
    private TransactionalCaseChange caseChange;

    @BeforeEach
    void setUp() {
        ctx = springContext(TransactionalCaseChange.class);
        caseChange = ctx.getBean(TransactionalCaseChange.class);
    }

    @AfterEach
    void tearDown() {
        ctx.close();
    }

    @Test
    void caseChangeAndEngineCommandEnqueueRollBackTogether() {
        assertThatThrownBy(() ->
                caseChange.mutateCaseAndEnqueueEngineCommand("audit-fail", "eng-a:1", true))
                .isInstanceOf(IllegalStateException.class);

        assertThat(auditRowCount("audit-fail")).isZero();
        assertThat(engineCommandCount()).isZero();
    }

    @Test
    void caseChangeAndEngineCommandEnqueueCommitTogether() {
        caseChange.mutateCaseAndEnqueueEngineCommand("audit-ok", "eng-a:2", false);

        assertThat(auditRowCount("audit-ok")).isEqualTo(1);
        assertThat(engineCommandCount()).isEqualTo(1);
    }

    private int auditRowCount(String id) {
        return jdbc().sql("SELECT COUNT(*) FROM CM_AUDIT_LOG WHERE ID_ = :id")
                .param("id", id).query(Integer.class).single();
    }

    private int engineCommandCount() {
        return jdbc().sql("SELECT COUNT(*) FROM CM_ENGINE_COMMAND").query(Integer.class).single();
    }

    /** A minimal stand-in for a future @Transactional case-mutating service (Tasks 14/15/18/19). */
    @Component
    static class TransactionalCaseChange {
        private final JdbcClient jdbc;
        private final OutboxEngineGateway outbox;

        TransactionalCaseChange(DataSource dataSource) {
            this.jdbc = JdbcClient.create(dataSource);
            this.outbox = new OutboxEngineGateway(
                    new EngineCommandRepository(JdbcClient.create(dataSource)), id -> {});
        }

        @Transactional
        void mutateCaseAndEnqueueEngineCommand(String auditId, String caseId, boolean thenFail) {
            jdbc.sql("""
                    INSERT INTO CM_AUDIT_LOG (ID_, CASE_ID_, ACTOR_, ACTION_, RESOURCE_TYPE_, RESOURCE_ID_)
                    VALUES (:id, :caseId, 'tester', 'test.action', 'CASE', :caseId)""")
                .param("id", auditId).param("caseId", caseId).update();

            outbox.createHumanTask(new HumanTaskRequest(caseId, "pi-x", "Review", null, List.of(), null, Map.of()));

            if (thenFail) {
                throw new IllegalStateException("simulated failure after case mutation and outbox enqueue");
            }
        }
    }
}
