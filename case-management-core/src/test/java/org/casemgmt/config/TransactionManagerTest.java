package org.casemgmt.config;

import org.casemgmt.OracleTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves {@code @Transactional} genuinely commits and rolls back against Oracle, instead of
 * merely trusting that {@link TransactionManagerConfig} compiling means it works.
 *
 * <p>Before this module had a real {@code PlatformTransactionManager}, {@code @Transactional}
 * was a silent no-op: with no Spring context and no AOP proxy wrapping the call, the annotation
 * was inert metadata and every statement autocommitted regardless of what it said (Task 5's
 * finding). A test written against that code would have watched {@link TwoWriteProbe#writeTwoThenFail}
 * commit BOTH rows despite throwing — exactly the failure mode this test is built to catch.
 */
class TransactionManagerTest extends OracleTestBase {

    private AnnotationConfigApplicationContext ctx;
    private TwoWriteProbe probe;

    @BeforeEach
    void setUp() {
        ctx = springContext(TwoWriteProbe.class);
        probe = ctx.getBean(TwoWriteProbe.class);
    }

    @AfterEach
    void tearDown() {
        ctx.close();
    }

    @Test
    void bothWritesRollBackWhenTheTransactionalMethodThrows() {
        assertThatThrownBy(() -> probe.writeTwoThenFail("cmd-fail-1", "cmd-fail-2"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("simulated failure after two writes");

        assertThat(countRows("cmd-fail-1", "cmd-fail-2")).isZero();
    }

    @Test
    void bothWritesAreVisibleWhenTheTransactionalMethodSucceeds() {
        probe.writeTwoThenSucceed("cmd-ok-1", "cmd-ok-2");

        assertThat(countRows("cmd-ok-1", "cmd-ok-2")).isEqualTo(2);
    }

    private int countRows(String id1, String id2) {
        return jdbc().sql("SELECT COUNT(*) FROM CM_ENGINE_COMMAND WHERE ID_ IN (:a, :b)")
                .param("a", id1).param("b", id2)
                .query(Integer.class).single();
    }

    /**
     * A minimal Spring-managed bean whose methods each perform two inserts. Writes into
     * CM_ENGINE_COMMAND purely because it is a convenient FK-free table already in the schema —
     * this test is about proving the transaction mechanism works, not about the engine command
     * outbox itself (Task 13's second half, built separately in {@code org.casemgmt.engine}).
     */
    @Component
    static class TwoWriteProbe {
        private final JdbcClient jdbc;

        TwoWriteProbe(DataSource dataSource) {
            this.jdbc = JdbcClient.create(dataSource);
        }

        @Transactional
        void writeTwoThenSucceed(String id1, String id2) {
            insert(id1);
            insert(id2);
        }

        @Transactional
        void writeTwoThenFail(String id1, String id2) {
            insert(id1);
            insert(id2);
            throw new IllegalStateException("simulated failure after two writes");
        }

        private void insert(String id) {
            jdbc.sql("INSERT INTO CM_ENGINE_COMMAND (ID_, CASE_ID_, TYPE_) VALUES (:id, 'case-1', 'CREATE_TASK')")
                    .param("id", id)
                    .update();
        }
    }
}
