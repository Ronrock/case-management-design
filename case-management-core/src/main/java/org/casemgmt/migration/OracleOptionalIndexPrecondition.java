package org.casemgmt.migration;

import liquibase.database.Database;
import liquibase.database.jvm.JdbcConnection;
import liquibase.exception.CustomPreconditionErrorException;
import liquibase.exception.CustomPreconditionFailedException;
import liquibase.precondition.CustomPrecondition;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Accepts an absent Oracle index so its following create changeset can run, but requires an
 * already-present index to match its full physical dictionary representation exactly.
 */
public final class OracleOptionalIndexPrecondition implements CustomPrecondition {

    private String name;
    private String table;
    private String unique;
    private String entries;

    public void setName(String name) {
        this.name = name;
    }

    public void setTable(String table) {
        this.table = table;
    }

    public void setUnique(String unique) {
        this.unique = unique;
    }

    public void setEntries(String entries) {
        this.entries = entries;
    }

    @Override
    public void check(Database database)
            throws CustomPreconditionFailedException, CustomPreconditionErrorException {
        if (!(database.getConnection() instanceof JdbcConnection jdbc)) {
            throw new CustomPreconditionErrorException("Exact index validation requires JDBC");
        }
        var expected = expected();
        try {
            var connection = jdbc.getUnderlyingConnection();
            if (OracleFinalSchemaPrecondition.SchemaContract.indexExists(
                    connection, expected.name())
                    && !OracleFinalSchemaPrecondition.SchemaContract.indexMatches(
                    connection, expected)) {
                throw new CustomPreconditionFailedException(
                        expected.name() + " has an incompatible structure");
            }
        } catch (SQLException ex) {
            throw new CustomPreconditionErrorException(
                    "Unable to read Oracle index metadata for " + expected.name(), ex);
        }
    }

    OracleFinalSchemaPrecondition.IndexContract expected() {
        String requiredName = required(name, "name");
        String requiredTable = required(table, "table");
        String requiredUnique = required(unique, "unique");
        if (!List.of("true", "false").contains(requiredUnique)) {
            throw new IllegalArgumentException("unique must be true or false");
        }
        List<String> orderedEntries = Arrays.stream(required(entries, "entries").split("\\|", -1))
                .map(String::trim)
                .map(entry -> required(entry, "entry"))
                .toList();
        return new OracleFinalSchemaPrecondition.IndexContract(
                requiredName, requiredTable, Boolean.parseBoolean(requiredUnique), orderedEntries);
    }

    private static String required(String value, String field) {
        String required = Objects.requireNonNull(value, field).trim();
        if (required.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return required;
    }
}
