package org.casemgmt;

import liquibase.Liquibase;
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
                    .endsWith("cm-applied-engine-observation");

            var appliedObservation = changes.getLast();
            String sql = appliedObservation.getChanges().stream()
                    .filter(RawSQLChange.class::isInstance)
                    .map(RawSQLChange.class::cast)
                    .map(RawSQLChange::getSql)
                    .collect(Collectors.joining("\n"));
            assertThat(sql)
                    .contains("CREATE UNIQUE INDEX UQ_CM_AEO_AUTH_FINGERPRINT")
                    .contains("CASE WHEN TENANT_ID_ IS NULL THEN 1 ELSE 0 END")
                    .contains("FINGERPRINT_");
        }
    }
}
