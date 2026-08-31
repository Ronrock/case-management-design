package org.casemgmt;

import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SlaCalendarRevisionMigrationTest extends OracleTestBase {

    private static final List<String> CHANGESETS = List.of(
            "cm-sla-calendar-revision-table-shape-guard",
            "cm-sla-calendar-revision-table",
            "cm-sla-calendar-digest-column-shape-guard",
            "cm-sla-calendar-digest-column");

    @Test
    void cleanSchemaHasExactRevisionCatalogAndNullableOccurrenceDigest() {
        assertRevisionSchema();
        assertThat(jdbc().sql("""
                SELECT NULLABLE FROM USER_TAB_COLUMNS
                WHERE TABLE_NAME = 'CM_SLA_RECORD' AND COLUMN_NAME = 'CALENDAR_SHA256_'""")
                .query(String.class).single()).isEqualTo("Y");
    }

    @Test
    void upgradesLegacySchemaWithoutCopyingTenantlessCalendarRowsAndCanRestart() throws Exception {
        jdbc().sql("ALTER TABLE CM_SLA_RECORD DROP COLUMN CALENDAR_SHA256_").update();
        jdbc().sql("DROP TABLE CM_BUSINESS_CALENDAR_REVISION PURGE").update();
        jdbc().sql("""
                INSERT INTO CM_BUSINESS_CALENDAR (ID_, NAME_, TENANT_ID_, DEFINITION_JSON_)
                VALUES ('legacy', 'Legacy', NULL, '{}')""").update();
        jdbc().sql("DELETE FROM DATABASECHANGELOG WHERE ID IN (:ids) AND AUTHOR = 'casemgmt'")
                .param("ids", CHANGESETS).update();

        applyMaster();
        applyMaster();

        assertRevisionSchema();
        assertThat(jdbc().sql("SELECT COUNT(*) FROM CM_BUSINESS_CALENDAR_REVISION")
                .query(Integer.class).single()).isZero();
        assertThat(jdbc().sql("SELECT COUNT(*) FROM CM_BUSINESS_CALENDAR WHERE ID_ = 'legacy'")
                .query(Integer.class).single()).isEqualTo(1);
    }

    private void assertRevisionSchema() {
        assertThat(jdbc().sql("""
                SELECT COLUMN_NAME FROM USER_TAB_COLUMNS
                WHERE TABLE_NAME = 'CM_BUSINESS_CALENDAR_REVISION'
                ORDER BY COLUMN_ID""").query(String.class).list()).containsExactly(
                "TENANT_ID_", "CALENDAR_ID_", "REVISION_", "NAME_", "DEFINITION_JSON_",
                "SHA256_", "CREATED_AT_");
        assertThat(jdbc().sql("""
                SELECT CONSTRAINT_NAME FROM USER_CONSTRAINTS
                WHERE TABLE_NAME = 'CM_BUSINESS_CALENDAR_REVISION'
                  AND CONSTRAINT_NAME IN ('PK_CM_BCAL_REV', 'UQ_CM_BCAL_REV_SHA',
                                          'CK_CM_BCAL_REV_JSON')
                ORDER BY CONSTRAINT_NAME""").query(String.class).list()).containsExactly(
                "CK_CM_BCAL_REV_JSON", "PK_CM_BCAL_REV", "UQ_CM_BCAL_REV_SHA");
    }

    private void applyMaster() throws Exception {
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
