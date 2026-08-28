package org.casemgmt.projection;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** Oracle grammar guard for the SQL text sent by the projection adapter. */
class JdbcCaseProjectionSqlStaticValidationTest {

    private static final Pattern QUALIFIED_SET_COLUMN = Pattern.compile(
            "UPDATE\\s+CM_(?:PLAN_ITEM|TASK)\\s+target\\s+SET\\s+(?:(?!WHERE)[\\s\\S])*?\\btarget\\.[A-Z_]+\\s*=",
            Pattern.CASE_INSENSITIVE);

    @Test
    void guardedOracleUpdatesNeverQualifySetTargetsWithTheTableAlias() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/org/casemgmt/projection/JdbcCaseProjectionPort.java"));

        assertThat(QUALIFIED_SET_COLUMN.matcher(source).find())
                .as("Oracle rejects alias-qualified columns on the left side of UPDATE SET")
                .isFalse();
    }
}
