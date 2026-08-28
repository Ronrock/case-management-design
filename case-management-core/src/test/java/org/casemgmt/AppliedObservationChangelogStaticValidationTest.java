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
                    assertThat(change.getNewDataType()).isEqualTo("VARCHAR2(128)");
                });
    }

    @Test
    void everyObservationSchemaMutationIncludingInitialCreationIsGranularAndRestartGuarded()
            throws Exception {
        var lifecycle = parsedChanges().stream()
                .dropWhile(change -> !"cm-applied-engine-observation".equals(change.getId()))
                .toList();

        assertThat(lifecycle).isNotEmpty();
        assertThat(lifecycle).allSatisfy(change -> {
            assertThat(change.getChanges()).hasSize(1);
            assertThat(change.getPreconditions()).isNotNull();
        });
        var initialCreation = lifecycle.stream().limit(5).toList();
        assertThat(initialCreation).extracting(change -> change.getId())
                .containsExactly(
                        "cm-applied-engine-observation",
                        "cm-applied-engine-observation-status-constraint",
                        "cm-applied-engine-observation-status-timestamps-constraint",
                        "cm-applied-engine-observation-authority-index",
                        "cm-applied-engine-observation-status-index");
        assertThat(initialCreation).allSatisfy(change -> {
            assertThat(change.getPreconditions().getOnFail().toString()).isEqualTo("MARK_RAN");
            assertThat(change.getPreconditions().getOnError().toString()).isEqualTo("HALT");
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
                            "cm-engine-observation-hardening-kind")
                    .endsWith("cm-engine-observation-channel-engine-index");

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
