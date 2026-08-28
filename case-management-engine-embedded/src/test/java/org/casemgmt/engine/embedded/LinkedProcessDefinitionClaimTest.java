package org.casemgmt.engine.embedded;

import org.casemgmt.domain.CaseTask;
import org.casemgmt.repo.LinkedProcessRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** H2 proof of the exact-row atomic definition claim used for migrated PLAN_MODEL links. */
class LinkedProcessDefinitionClaimTest {

    private JdbcClient jdbc;
    private LinkedProcessRepository repository;

    @BeforeEach
    void setUp() {
        var dataSource = new SimpleDriverDataSource(new org.h2.Driver(),
                "jdbc:h2:mem:definition-claim;MODE=LEGACY;DB_CLOSE_DELAY=-1");
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("DROP TABLE IF EXISTS CM_LINKED_PROCESS").update();
        jdbc.sql("""
                CREATE TABLE CM_LINKED_PROCESS (
                    ID_ VARCHAR(64) PRIMARY KEY,
                    CASE_ID_ VARCHAR(64) NOT NULL,
                    PLAN_ITEM_ID_ VARCHAR(64),
                    CORRELATION_ID_ VARCHAR(64) NOT NULL,
                    PROC_INST_ID_ VARCHAR(64),
                    PROC_DEF_ID_ VARCHAR(128),
                    PROC_DEF_KEY_ VARCHAR(255),
                    STATE_ VARCHAR(32) NOT NULL,
                    ENGINE_SYNC_ VARCHAR(32) NOT NULL,
                    IS_CASE_ROOT_ INTEGER NOT NULL,
                    LAST_ENGINE_UPDATE_AT_ TIMESTAMP WITH TIME ZONE,
                    LAST_PROJECTED_AT_ TIMESTAMP WITH TIME ZONE)
                """).update();
        repository = new LinkedProcessRepository(jdbc);
        insertMigratedLink();
    }

    @Test
    void claimsTheNullDefinitionOnTheExactConfirmedRowAndIsIdempotent() {
        OffsetDateTime now = OffsetDateTime.parse("2026-08-28T12:00:00Z");

        repository.confirmStarted("case-1", "correlation-1", "process-1",
                "legacy:7", "legacy", now);
        repository.confirmStarted("case-1", "correlation-1", "process-1",
                "legacy:7", "legacy", now.plusSeconds(1));

        assertThat(repository.findByProcessInstanceId("process-1").orElseThrow()
                .processDefinitionId()).isEqualTo("legacy:7");
    }

    @Test
    void rejectsADifferentDefinitionThatAlreadyWonTheNullClaim() {
        jdbc.sql("UPDATE CM_LINKED_PROCESS SET PROC_DEF_ID_ = 'legacy:8' "
                        + "WHERE ID_ = 'link-1' AND PROC_DEF_ID_ IS NULL")
                .update();

        assertThatThrownBy(() -> repository.confirmStarted(
                "case-1", "correlation-1", "process-1",
                "legacy:7", "legacy", OffsetDateTime.parse("2026-08-28T12:00:00Z")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("different state or engine identity");
    }

    private void insertMigratedLink() {
        jdbc.sql("""
                INSERT INTO CM_LINKED_PROCESS
                    (ID_, CASE_ID_, PLAN_ITEM_ID_, CORRELATION_ID_, PROC_INST_ID_,
                     PROC_DEF_ID_, PROC_DEF_KEY_, STATE_, ENGINE_SYNC_, IS_CASE_ROOT_)
                VALUES ('link-1', 'case-1', 'plan-item-1', 'correlation-1', 'process-1',
                        NULL, 'legacy', 'ACTIVE', :sync, 0)
                """).param("sync", CaseTask.EngineSync.SYNCED.name()).update();
    }
}
