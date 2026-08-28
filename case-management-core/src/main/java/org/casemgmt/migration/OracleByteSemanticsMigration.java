package org.casemgmt.migration;

import liquibase.change.custom.CustomTaskChange;
import liquibase.database.Database;
import liquibase.database.jvm.JdbcConnection;
import liquibase.exception.CustomChangeException;
import liquibase.exception.SetupException;
import liquibase.exception.ValidationErrors;
import liquibase.resource.ResourceAccessor;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** Restart-safe conversion of contract VARCHAR2 columns to explicit Oracle BYTE semantics. */
public final class OracleByteSemanticsMigration implements CustomTaskChange {

    private String contract;

    public void setContract(String contract) {
        this.contract = contract;
    }

    @Override
    public void execute(Database database) throws CustomChangeException {
        if (!(database.getConnection() instanceof JdbcConnection jdbc)) {
            throw new CustomChangeException("BYTE-semantics migration requires JDBC");
        }
        try {
            migrate(jdbc.getUnderlyingConnection(), targets(contract));
        } catch (SQLException ex) {
            throw new CustomChangeException(
                    "Unable to convert " + contract + " VARCHAR2 columns to BYTE semantics", ex);
        }
    }

    static List<Target> targets(String contract) {
        OracleFinalSchemaPrecondition.SchemaContract schema = switch (contract) {
            case "production-command" -> OracleFinalSchemaPrecondition.productionCommandContract();
            case "engine-observation" -> OracleFinalSchemaPrecondition.engineObservationContract();
            default -> throw new IllegalArgumentException("Unknown BYTE-semantics contract: " + contract);
        };
        return schema.columns().stream()
                .filter(column -> "VARCHAR2".equals(column.type()))
                .map(column -> new Target(column.table(), column.name(), column.size()))
                .toList();
    }

    private static void migrate(Connection connection, List<Target> targets) throws SQLException,
            CustomChangeException {
        var conversions = new ArrayList<Target>();
        for (Target target : targets) {
            String semantics;
            try (var statement = connection.prepareStatement("""
                    SELECT CHAR_USED FROM USER_TAB_COLUMNS
                    WHERE TABLE_NAME=? AND COLUMN_NAME=? AND DATA_TYPE='VARCHAR2'
                    """)) {
                statement.setString(1, target.table());
                statement.setString(2, target.column());
                try (var row = statement.executeQuery()) {
                    if (!row.next()) {
                        throw new CustomChangeException("Missing VARCHAR2 column " + target.qualified());
                    }
                    semantics = row.getString(1);
                    if (row.next()) {
                        throw new CustomChangeException("Duplicate VARCHAR2 metadata for "
                                + target.qualified());
                    }
                }
            }
            if ("B".equals(semantics)) continue;
            if (!"C".equals(semantics)) {
                throw new CustomChangeException("Unsupported length semantics for "
                        + target.qualified());
            }
            try (var statement = connection.prepareStatement("SELECT COUNT(*) FROM "
                    + target.table() + " WHERE LENGTHB(" + target.column() + ") > ?")) {
                statement.setInt(1, target.bytes());
                try (var row = statement.executeQuery()) {
                    row.next();
                    if (row.getLong(1) != 0) {
                        throw new CustomChangeException("BYTE conversion would truncate "
                                + target.qualified());
                    }
                }
            }
            conversions.add(target);
        }
        for (Target target : conversions) {
            try (var statement = connection.createStatement()) {
                statement.execute("ALTER TABLE " + target.table() + " MODIFY ("
                        + target.column() + " VARCHAR2(" + target.bytes() + " BYTE))");
            }
        }
    }

    record Target(String table, String column, int bytes) {
        Target {
            if (!table.matches("[A-Z][A-Z0-9_]*") || !column.matches("[A-Z][A-Z0-9_]*")
                    || bytes <= 0) {
                throw new IllegalArgumentException("Unsafe BYTE conversion target");
            }
        }

        String qualified() {
            return table + "." + column;
        }
    }

    @Override
    public String getConfirmationMessage() {
        return contract + " VARCHAR2 columns use BYTE semantics";
    }

    @Override
    public void setUp() throws SetupException {
        // No external resources are used.
    }

    @Override
    public void setFileOpener(ResourceAccessor resourceAccessor) {
        // No changelog-relative resources are used.
    }

    @Override
    public ValidationErrors validate(Database database) {
        var errors = new ValidationErrors();
        if (!"production-command".equals(contract) && !"engine-observation".equals(contract)) {
            errors.addError("Unknown BYTE-semantics contract: " + contract);
        }
        return errors;
    }
}
