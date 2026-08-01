package org.casemgmt;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SchemaMigrationTest extends OracleTestBase {

    @BeforeEach
    void cleanSchema() {
        deleteAllCaseManagementData();
    }

    @Test
    void createsAll25TablesFromTheDesignDdl() {
        Integer tables = jdbc().sql("SELECT COUNT(*) FROM USER_TABLES WHERE TABLE_NAME LIKE 'CM!_%' ESCAPE '!'")
                .query(Integer.class).single();
        // 25 from db-design.sql + CM_ENGINE_COMMAND from the PoC changeset,
        // minus none. DATABASECHANGELOG* do not match the CM_ prefix.
        assertThat(tables).isEqualTo(26);
    }

    @Test
    void enforcesTheIsJsonConstraintOnCaseVariables() {
        jdbc().sql("""
                INSERT INTO CM_CASE_DEF (ID_, KEY_, VERSION_NO_, NAME_)
                VALUES ('d:1', 'd', 1, 'D')""").update();

        assertThatThrownBy(() -> jdbc().sql("""
                INSERT INTO CM_CASE (ID_, ENGINE_ID_, CASE_DEF_ID_, CASE_DEF_KEY_, CASE_DEF_VER_,
                                     STATE_, VARIABLES_JSON_)
                VALUES ('e:1', 'e', 'd:1', 'd', 1, 'ACTIVE', 'not json')""").update())
                .hasMessageContaining("CK_CM_CASE_VARS");
    }

    @Test
    void addsEngineSyncColumnToTasks() {
        Integer count = jdbc().sql("""
                SELECT COUNT(*) FROM USER_TAB_COLUMNS
                WHERE TABLE_NAME = 'CM_TASK' AND COLUMN_NAME = 'ENGINE_SYNC_'""")
                .query(Integer.class).single();
        assertThat(count).isEqualTo(1);
    }
}
