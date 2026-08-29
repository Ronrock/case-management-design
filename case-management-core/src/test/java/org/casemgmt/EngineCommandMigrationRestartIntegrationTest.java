package org.casemgmt;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.casemgmt.engine.EngineCommandStatus;
import org.casemgmt.repo.EngineCommandRepository;
import org.casemgmt.repo.JsonCodec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.io.StringReader;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Oracle proof for legacy command mapping and restart-safe production command DDL. */
class EngineCommandMigrationRestartIntegrationTest extends OracleTestBase {

    private static final String MASTER = "db/changelog/db.changelog-master.xml";
    private static final String CHANGELOG = "db/changelog/cm-production-engine-command.xml";
    private static final String SCHEMA = "WS4_COMMAND_RESTART";
    private static final String PASSWORD = "Ws4Command42";
    private static final String FIRST_PRODUCTION_CHANGESET =
            "cm-production-engine-command-columns-guard";
    private static final List<String> EXPECTED_CHANGESETS = List.of(
            "cm-production-engine-command-columns",
            "cm-production-engine-command-status-migration-drop",
            "cm-production-engine-command-legacy-due-migration-drop",
            "cm-production-engine-command-status-width-v2",
            "cm-production-engine-command-backfill",
            "cm-production-engine-command-status-v2",
            "cm-production-engine-command-legacy-due-v2",
            "cm-production-engine-command-payload-digest-backfill",
            "cm-production-engine-command-required",
            "cm-production-engine-command-status-guard",
            "cm-production-engine-command-drop-poc-status",
            "cm-production-engine-command-new-status-guard",
            "cm-production-engine-command-status",
            "cm-engine-command-action-table",
            "cm-production-engine-command-counter-invariants",
            "cm-production-engine-command-lease-invariants",
            "cm-engine-command-action-invariants",
            "cm-production-engine-command-normalize-retry-time",
            "cm-production-engine-command-temporal-invariants",
            "cm-production-engine-command-temporal-v1-drop",
            "cm-production-engine-command-temporal-v2",
            "cm-engine-command-action-fk",
            "cm-engine-command-action-id-unique",
            "cm-engine-command-action-seq-unique",
            "cm-engine-command-operation-unique",
            "cm-engine-command-idempotency-unique",
            "cm-engine-command-due-index",
            "cm-engine-command-lease-index",
            "cm-engine-command-case-status-index",
            "cm-engine-command-review-index",
            "cm-engine-command-transition-table",
            "cm-engine-command-transition-status-width-v2",
            "cm-engine-command-transition-baseline",
            "cm-engine-command-transition-format-v1-drop",
            "cm-engine-command-transition-format-v2",
            "cm-engine-command-action-sequence-constraint",
            "cm-engine-command-transition-command-fk",
            "cm-engine-command-transition-action-fk",
            "cm-production-engine-command-byte-semantics",
            "cm-production-engine-command-columns-guard",
            "cm-production-engine-command-status-migration-guard",
            "cm-production-engine-command-legacy-due-migration-guard",
            "cm-production-engine-command-status-width-guard",
            "cm-engine-command-action-table-guard",
            "cm-production-engine-command-invariants-guard",
            "cm-production-engine-command-temporal-guard",
            "cm-production-engine-command-temporal-v2-guard",
            "cm-production-engine-command-objects-guard",
            "cm-engine-command-transition-table-guard",
            "cm-engine-command-transition-status-width-guard",
            "cm-engine-command-transition-format-guard",
            "cm-engine-command-transition-objects-guard",
            "cm-production-engine-command-final-state-guard");
    private static String jdbcUrl;

    @BeforeAll
    static void captureOracleUrl() throws Exception {
        try (Connection connection = dataSource().getConnection()) {
            jdbcUrl = connection.getMetaData().getURL();
        }
    }

    @AfterAll
    static void cleanup() throws Exception {
        if (jdbcUrl != null) {
            try (Connection system = DriverManager.getConnection(jdbcUrl, "system", "cm")) {
                dropSchema(system);
            }
        }
    }

    @Test
    void mapsEveryPocStatusDeterministicallyAndRetainsRawHistoricalEvidence() throws Exception {
        DataSource scenario = recreateBaseline();
        JdbcClient jdbc = JdbcClient.create(scenario);
        seedLegacyRows(jdbc);

        migrate(scenario);
        List<String> first = mappedRows(jdbc);
        migrate(scenario);

        assertThat(first).containsExactly(
                "claimed|AWAITING_CONFIRMATION|3|3|CLAIMED|old-claimed|boom-claimed|-|-|-|old-token|Y",
                "dead|FAILED|4|4|DEAD|old-dead|boom-dead|-|-|-|-|N",
                "done|CONFIRMED|4|4|DONE|old-done|boom-done|done|DONE|3|-|N",
                "pending|PENDING|0|0|PENDING|old-pending|boom-pending|-|-|-|-|N",
                "retrying|RETRYABLE|1|1|RETRYING|old-retrying|boom-retrying|-|-|-|-|N");
        assertThat(mappedRows(jdbc)).containsExactlyElementsOf(first);
        assertThat(jdbc.sql("""
                SELECT COUNT(*) FROM CM_ENGINE_COMMAND
                WHERE RAW_LEGACY_ATTEMPTS_=ATTEMPTS_
                  AND RAW_LEGACY_CREATED_AT_=CREATED_AT_
                  AND RAW_LEGACY_UPDATED_AT_=CREATED_AT_
                  AND MIGRATION_BASELINE_ACTIVE_=1
                  AND MIGRATION_BASELINE_DECIDED_AT_=CASE WHEN ORIGINAL_STATUS_='DONE'
                    THEN LEGACY_MIGRATED_AT_ ELSE CREATED_AT_ END
                """).query(Integer.class).single()).isEqualTo(5);
        var rehydratedDone = new EngineCommandRepository(scenario)
                .require("__legacy_unscoped__", "done");
        assertThat(rehydratedDone.state().committedDecision().status())
                .isEqualTo(EngineCommandStatus.CONFIRMED);
        assertThat(rehydratedDone.state().committedDecision().totalDispatchAttempts())
                .isEqualTo(4);
        assertThat(appliedChangeSets(jdbc)).containsExactlyElementsOf(EXPECTED_CHANGESETS);
        assertFinalSchema(jdbc);

        jdbc.sql("UPDATE CM_ENGINE_COMMAND SET LEGACY_MIGRATION_REF_='forged' WHERE ID_='done'")
                .update();
        assertThatThrownBy(() -> new EngineCommandRepository(scenario)
                .require("__legacy_unscoped__", "done"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("legacy DONE provenance");
    }

    @Test
    void hashesAndRehydratesTheEntireLegacyClobBeyondOracleVarcharLimits() throws Exception {
        DataSource scenario = recreateBaseline();
        String payload = "{\"engineTaskId\":\"case-a\",\"variables\":{\"raw\":\""
                + "é".repeat(40_000) + "\"}}";
        try (Connection connection = scenario.getConnection();
             var statement = connection.prepareStatement("""
                     INSERT INTO CM_ENGINE_COMMAND
                       (ID_,CASE_ID_,TYPE_,PAYLOAD_JSON_,STATUS_,ATTEMPTS_,CREATED_AT_)
                     VALUES ('large','case-a','COMPLETE_TASK',?,'PENDING',0,SYSTIMESTAMP)
                     """)) {
            statement.setCharacterStream(1, new StringReader(payload), payload.length());
            statement.executeUpdate();
        }

        migrate(scenario);
        JdbcClient jdbc = JdbcClient.create(scenario);
        assertThat(jdbc.sql("SELECT PAYLOAD_DIGEST_ FROM CM_ENGINE_COMMAND WHERE ID_='large'")
                .query(String.class).single()).isEqualTo(JsonCodec.sha256(payload));
        assertThat(new EngineCommandRepository(scenario)
                .require("__legacy_unscoped__", "large").payload())
                .extracting("variables")
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("raw", "é".repeat(40_000));
    }

    @Test
    void nullableLegacyPayloadRetainsNullEvidenceButFailsClosedDuringRehydration()
            throws Exception {
        DataSource scenario = recreateBaseline();
        JdbcClient jdbc = JdbcClient.create(scenario);
        jdbc.sql("""
                INSERT INTO CM_ENGINE_COMMAND
                  (ID_,CASE_ID_,TYPE_,PAYLOAD_JSON_,STATUS_,ATTEMPTS_,CREATED_AT_)
                VALUES ('null-payload','case-a','COMPLETE_TASK',NULL,'PENDING',0,SYSTIMESTAMP)
                """).update();

        migrate(scenario);

        assertThat(jdbc.sql("""
                SELECT COUNT(*) FROM CM_ENGINE_COMMAND
                WHERE ID_='null-payload' AND RAW_LEGACY_PAYLOAD_ IS NULL
                  AND DBMS_LOB.COMPARE(PAYLOAD_JSON_, TO_CLOB('{}'))=0
                  AND PAYLOAD_DIGEST_=:digest
                """).param("digest", JsonCodec.sha256("{}"))
                .query(Integer.class).single()).isEqualTo(1);
        assertThatThrownBy(() -> new EngineCommandRepository(scenario)
                .require("__legacy_unscoped__", "null-payload"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("payload fields");
    }

    @Test
    void legacyBaselineRemainsImmutableWhileRepositoryOwnedDecisionsEvolve() throws Exception {
        DataSource scenario = recreateBaseline();
        JdbcClient jdbc = JdbcClient.create(scenario);
        seedLegacyRows(jdbc);
        migrate(scenario);
        OffsetDateTime now = OffsetDateTime.parse("2030-01-01T12:00:00Z");
        var repository = new EngineCommandRepository(scenario,
                Clock.fixed(now.plusSeconds(10).toInstant(), ZoneOffset.UTC));

        var leases = repository.claimDue("migration-worker", 2,
                now, java.time.Duration.ofMinutes(5));
        assertThat(leases).hasSize(2);
        for (var lease : leases) {
            repository.commitLeaseOutcome("__legacy_unscoped__",
                    lease.command().operationId(), lease.leaseToken(), lease.command().version(),
                    org.casemgmt.engine.CommandDispatchOutcome.transportFailure(
                            org.casemgmt.engine.CommandDispatchOutcome.TransportFailure
                                    .PRE_SEND_ZERO_BYTES));
        }

        var claimed = repository.require("__legacy_unscoped__", "claimed");
        var confirmation = new org.casemgmt.engine.CommandDispatchOutcome.ConfirmationEvidence(
                "__legacy_unscoped__", "claimed", "claimed",
                org.casemgmt.engine.EngineCommand.Type.COMPLETE_TASK, "case-a", "case-a",
                org.casemgmt.engine.CommandDispatchOutcome.RemoteState.TASK_COMPLETED,
                org.casemgmt.engine.CommandDispatchOutcome.ConfirmationSource.RECONCILIATION,
                "reconcile-claimed");
        repository.applyOutcome("__legacy_unscoped__", "claimed", claimed.version(),
                org.casemgmt.engine.CommandDispatchOutcome.reconciliationConfirmed(confirmation));

        assertThat(repository.require("__legacy_unscoped__", "pending").state()
                .committedDecision().status()).isEqualTo(EngineCommandStatus.RETRYABLE);
        assertThat(repository.require("__legacy_unscoped__", "retrying").state()
                .committedDecision().status()).isEqualTo(EngineCommandStatus.RETRYABLE);
        assertThat(repository.require("__legacy_unscoped__", "claimed").state()
                .committedDecision().status()).isEqualTo(EngineCommandStatus.CONFIRMED);
        assertThat(jdbc.sql("""
                SELECT COUNT(*) FROM CM_ENGINE_COMMAND
                WHERE ORIGINAL_STATUS_ IS NOT NULL AND MIGRATION_BASELINE_ACTIVE_=0
                  AND DBMS_LOB.COMPARE(RAW_LEGACY_PAYLOAD_,PAYLOAD_JSON_)=0
                  AND RAW_LEGACY_ATTEMPTS_=ATTEMPTS_
                  AND RAW_LEGACY_CREATED_AT_=CREATED_AT_
                """).query(Integer.class).single()).isEqualTo(3);
    }

    @ParameterizedTest(name = "rejects forged historical tuple: {0}")
    @MethodSource("forgedHistoricalTuples")
    void rejectsForgedHistoricalTupleForEveryLegacyStatus(
            String scenarioName, String id, String mutation) throws Exception {
        DataSource scenario = recreateBaseline();
        JdbcClient jdbc = JdbcClient.create(scenario);
        seedLegacyRows(jdbc);
        migrate(scenario);
        jdbc.sql(mutation).update();
        assertThatThrownBy(() -> new EngineCommandRepository(scenario)
                .require("__legacy_unscoped__", id))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Stream<Arguments> forgedHistoricalTuples() {
        return Stream.of(
                Arguments.of("pending raw error", "pending",
                        "UPDATE CM_ENGINE_COMMAND SET RAW_LEGACY_ERROR_='other' WHERE ID_='pending'"),
                Arguments.of("pending raw attempts", "pending",
                        "UPDATE CM_ENGINE_COMMAND SET RAW_LEGACY_ATTEMPTS_=1 WHERE ID_='pending'"),
                Arguments.of("pending raw created time", "pending",
                        "UPDATE CM_ENGINE_COMMAND SET RAW_LEGACY_CREATED_AT_="
                                + "RAW_LEGACY_CREATED_AT_ + INTERVAL '1' SECOND "
                                + "WHERE ID_='pending'"),
                Arguments.of("pending raw updated time", "pending",
                        "UPDATE CM_ENGINE_COMMAND SET RAW_LEGACY_UPDATED_AT_="
                                + "RAW_LEGACY_UPDATED_AT_ + INTERVAL '1' SECOND "
                                + "WHERE ID_='pending'"),
                Arguments.of("pending raw payload", "pending",
                        "UPDATE CM_ENGINE_COMMAND SET RAW_LEGACY_PAYLOAD_='{}' "
                                + "WHERE ID_='pending'"),
                Arguments.of("pending claim tuple", "pending",
                        "UPDATE CM_ENGINE_COMMAND SET RAW_LEGACY_CLAIM_TOKEN_='forged', "
                                + "RAW_LEGACY_CLAIMED_AT_=SYSTIMESTAMP WHERE ID_='pending'"),
                Arguments.of("retrying due time", "retrying",
                        "UPDATE CM_ENGINE_COMMAND SET NEXT_ATTEMPT_AT_=NULL WHERE ID_='retrying'"),
                Arguments.of("claimed claim tuple", "claimed",
                        "UPDATE CM_ENGINE_COMMAND SET RAW_LEGACY_CLAIM_TOKEN_=NULL, "
                                + "RAW_LEGACY_CLAIMED_AT_=NULL WHERE ID_='claimed'"),
                Arguments.of("done migration reference", "done",
                        "UPDATE CM_ENGINE_COMMAND SET LEGACY_MIGRATION_REF_='other' WHERE ID_='done'"),
                Arguments.of("dead failure time", "dead",
                        "UPDATE CM_ENGINE_COMMAND SET FAILED_AT_=NULL WHERE ID_='dead'"),
                Arguments.of("historical update time", "dead",
                        "UPDATE CM_ENGINE_COMMAND SET UPDATED_AT_=UPDATED_AT_ + INTERVAL '1' SECOND "
                                + "WHERE ID_='dead'"),
                Arguments.of("historical decision time", "pending",
                        "UPDATE CM_ENGINE_COMMAND SET DECIDED_AT_=DECIDED_AT_ + INTERVAL '1' SECOND "
                                + "WHERE ID_='pending'"),
                Arguments.of("retrying update time", "retrying",
                        "UPDATE CM_ENGINE_COMMAND SET UPDATED_AT_=UPDATED_AT_ + INTERVAL '1' SECOND "
                                + "WHERE ID_='retrying'"),
                Arguments.of("claimed baseline decision time", "claimed",
                        "UPDATE CM_ENGINE_COMMAND SET MIGRATION_BASELINE_DECIDED_AT_="
                                + "MIGRATION_BASELINE_DECIDED_AT_ + INTERVAL '1' SECOND "
                                + "WHERE ID_='claimed'"),
                Arguments.of("done confirmation time", "done",
                        "UPDATE CM_ENGINE_COMMAND SET CONFIRMED_AT_=CONFIRMED_AT_ + INTERVAL '1' SECOND "
                                + "WHERE ID_='done'"),
                Arguments.of("pending deterministic binding", "pending",
                        "UPDATE CM_ENGINE_COMMAND SET IDEMPOTENCY_KEY_='forged' WHERE ID_='pending'"),
                Arguments.of("pending marker-only evolution", "pending",
                        "UPDATE CM_ENGINE_COMMAND SET MIGRATION_BASELINE_ACTIVE_=0, ROW_VERSION_=1 "
                                + "WHERE ID_='pending'"),
                Arguments.of("retryable marker-only evolution", "retrying",
                        "UPDATE CM_ENGINE_COMMAND SET MIGRATION_BASELINE_ACTIVE_=0, ROW_VERSION_=1 "
                                + "WHERE ID_='retrying'"),
                Arguments.of("retrying deterministic binding", "retrying",
                        "UPDATE CM_ENGINE_COMMAND SET IDEMPOTENCY_KEY_='forged' WHERE ID_='retrying'"),
                Arguments.of("claimed deterministic binding", "claimed",
                        "UPDATE CM_ENGINE_COMMAND SET IDEMPOTENCY_KEY_='forged' WHERE ID_='claimed'"),
                Arguments.of("done deterministic binding", "done",
                        "UPDATE CM_ENGINE_COMMAND SET IDEMPOTENCY_KEY_='forged' WHERE ID_='done'"),
                Arguments.of("dead deterministic binding", "dead",
                        "UPDATE CM_ENGINE_COMMAND SET IDEMPOTENCY_KEY_='forged' WHERE ID_='dead'"));
    }

    @ParameterizedTest(name = "resumes after {0} production changesets")
    @ValueSource(ints = {0, 2, 5, 6, 8, 9, 10, 11, 19, 26, 29, 36, 41, 43, 44, 47, 52})
    void resumesAndRerunsAfterEveryRepresentativeOracleDdlPrefix(int prefix) throws Exception {
        DataSource scenario = recreateBaseline();
        updateNext(scenario, prefix);

        migrate(scenario);
        assertFinalSchema(JdbcClient.create(scenario));
        migrate(scenario);
        assertThat(appliedChangeSets(JdbcClient.create(scenario)))
                .containsExactlyElementsOf(EXPECTED_CHANGESETS);
    }

    @ParameterizedTest(name = "malformed same-named {0} halts")
    @EnumSource(MalformedObject.class)
    void malformedSameNamedProductionObjectsHaltWithoutRecordingTheirGuard(
            MalformedObject malformed) throws Exception {
        DataSource scenario = recreateBaseline();
        updateNext(scenario, malformed.prefix);
        JdbcClient jdbc = JdbcClient.create(scenario);
        installMalformed(jdbc, malformed);

        assertThatThrownBy(() -> migrate(scenario))
                .hasStackTraceContaining("incompatible");
        assertThat(appliedChangeSets(jdbc)).doesNotContain(malformed.guard);
    }

    @ParameterizedTest(name = "final production guard rejects post-apply drift: {0}")
    @EnumSource(FinalMutation.class)
    void finalProductionGuardRejectsPostApplyDrift(FinalMutation mutation) throws Exception {
        DataSource scenario = recreateBaseline();
        migrate(scenario);
        mutation.apply(JdbcClient.create(scenario));

        assertThatThrownBy(() -> migrate(scenario))
                .hasStackTraceContaining("incompatible");
    }

    @Test
    void charLengthSessionConvergesEveryCommandAndObservationContractColumnToByteSemantics()
            throws Exception {
        DataSource scenario = recreateBaseline(true);

        migrate(scenario, true);

        assertThat(JdbcClient.create(scenario).sql("""
                SELECT COUNT(*) FROM USER_TAB_COLUMNS
                WHERE DATA_TYPE='VARCHAR2' AND CHAR_USED<>'B' AND (
                  TABLE_NAME IN ('CM_ENGINE_COMMAND','CM_ENGINE_COMMAND_ACTION',
                                 'CM_APPLIED_ENGINE_OBSERVATION')
                  OR (TABLE_NAME='CM_PLAN_ITEM' AND COLUMN_NAME='PROC_INST_ID_')
                  OR (TABLE_NAME='CM_TASK' AND COLUMN_NAME='PROC_INST_ID_')
                  OR (TABLE_NAME='CM_LINKED_PROCESS' AND COLUMN_NAME='PROC_DEF_ID_'))
                """).query(Integer.class).single()).isZero();
        migrate(scenario, true);
    }

    private static void installMalformed(JdbcClient jdbc, MalformedObject malformed) {
        switch (malformed) {
            case STATUS_CONSTRAINT -> {
                jdbc.sql("ALTER TABLE CM_ENGINE_COMMAND DROP CONSTRAINT CK_CM_ENGCMD_STATUS")
                        .update();
                jdbc.sql("ALTER TABLE CM_ENGINE_COMMAND ADD CONSTRAINT CK_CM_ENGCMD_STATUS "
                        + "CHECK (STATUS_ IN ('PENDING'))").update();
            }
            case ACTION_TABLE -> jdbc.sql("CREATE TABLE CM_ENGINE_COMMAND_ACTION "
                    + "(COMMAND_ID_ VARCHAR2(64) NOT NULL)").update();
            case ACTION_DEFAULT -> jdbc.sql("ALTER TABLE CM_ENGINE_COMMAND_ACTION "
                    + "MODIFY REVIEW_FINDING_ DEFAULT 'FORGED'").update();
            case COUNTER_CONSTRAINT_DISABLED -> jdbc.sql(
                    "ALTER TABLE CM_ENGINE_COMMAND DISABLE NOVALIDATE CONSTRAINT CK_CM_ENGCMD_COUNTERS")
                    .update();
            case COUNTER_CONSTRAINT_WRONG_TABLE -> {
                jdbc.sql("CREATE TABLE CM_WRONG_COMMAND (VALUE_ NUMBER)").update();
                jdbc.sql("ALTER TABLE CM_WRONG_COMMAND ADD CONSTRAINT CK_CM_ENGCMD_COUNTERS "
                        + "CHECK (VALUE_ >= 0)").update();
            }
            case DUE_INDEX_UNUSABLE -> jdbc.sql(
                    "ALTER INDEX IX_CM_ENGCMD_PROD_DUE UNUSABLE").update();
            default -> jdbc.sql(malformed.ddl).update();
        }
    }

    private enum MalformedObject {
        PRODUCTION_COLUMNS(0, "ALTER TABLE CM_ENGINE_COMMAND ADD OPERATION_ID_ VARCHAR2(10)",
                "cm-production-engine-command-columns-guard"),
        PRODUCTION_NUMBER_SCALE(0,
                "ALTER TABLE CM_ENGINE_COMMAND ADD EXPECTED_CASE_VERSION_ NUMBER(19,2)",
                "cm-production-engine-command-columns-guard"),
        STATUS_CONSTRAINT(0, null, "cm-production-engine-command-status-guard"),
        ACTION_TABLE(0, null, "cm-engine-command-action-table-guard"),
        ACTION_DEFAULT(19, null, "cm-production-engine-command-invariants-guard"),
        COUNTER_CONSTRAINT_DISABLED(21, null,
                "cm-production-engine-command-invariants-guard"),
        COUNTER_CONSTRAINT_WRONG_TABLE(19, null,
                "cm-production-engine-command-invariants-guard"),
        COUNTER_CONSTRAINT(19, "ALTER TABLE CM_ENGINE_COMMAND ADD CONSTRAINT "
                + "CK_CM_ENGCMD_COUNTERS CHECK (TOTAL_DISPATCH_ATTEMPTS_ >= 0)",
                "cm-production-engine-command-invariants-guard"),
        LEASE_CONSTRAINT(19, "ALTER TABLE CM_ENGINE_COMMAND ADD CONSTRAINT "
                + "CK_CM_ENGCMD_LEASE CHECK (LEASE_TOKEN_ IS NULL)",
                "cm-production-engine-command-invariants-guard"),
        ACTION_CONSTRAINT(19, "ALTER TABLE CM_ENGINE_COMMAND_ACTION ADD CONSTRAINT "
                + "CK_CM_ECA_INVARIANTS CHECK (SEQUENCE_ > 0)",
                "cm-production-engine-command-invariants-guard"),
        ACTION_FK(19, "ALTER TABLE CM_ENGINE_COMMAND_ACTION ADD CONSTRAINT FK_CM_ECA_COMMAND "
                + "FOREIGN KEY (OPERATION_ID_) REFERENCES CM_ENGINE_COMMAND(ID_)",
                "cm-production-engine-command-objects-guard"),
        ACTION_ID_INDEX(19, "CREATE UNIQUE INDEX UQ_CM_ECA_ACTION "
                + "ON CM_ENGINE_COMMAND_ACTION(ACTION_ID_)",
                "cm-production-engine-command-objects-guard"),
        ACTION_SEQUENCE_INDEX(19, "CREATE UNIQUE INDEX UQ_CM_ECA_SEQUENCE "
                + "ON CM_ENGINE_COMMAND_ACTION(SEQUENCE_)",
                "cm-production-engine-command-objects-guard"),
        OPERATION_INDEX(19, "CREATE INDEX UQ_CM_ENGCMD_OPERATION "
                + "ON CM_ENGINE_COMMAND(TENANT_ID_, OPERATION_ID_)",
                "cm-production-engine-command-objects-guard"),
        OPERATION_INDEX_TRAILING(19, "CREATE UNIQUE INDEX UQ_CM_ENGCMD_OPERATION "
                + "ON CM_ENGINE_COMMAND(CASE WHEN TENANT_ID_ IS NULL THEN 1 ELSE 0 END, "
                + "TENANT_ID_, OPERATION_ID_, ID_)",
                "cm-production-engine-command-objects-guard"),
        IDEMPOTENCY_INDEX(19, "CREATE UNIQUE INDEX UQ_CM_ENGCMD_IDEMPOTENCY "
                + "ON CM_ENGINE_COMMAND(TENANT_ID_, OPERATION_ID_)",
                "cm-production-engine-command-objects-guard"),
        DUE_INDEX(19, "CREATE INDEX IX_CM_ENGCMD_PROD_DUE ON CM_ENGINE_COMMAND(STATUS_)",
                "cm-production-engine-command-objects-guard"),
        DUE_INDEX_UNUSABLE(36, null, "cm-production-engine-command-objects-guard"),
        LEASE_INDEX(19, "CREATE INDEX IX_CM_ENGCMD_LEASE ON CM_ENGINE_COMMAND(LEASE_EXPIRES_AT_)",
                "cm-production-engine-command-objects-guard"),
        CASE_STATUS_INDEX(19, "CREATE INDEX IX_CM_ENGCMD_CASE_STATUS "
                + "ON CM_ENGINE_COMMAND(CASE_ID_, STATUS_)",
                "cm-production-engine-command-objects-guard"),
        REVIEW_INDEX(19, "CREATE INDEX IX_CM_ENGCMD_REVIEW ON CM_ENGINE_COMMAND(UPDATED_AT_)",
                "cm-production-engine-command-objects-guard");

        private final int prefix;
        private final String ddl;
        private final String guard;

        MalformedObject(int prefix, String ddl, String guard) {
            this.prefix = prefix;
            this.ddl = ddl;
            this.guard = guard;
        }
    }

    private enum FinalMutation {
        CHANGED_INITIAL_COLUMN {
            @Override void apply(JdbcClient jdbc) {
                jdbc.sql("ALTER TABLE CM_ENGINE_COMMAND MODIFY CASE_ID_ VARCHAR2(141)").update();
            }
        },
        CHANGED_PRODUCTION_COLUMN {
            @Override void apply(JdbcClient jdbc) {
                jdbc.sql("ALTER TABLE CM_ENGINE_COMMAND MODIFY LEASE_OWNER_ VARCHAR2(129)").update();
            }
        },
        CHANGED_ACTION_COLUMN {
            @Override void apply(JdbcClient jdbc) {
                jdbc.sql("ALTER TABLE CM_ENGINE_COMMAND_ACTION MODIFY ACTION_ID_ VARCHAR2(161)")
                        .update();
            }
        },
        REMOVED_COUNTER_CONSTRAINT {
            @Override void apply(JdbcClient jdbc) {
                jdbc.sql("ALTER TABLE CM_ENGINE_COMMAND DROP CONSTRAINT CK_CM_ENGCMD_COUNTERS")
                        .update();
            }
        },
        DISABLED_LEASE_CONSTRAINT {
            @Override void apply(JdbcClient jdbc) {
                jdbc.sql("ALTER TABLE CM_ENGINE_COMMAND DISABLE NOVALIDATE CONSTRAINT CK_CM_ENGCMD_LEASE")
                        .update();
            }
        },
        REMOVED_DUE_INDEX {
            @Override void apply(JdbcClient jdbc) {
                jdbc.sql("DROP INDEX IX_CM_ENGCMD_PROD_DUE").update();
            }
        },
        REPLACED_REVIEW_INDEX {
            @Override void apply(JdbcClient jdbc) {
                jdbc.sql("DROP INDEX IX_CM_ENGCMD_REVIEW").update();
                jdbc.sql("CREATE INDEX IX_CM_ENGCMD_REVIEW ON CM_ENGINE_COMMAND(UPDATED_AT_)")
                        .update();
            }
        },
        UNUSABLE_ACTION_INDEX {
            @Override void apply(JdbcClient jdbc) {
                jdbc.sql("ALTER INDEX UQ_CM_ECA_SEQUENCE UNUSABLE").update();
            }
        };

        abstract void apply(JdbcClient jdbc);
    }

    private static DataSource recreateBaseline() throws Exception {
        return recreateBaseline(false);
    }

    private static DataSource recreateBaseline(boolean charSemantics) throws Exception {
        try (Connection system = DriverManager.getConnection(jdbcUrl, "system", "cm")) {
            dropSchema(system);
            try (Statement statement = system.createStatement()) {
                statement.execute("CREATE USER " + SCHEMA + " IDENTIFIED BY \"" + PASSWORD
                        + "\" DEFAULT TABLESPACE USERS QUOTA UNLIMITED ON USERS");
                statement.execute("GRANT CREATE SESSION, RESOURCE TO " + SCHEMA);
            }
        }
        DataSource scenario = new DriverManagerDataSource(jdbcUrl, SCHEMA, PASSWORD);
        try (Connection connection = scenario.getConnection()) {
            if (charSemantics) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("ALTER SESSION SET NLS_LENGTH_SEMANTICS=CHAR");
                }
            }
            var database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (var liquibase = new Liquibase(
                    MASTER, new ClassLoaderResourceAccessor(), database)) {
                int beforeProduction = 0;
                for (var changeSet : liquibase.getDatabaseChangeLog().getChangeSets()) {
                    if (FIRST_PRODUCTION_CHANGESET.equals(changeSet.getId())) break;
                    beforeProduction++;
                }
                assertThat(beforeProduction).isPositive();
                liquibase.update(beforeProduction, new Contexts(), new LabelExpression());
            }
        }
        return scenario;
    }

    private static void seedLegacyRows(JdbcClient jdbc) {
        insertLegacy(jdbc, "pending", "PENDING", 0);
        insertLegacy(jdbc, "retrying", "RETRYING", 1);
        insertLegacy(jdbc, "claimed", "CLAIMED", 2);
        insertLegacy(jdbc, "done", "DONE", 3);
        insertLegacy(jdbc, "dead", "DEAD", 4);
    }

    private static void insertLegacy(JdbcClient jdbc, String id, String status, int attempts) {
        jdbc.sql("""
                INSERT INTO CM_ENGINE_COMMAND
                  (ID_, CASE_ID_, TYPE_, PAYLOAD_JSON_, STATUS_, ATTEMPTS_,
                   NEXT_ATTEMPT_AT_, LAST_ERROR_, CREATED_AT_, CLAIM_TOKEN_, CLAIMED_AT_)
                VALUES (:id, 'case-a', 'COMPLETE_TASK', :payload, :status, :attempts,
                        SYSTIMESTAMP, :error, SYSTIMESTAMP,
                        CASE WHEN :status='CLAIMED' THEN 'old-token' END,
                        CASE WHEN :status='CLAIMED' THEN SYSTIMESTAMP END)
                """).param("id", id).param("payload", "{\"engineTaskId\":\"case-a\","
                        + "\"variables\":{\"raw\":\"old-" + id + "\"}}")
                .param("status", status).param("attempts", attempts)
                .param("error", "boom-" + id).update();
    }

    private static List<String> mappedRows(JdbcClient jdbc) {
        return jdbc.sql("""
                SELECT ID_ || '|' || STATUS_ || '|' || TOTAL_DISPATCH_ATTEMPTS_ || '|' ||
                       AUTO_ATTEMPTS_ || '|' || ORIGINAL_STATUS_ || '|' ||
                       JSON_VALUE(RAW_LEGACY_PAYLOAD_, '$.variables.raw') || '|' || RAW_LEGACY_ERROR_ || '|' ||
                       NVL(LEGACY_ROW_ID_, '-') || '|' || NVL(LEGACY_STATUS_, '-') || '|' ||
                       NVL(TO_CHAR(LEGACY_FAILURE_COUNT_), '-') || '|' ||
                       NVL(RAW_LEGACY_CLAIM_TOKEN_, '-') || '|' ||
                       CASE WHEN RAW_LEGACY_CLAIMED_AT_ IS NULL THEN 'N' ELSE 'Y' END AS SNAPSHOT
                FROM CM_ENGINE_COMMAND ORDER BY ID_
                """).query(String.class).list();
    }

    private static void assertFinalSchema(JdbcClient jdbc) {
        assertThat(jdbc.sql("""
                SELECT COLUMN_NAME FROM USER_TAB_COLUMNS WHERE TABLE_NAME='CM_ENGINE_COMMAND'
                  AND COLUMN_NAME IN ('OPERATION_ID_','TENANT_ID_','IDEMPOTENCY_KEY_',
                    'PAYLOAD_DIGEST_','LEASE_TOKEN_','LEASE_OWNER_','LEASE_EXPIRES_AT_',
                    'TOTAL_DISPATCH_ATTEMPTS_','AUTO_ATTEMPTS_','BUDGET_EPOCH_','ROW_VERSION_',
                    'ACTION_COUNT_','ACTION_HIGH_WATER_','LEGACY_STATUS_','RAW_LEGACY_PAYLOAD_',
                    'RAW_LEGACY_CLAIM_TOKEN_','RAW_LEGACY_CLAIMED_AT_',
                    'RAW_LEGACY_ATTEMPTS_','RAW_LEGACY_CREATED_AT_','RAW_LEGACY_UPDATED_AT_',
                    'MIGRATION_BASELINE_DECIDED_AT_','MIGRATION_BASELINE_ACTIVE_')
                ORDER BY COLUMN_NAME
                """).query(String.class).list()).hasSize(22);
        assertThat(jdbc.sql("""
                SELECT INDEX_NAME FROM USER_INDEXES WHERE INDEX_NAME IN
                  ('UQ_CM_ENGCMD_OPERATION','UQ_CM_ENGCMD_IDEMPOTENCY','IX_CM_ENGCMD_PROD_DUE',
                   'IX_CM_ENGCMD_LEASE','IX_CM_ENGCMD_CASE_STATUS','IX_CM_ENGCMD_REVIEW',
                   'UQ_CM_ECA_ACTION','UQ_CM_ECA_SEQUENCE') ORDER BY INDEX_NAME
                """).query(String.class).list()).hasSize(8);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM USER_TABLES WHERE TABLE_NAME='CM_ENGINE_COMMAND_ACTION'")
                .query(Integer.class).single()).isEqualTo(1);
    }

    private static void updateNext(DataSource scenario, int count) throws Exception {
        if (count == 0) return;
        try (Connection connection = scenario.getConnection()) {
            var database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (var liquibase = new Liquibase(MASTER,
                    new ClassLoaderResourceAccessor(), database)) {
                liquibase.update(count, new Contexts(), new LabelExpression());
            }
        }
    }

    private static void migrate(DataSource scenario) throws Exception {
        migrate(scenario, false);
    }

    private static void migrate(DataSource scenario, boolean charSemantics) throws Exception {
        try (Connection connection = scenario.getConnection()) {
            if (charSemantics) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("ALTER SESSION SET NLS_LENGTH_SEMANTICS=CHAR");
                }
            }
            var database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (var liquibase = new Liquibase(MASTER,
                    new ClassLoaderResourceAccessor(), database)) {
                liquibase.update("");
            }
        }
    }

    private static List<String> appliedChangeSets(JdbcClient jdbc) {
        return jdbc.sql("""
                SELECT ID FROM DATABASECHANGELOG WHERE FILENAME=:filename ORDER BY ORDEREXECUTED
                """).param("filename", CHANGELOG).query(String.class).list();
    }

    private static void dropSchema(Connection system) throws SQLException {
        try (Statement statement = system.createStatement()) {
            statement.execute("DROP USER " + SCHEMA + " CASCADE");
        } catch (SQLException e) {
            if (e.getErrorCode() != 1918) throw e;
        }
    }
}
