package org.casemgmt;

import liquibase.Liquibase;
import liquibase.change.core.AddNotNullConstraintChange;
import liquibase.change.core.RawSQLChange;
import liquibase.database.DatabaseFactory;
import liquibase.database.OfflineConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;

import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** Database-independent guard for the changelog used by the Oracle WS2 rehearsal. */
class Ws2ChangelogStaticValidationTest {

    @Test
    void masterChangelogParsesValidatesAndKeepsWs2InItsOriginalContiguousOrder() throws Exception {
        var resources = new ClassLoaderResourceAccessor();
        var connection = new OfflineConnection(
                "offline:oracle?changeLogFile=target/ws2-offline-databasechangelog.csv",
                resources);
        var database = DatabaseFactory.getInstance()
                .findCorrectDatabaseImplementation(connection);
        try (var liquibase = new Liquibase(
                "db/changelog/db.changelog-master.xml", resources, database)) {
            liquibase.validate();

            var changes = liquibase.getDatabaseChangeLog().getChangeSets();
            int firstWs2 = -1;
            for (int index = 0; index < changes.size(); index++) {
                if ("cm-bpmn-release-exact-identity".equals(changes.get(index).getId())) {
                    firstWs2 = index;
                    break;
                }
            }

            assertThat(firstWs2).isPositive();
            assertThat(changes.get(firstWs2 - 1).getId())
                    .isEqualTo("cm-projected-milestone-idempotency");
            assertThat(changes).hasSizeGreaterThanOrEqualTo(firstWs2 + 4);
            assertThat(changes.subList(firstWs2, firstWs2 + 4))
                    .extracting(change -> change.getId())
                    .containsExactly(
                            "cm-bpmn-release-exact-identity",
                            "cm-bpmn-binding-lifecycle-identity",
                            "cm-bpmn-binding-active-authority",
                            "cm-bpmn-root-correlation-separation");

            var activeAuthority = changes.stream()
                    .filter(change -> "cm-bpmn-binding-active-authority".equals(change.getId()))
                    .findFirst()
                    .orElseThrow();
            assertThat(activeAuthority.getChanges())
                    .noneMatch(AddNotNullConstraintChange.class::isInstance);

            String authoritySql = activeAuthority.getChanges().stream()
                    .filter(RawSQLChange.class::isInstance)
                    .map(RawSQLChange.class::cast)
                    .map(RawSQLChange::getSql)
                    .collect(Collectors.joining("\n"));
            assertThat(authoritySql)
                    .contains("ROW_NUMBER() OVER")
                    .contains(":NEW.CASE_DEF_KEY_ := authoritative_key")
                    .contains(":NEW.TENANT_ID_ := authoritative_tenant")
                    .contains("CREATE UNIQUE INDEX UQ_CM_CDB_ACTIVE_KEY");
        }
    }
}
