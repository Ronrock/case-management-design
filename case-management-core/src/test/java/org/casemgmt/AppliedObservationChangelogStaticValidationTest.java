package org.casemgmt;

import liquibase.Liquibase;
import liquibase.change.core.CreateTableChange;
import liquibase.change.core.AddColumnChange;
import liquibase.change.core.RawSQLChange;
import liquibase.change.core.ModifyDataTypeChange;
import liquibase.database.DatabaseFactory;
import liquibase.database.OfflineConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;

import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** Non-Docker guard for the additive claim migration and null-tenant authority invariant. */
class AppliedObservationChangelogStaticValidationTest {

    @Test
    void lifecycleTailWidensBaselinePlanProcessIdentityWithoutAddingADuplicateColumn()
            throws Exception {
        String baseline = java.nio.file.Files.readString(java.nio.file.Path.of("../db-design.sql"));
        assertThat(baseline).contains("PROC_INST_ID_     VARCHAR2(64)");

        var changes = parsedChanges();
        assertThat(changes.stream()
                .flatMap(changeSet -> changeSet.getChanges().stream())
                .filter(AddColumnChange.class::isInstance)
                .map(AddColumnChange.class::cast)
                .filter(change -> "CM_PLAN_ITEM".equals(change.getTableName()))
                .flatMap(change -> change.getColumns().stream())
                .map(column -> column.getName()))
                .doesNotContain("PROC_INST_ID_");
        assertThat(changes.stream()
                .flatMap(changeSet -> changeSet.getChanges().stream())
                .filter(ModifyDataTypeChange.class::isInstance)
                .map(ModifyDataTypeChange.class::cast))
                .anySatisfy(change -> {
                    assertThat(change.getTableName()).isEqualTo("CM_PLAN_ITEM");
                    assertThat(change.getColumnName()).isEqualTo("PROC_INST_ID_");
                    assertThat(change.getNewDataType()).isEqualTo("VARCHAR2(128 BYTE)");
                });
    }

    @Test
    void everyObservationSchemaMutationIncludingInitialCreationIsGranularAndRestartGuarded()
            throws Exception {
        var lifecycle = parsedChanges().stream()
                .dropWhile(change -> !"cm-applied-engine-observation-structure-guard"
                        .equals(change.getId()))
                .takeWhile(change -> !"cm-production-engine-command-columns-guard"
                        .equals(change.getId()))
                .toList();

        assertThat(lifecycle).isNotEmpty();
        assertThat(lifecycle).allSatisfy(change -> {
            assertThat(change.getChanges()).hasSize(1);
            assertThat(change.getPreconditions()).isNotNull();
        });
        var initialCreation = lifecycle.stream().limit(8).toList();
        assertThat(initialCreation).extracting(change -> change.getId())
                .containsExactly(
                        "cm-applied-engine-observation-structure-guard",
                        "cm-applied-engine-observation",
                        "cm-applied-engine-observation-status-constraint",
                        "cm-applied-engine-observation-status-timestamps-constraint",
                        "cm-applied-engine-observation-authority-index-structure-guard",
                        "cm-applied-engine-observation-authority-index",
                        "cm-applied-engine-observation-status-index-structure-guard",
                        "cm-applied-engine-observation-status-index");
        assertThat(initialCreation.stream()
                .filter(change -> !change.getId().endsWith("structure-guard")))
                .allSatisfy(change -> {
            assertThat(change.getPreconditions().getOnFail().toString()).isEqualTo("MARK_RAN");
            assertThat(change.getPreconditions().getOnError().toString()).isEqualTo("HALT");
        });
        assertThat(initialCreation.stream()
                .filter(change -> change.getId().endsWith("structure-guard")))
                .allSatisfy(change -> {
                    assertThat(change.getPreconditions().getOnFail().toString())
                            .isEqualTo("HALT");
                    assertThat(change.getPreconditions().getOnError().toString())
                            .isEqualTo("HALT");
                });
    }

    @Test
    void masterChangelogPlacesTheAppliedObservationTailAfterExistingMigrations() throws Exception {
        var resources = new ClassLoaderResourceAccessor();
        var connection = new OfflineConnection(
                "offline:oracle?changeLogFile=target/applied-observation-offline.csv", resources);
        var database = DatabaseFactory.getInstance()
                .findCorrectDatabaseImplementation(connection);
        try (var liquibase = new Liquibase(
                "db/changelog/db.changelog-master.xml", resources, database)) {
            liquibase.validate();

            var changes = liquibase.getDatabaseChangeLog().getChangeSets();
            assertThat(changes).extracting(change -> change.getId())
                    .contains("cm-applied-engine-observation",
                            "cm-engine-observation-hardening-kind",
                            "cm-engine-observation-byte-semantics",
                            "cm-engine-observation-final-state-guard")
                    .containsSubsequence(
                            "cm-engine-observation-channel-engine-index",
                            "cm-engine-observation-byte-semantics",
                            "cm-engine-observation-final-state-guard",
                            "cm-production-engine-command-columns-guard");

            var appliedObservation = changes.stream()
                    .filter(change -> "cm-applied-engine-observation".equals(change.getId()))
                    .findFirst().orElseThrow();
            var table = appliedObservation.getChanges().stream()
                    .filter(CreateTableChange.class::isInstance)
                    .map(CreateTableChange.class::cast)
                    .findFirst()
                    .orElseThrow();
            assertThat(table.getColumns()).extracting(column -> column.getName())
                    .contains("CLAIM_TOKEN_");
            var authorityIndex = changes.stream()
                    .filter(change -> "cm-applied-engine-observation-authority-index"
                            .equals(change.getId()))
                    .findFirst().orElseThrow();
            String sql = authorityIndex.getChanges().stream()
                    .filter(RawSQLChange.class::isInstance)
                    .map(RawSQLChange.class::cast)
                    .map(RawSQLChange::getSql)
                    .collect(Collectors.joining("\n"));
            assertThat(sql)
                    .contains("CREATE UNIQUE INDEX UQ_CM_AEO_AUTH_FINGERPRINT")
                    .contains("CASE WHEN TENANT_ID_ IS NULL THEN 1 ELSE 0 END")
                    .contains("FINGERPRINT_");

            var hardening = changes.stream()
                    .filter(change -> change.getId().startsWith("cm-engine-observation-hardening"))
                    .toList();
            assertThat(hardening.stream()
                    .flatMap(changeSet -> changeSet.getChanges().stream())
                    .filter(AddColumnChange.class::isInstance)
                    .map(AddColumnChange.class::cast)
                    .flatMap(change -> change.getColumns().stream())
                    .map(column -> column.getName()))
                    .contains("OBSERVATION_KIND_", "IGNORED_AT_", "PROC_INST_ID_");
            String hardeningSql = hardening.stream()
                    .flatMap(changeSet -> changeSet.getChanges().stream())
                    .filter(RawSQLChange.class::isInstance)
                    .map(RawSQLChange.class::cast)
                    .map(RawSQLChange::getSql)
                    .collect(Collectors.joining("\n"));
            assertThat(hardeningSql)
                    .contains("IGNORED_STALE")
                    .contains("IGNORED_AT_")
                    .doesNotContain("UPDATE CM_PLAN_ITEM")
                    .doesNotContain("UPDATE CM_TASK");

            var channelIdentity = changes.stream()
                    .filter(change -> change.getId().startsWith("cm-engine-observation-channel"))
                    .toList();
            assertThat(channelIdentity.stream()
                    .flatMap(changeSet -> changeSet.getChanges().stream())
                    .filter(AddColumnChange.class::isInstance)
                    .map(AddColumnChange.class::cast)
                    .flatMap(change -> change.getColumns().stream())
                    .map(column -> column.getName()))
                    .containsExactly("ENGINE_ID_", "PROC_DEF_ID_");
            assertThat(channelIdentity.stream()
                    .flatMap(changeSet -> changeSet.getChanges().stream())
                    .filter(RawSQLChange.class::isInstance)
                    .map(RawSQLChange.class::cast)
                    .map(RawSQLChange::getSql)
                    .collect(Collectors.joining("\n")))
                    .doesNotContain("UPDATE CM_APPLIED_ENGINE_OBSERVATION");
        }
    }

    @Test
    void productionCommandMigrationFailsClosedBeforeRestartableOracleDdl() throws Exception {
        var production = parsedChanges().stream()
                .dropWhile(change -> !"cm-production-engine-command-columns-guard"
                        .equals(change.getId()))
                .toList();

        assertThat(production).extracting(change -> change.getId())
                .startsWith(
                        "cm-production-engine-command-columns-guard",
                        "cm-production-engine-command-columns",
                        "cm-production-engine-command-status-migration-guard",
                        "cm-production-engine-command-legacy-due-migration-guard",
                        "cm-production-engine-command-status-migration-drop",
                        "cm-production-engine-command-legacy-due-migration-drop",
                        "cm-production-engine-command-status-width-guard",
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
                        "cm-engine-command-action-table-guard",
                        "cm-engine-command-action-table",
                        "cm-production-engine-command-invariants-guard",
                        "cm-production-engine-command-counter-invariants",
                        "cm-production-engine-command-lease-invariants",
                        "cm-engine-command-action-invariants",
                        "cm-production-engine-command-normalize-retry-time",
                        "cm-production-engine-command-temporal-guard",
                        "cm-production-engine-command-temporal-invariants",
                        "cm-production-engine-command-temporal-v2-guard",
                        "cm-production-engine-command-temporal-v1-drop",
                        "cm-production-engine-command-temporal-v2",
                        "cm-production-engine-command-objects-guard");
        assertThat(production).extracting(change -> change.getId()).containsSubsequence(
                "cm-engine-command-transition-table-guard",
                "cm-engine-command-transition-table",
                "cm-engine-command-transition-status-width-guard",
                "cm-engine-command-transition-status-width-v2",
                "cm-engine-command-transition-baseline",
                "cm-engine-command-transition-format-guard",
                "cm-engine-command-transition-format-v1-drop",
                "cm-engine-command-transition-format-v2",
                "cm-engine-command-transition-objects-guard",
                "cm-engine-command-action-sequence-constraint",
                "cm-engine-command-transition-command-fk",
                "cm-engine-command-transition-action-fk",
                "cm-production-engine-command-byte-semantics",
                "cm-production-engine-command-final-state-guard");
        assertThat(production).extracting(change -> change.getId())
                .endsWith("cm-production-engine-command-final-state-guard");
        assertThat(production.stream().filter(change -> change.getId().endsWith("guard")))
                .allSatisfy(guard -> {
                    assertThat(guard.getPreconditions()).isNotNull();
                    assertThat(guard.getPreconditions().getOnFail().toString()).isEqualTo("HALT");
                    assertThat(guard.getPreconditions().getOnError().toString()).isEqualTo("HALT");
                });
        String productionSql = production.stream()
                .flatMap(changeSet -> changeSet.getChanges().stream())
                .filter(RawSQLChange.class::isInstance)
                .map(RawSQLChange.class::cast)
                .map(RawSQLChange::getSql)
                .collect(Collectors.joining("\n"));
        assertThat(productionSql)
                .contains("RAW_LEGACY_CLAIM_TOKEN_ = CLAIM_TOKEN_")
                .contains("RAW_LEGACY_CLAIMED_AT_ = CLAIMED_AT_")
                .contains("RAW_LEGACY_ATTEMPTS_ = ATTEMPTS_")
                .contains("CLAIM_TOKEN_ = NULL")
                .contains("CLAIMED_AT_ = NULL")
                .contains("CM_ENGINE_COMMAND_ACTION")
                .contains("CM_ENGINE_COMMAND_TRANSITION")
                .contains("UQ_CM_ENGCMD_IDEMPOTENCY")
                .doesNotContain("DBMS_CRYPTO");
    }

    private static java.util.List<liquibase.changelog.ChangeSet> parsedChanges() throws Exception {
        var resources = new ClassLoaderResourceAccessor();
        var connection = new OfflineConnection(
                "offline:oracle?changeLogFile=target/applied-observation-restart.csv", resources);
        var database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(connection);
        try (var liquibase = new Liquibase(
                "db/changelog/db.changelog-master.xml", resources, database)) {
            liquibase.validate();
            return java.util.List.copyOf(liquibase.getDatabaseChangeLog().getChangeSets());
        }
    }
}
