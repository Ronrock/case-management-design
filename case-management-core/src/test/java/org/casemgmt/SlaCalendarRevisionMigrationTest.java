package org.casemgmt;

import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Test
    void rejectsExistingRevisionTableWithWrongConstraintColumnOrder() throws Exception {
        replaceRevisionTable("CALENDAR_ID_, TENANT_ID_, REVISION_",
                "TENANT_ID_, SHA256_, CALENDAR_ID_", "SYSTIMESTAMP");
        try {
            assertThatThrownBy(this::applyMaster)
                    .isInstanceOf(Exception.class)
                    .hasStackTraceContaining("CM_BUSINESS_CALENDAR_REVISION must have the exact");
        } finally {
            restoreRevisionTable();
        }
    }

    @Test
    void rejectsExistingRevisionTableWithWrongCreatedAtDefault() throws Exception {
        replaceRevisionTable("TENANT_ID_, CALENDAR_ID_, REVISION_",
                "TENANT_ID_, CALENDAR_ID_, SHA256_", "CURRENT_TIMESTAMP");
        try {
            assertThatThrownBy(this::applyMaster)
                    .isInstanceOf(Exception.class)
                    .hasStackTraceContaining("CM_BUSINESS_CALENDAR_REVISION must have the exact");
        } finally {
            restoreRevisionTable();
        }
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
        assertThat(constraintColumns("PK_CM_BCAL_REV"))
                .isEqualTo("TENANT_ID_,CALENDAR_ID_,REVISION_");
        assertThat(constraintColumns("UQ_CM_BCAL_REV_SHA"))
                .isEqualTo("TENANT_ID_,CALENDAR_ID_,SHA256_");
        assertThat(jdbc().sql("""
                SELECT REGEXP_REPLACE(UPPER(DATA_DEFAULT_VC), '[[:space:]]', '')
                FROM USER_TAB_COLUMNS
                WHERE TABLE_NAME = 'CM_BUSINESS_CALENDAR_REVISION'
                  AND COLUMN_NAME = 'CREATED_AT_'""").query(String.class).single())
                .isEqualTo("SYSTIMESTAMP");
    }

    private String constraintColumns(String constraintName) {
        return jdbc().sql("""
                SELECT LISTAGG(COLUMN_NAME, ',') WITHIN GROUP (ORDER BY POSITION)
                FROM USER_CONS_COLUMNS WHERE CONSTRAINT_NAME = :constraintName""")
                .param("constraintName", constraintName).query(String.class).single();
    }

    private void replaceRevisionTable(String primaryKeyColumns, String uniqueColumns,
                                      String createdAtDefault) {
        jdbc().sql("DROP TABLE CM_BUSINESS_CALENDAR_REVISION PURGE").update();
        jdbc().sql("""
                CREATE TABLE CM_BUSINESS_CALENDAR_REVISION (
                  TENANT_ID_       VARCHAR2(64 BYTE)  NOT NULL,
                  CALENDAR_ID_     VARCHAR2(128 BYTE) NOT NULL,
                  REVISION_        NUMBER(10) NOT NULL,
                  NAME_            VARCHAR2(255 BYTE) NOT NULL,
                  DEFINITION_JSON_ CLOB NOT NULL,
                  SHA256_          VARCHAR2(64 BYTE) NOT NULL,
                  CREATED_AT_      TIMESTAMP WITH TIME ZONE DEFAULT %s NOT NULL,
                  CONSTRAINT PK_CM_BCAL_REV PRIMARY KEY (%s),
                  CONSTRAINT UQ_CM_BCAL_REV_SHA UNIQUE (%s),
                  CONSTRAINT CK_CM_BCAL_REV_JSON CHECK (DEFINITION_JSON_ IS JSON)
                )""".formatted(createdAtDefault, primaryKeyColumns, uniqueColumns)).update();
        jdbc().sql("""
                DELETE FROM DATABASECHANGELOG
                WHERE ID = 'cm-sla-calendar-revision-table-shape-guard'
                  AND AUTHOR = 'casemgmt'""").update();
    }

    private void restoreRevisionTable() throws Exception {
        jdbc().sql("DROP TABLE CM_BUSINESS_CALENDAR_REVISION PURGE").update();
        jdbc().sql("""
                DELETE FROM DATABASECHANGELOG
                WHERE ID IN ('cm-sla-calendar-revision-table-shape-guard',
                             'cm-sla-calendar-revision-table')
                  AND AUTHOR = 'casemgmt'""").update();
        applyMaster();
        assertRevisionSchema();
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
