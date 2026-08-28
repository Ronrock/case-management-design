package org.casemgmt;

import liquibase.Liquibase;
import liquibase.change.core.CreateTableChange;
import liquibase.change.core.AddColumnChange;
import liquibase.change.core.RawSQLChange;
import liquibase.database.DatabaseFactory;
import liquibase.database.OfflineConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;

import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** Non-Docker guard for the additive claim migration and null-tenant authority invariant. */
class AppliedObservationChangelogStaticValidationTest {

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
                    .endsWith("cm-applied-engine-observation", "cm-engine-observation-hardening",
                            "cm-engine-observation-channel-identity");

            var appliedObservation = changes.get(changes.size() - 3);
            var table = appliedObservation.getChanges().stream()
                    .filter(CreateTableChange.class::isInstance)
                    .map(CreateTableChange.class::cast)
                    .findFirst()
                    .orElseThrow();
            assertThat(table.getColumns()).extracting(column -> column.getName())
                    .contains("CLAIM_TOKEN_");
            String sql = appliedObservation.getChanges().stream()
                    .filter(RawSQLChange.class::isInstance)
                    .map(RawSQLChange.class::cast)
                    .map(RawSQLChange::getSql)
                    .collect(Collectors.joining("\n"));
            assertThat(sql)
                    .contains("CREATE UNIQUE INDEX UQ_CM_AEO_AUTH_FINGERPRINT")
                    .contains("CASE WHEN TENANT_ID_ IS NULL THEN 1 ELSE 0 END")
                    .contains("FINGERPRINT_");

            var hardening = changes.get(changes.size() - 2);
            assertThat(hardening.getChanges().stream()
                    .filter(AddColumnChange.class::isInstance)
                    .map(AddColumnChange.class::cast)
                    .flatMap(change -> change.getColumns().stream())
                    .map(column -> column.getName()))
                    .contains("OBSERVATION_KIND_", "IGNORED_AT_", "PROC_INST_ID_");
            String hardeningSql = hardening.getChanges().stream()
                    .filter(RawSQLChange.class::isInstance)
                    .map(RawSQLChange.class::cast)
                    .map(RawSQLChange::getSql)
                    .collect(Collectors.joining("\n"));
            assertThat(hardeningSql)
                    .contains("IGNORED_STALE")
                    .contains("IGNORED_AT_")
                    .doesNotContain("UPDATE CM_PLAN_ITEM")
                    .doesNotContain("UPDATE CM_TASK");

            var channelIdentity = changes.getLast();
            assertThat(channelIdentity.getChanges().stream()
                    .filter(AddColumnChange.class::isInstance)
                    .map(AddColumnChange.class::cast)
                    .flatMap(change -> change.getColumns().stream())
                    .map(column -> column.getName()))
                    .containsExactly("ENGINE_ID_", "PROC_DEF_ID_");
            assertThat(channelIdentity.getChanges().stream()
                    .filter(RawSQLChange.class::isInstance)
                    .map(RawSQLChange.class::cast)
                    .map(RawSQLChange::getSql)
                    .collect(Collectors.joining("\n")))
                    .doesNotContain("UPDATE CM_APPLIED_ENGINE_OBSERVATION");
        }
    }
}
