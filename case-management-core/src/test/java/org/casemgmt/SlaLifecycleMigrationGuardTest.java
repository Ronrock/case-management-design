package org.casemgmt;

import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;

import java.sql.Connection;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real-Oracle rehearsal: an existing column must be the expected type, never merely present. */
class SlaLifecycleMigrationGuardTest extends OracleTestBase {

    @Test
    void rejectsAnExistingCancelledAtColumnWithTheWrongOracleType() throws Exception {
        jdbc().sql("ALTER TABLE CM_SLA_RECORD DROP COLUMN CANCELLED_AT_").update();
        jdbc().sql("ALTER TABLE CM_SLA_RECORD ADD CANCELLED_AT_ VARCHAR2(40)").update();
        jdbc().sql("""
                DELETE FROM DATABASECHANGELOG
                WHERE ID = 'cm-sla-root-terminalization-column-shape-guard'
                  AND AUTHOR = 'casemgmt'""").update();

        try {
            assertThatThrownBy(this::applyMasterChangelog)
                    .hasMessageContaining("CANCELLED_AT_")
                    .hasMessageContaining("TIMESTAMP WITH TIME ZONE");
        } finally {
            jdbc().sql("ALTER TABLE CM_SLA_RECORD DROP COLUMN CANCELLED_AT_").update();
            jdbc().sql("ALTER TABLE CM_SLA_RECORD ADD CANCELLED_AT_ TIMESTAMP WITH TIME ZONE").update();
            applyMasterChangelog();
        }
    }

    private void applyMasterChangelog() throws Exception {
        try (Connection connection = dataSource().getConnection()) {
            var database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (var liquibase = new Liquibase("db/changelog/db.changelog-master.xml",
                    new ClassLoaderResourceAccessor(), database)) {
                liquibase.update("");
            }
        }
    }
}
