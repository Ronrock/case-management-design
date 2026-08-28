package org.casemgmt.engine;

import liquibase.change.custom.CustomTaskChange;
import liquibase.database.Database;
import liquibase.database.jvm.JdbcConnection;
import liquibase.exception.CustomChangeException;
import liquibase.exception.SetupException;
import liquibase.exception.ValidationErrors;
import liquibase.resource.ResourceAccessor;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Objects;

/** Seeds one immutable version-zero decision digest for every pre-ledger command. */
public final class EngineCommandTransitionBaselineBackfill implements CustomTaskChange {

    @Override
    public void execute(Database database) throws CustomChangeException {
        if (!(database.getConnection() instanceof JdbcConnection jdbc)) {
            throw new CustomChangeException("Command transition baseline backfill requires JDBC");
        }
        try {
            backfill(jdbc.getUnderlyingConnection());
        } catch (SQLException | IllegalArgumentException ex) {
            throw new CustomChangeException("Unable to seed command transition baselines", ex);
        }
    }

    static void backfill(Connection connection) throws SQLException, CustomChangeException {
        try (PreparedStatement commands = connection.prepareStatement("""
                SELECT ID_,TENANT_ID_,OPERATION_ID_,TYPE_,TARGET_IDENTITY_,STATUS_,
                       NEXT_ATTEMPT_AT_,DECIDED_AT_,SAFE_ERROR_CODE_,SAFE_SUMMARY_,
                       TOTAL_DISPATCH_ATTEMPTS_,AUTO_ATTEMPTS_,BUDGET_EPOCH_,AUTO_BUDGET_RESET_,
                       ROW_VERSION_,ACTION_COUNT_,ORIGINAL_STATUS_,MIGRATION_BASELINE_ACTIVE_,
                       LEGACY_ROW_ID_,LEGACY_MIGRATION_REF_,LEGACY_MIGRATED_AT_,
                       LEGACY_FAILURE_COUNT_
                FROM CM_ENGINE_COMMAND ORDER BY ID_
                """); ResultSet rows = commands.executeQuery()) {
            while (rows.next()) seed(connection, rows);
        }
        try (PreparedStatement count = connection.prepareStatement("""
                SELECT (SELECT COUNT(*) FROM CM_ENGINE_COMMAND),
                       (SELECT COUNT(*) FROM CM_ENGINE_COMMAND_TRANSITION WHERE VERSION_=0),
                       (SELECT COUNT(*) FROM CM_ENGINE_COMMAND_TRANSITION WHERE VERSION_<>0)
                FROM DUAL
                """); ResultSet totals = count.executeQuery()) {
            totals.next();
            if (totals.getLong(1) != totals.getLong(2) || totals.getLong(3) != 0) {
                throw new CustomChangeException(
                        "Command transition baseline rows do not exactly cover command rows");
            }
        }
    }

    private static void seed(Connection connection, ResultSet row)
            throws SQLException, CustomChangeException {
        long version = row.getLong("ROW_VERSION_");
        long actionCount = row.getLong("ACTION_COUNT_");
        String originalStatus = row.getString("ORIGINAL_STATUS_");
        Integer active = row.getObject("MIGRATION_BASELINE_ACTIVE_", Integer.class);
        if (version != 0 || actionCount != 0
                || originalStatus == null && !"PENDING".equals(row.getString("STATUS_"))
                || originalStatus != null && !Integer.valueOf(1).equals(active)) {
            throw new CustomChangeException(
                    "Existing command state cannot be truthfully backfilled without transition history: "
                            + row.getString("ID_"));
        }
        EngineCommandPolicy.CommandContext command = new EngineCommandPolicy.CommandContext(
                row.getString("TENANT_ID_"), row.getString("OPERATION_ID_"),
                row.getString("ID_"), EngineCommand.Type.valueOf(row.getString("TYPE_")),
                row.getString("TARGET_IDENTITY_"));
        EngineCommandPolicy.Decision baseline = baseline(row, command, originalStatus);
        String kind = originalStatus == null ? "BASELINE_NATIVE" : "BASELINE_LEGACY";
        String outcomeJson = EngineCommandTransitionHistory.encodeBaseline(baseline);
        String digest = EngineCommandTransitionHistory.digestDecision(baseline);
        try (PreparedStatement existing = connection.prepareStatement("""
                SELECT TENANT_ID_,OPERATION_ID_,COMMAND_TYPE_,EXPECTED_TARGET_,FROM_STATUS_,
                       TO_STATUS_,OUTCOME_FORMAT_,OUTCOME_KIND_,OUTCOME_JSON_,ACTION_SEQUENCE_,
                       DECIDED_AT_,PREVIOUS_DECISION_DIGEST_,NEXT_DECISION_DIGEST_
                FROM CM_ENGINE_COMMAND_TRANSITION WHERE COMMAND_ID_=? AND VERSION_=0
                """)) {
            existing.setString(1, command.commandId());
            try (ResultSet found = existing.executeQuery()) {
                if (found.next()) {
                    boolean exact = command.tenantId().equals(found.getString(1))
                            && command.operationId().equals(found.getString(2))
                            && command.commandType().name().equals(found.getString(3))
                            && command.expectedTargetIdentity().equals(found.getString(4))
                            && baseline.status().name().equals(found.getString(5))
                            && baseline.status().name().equals(found.getString(6))
                            && found.getInt(7) == EngineCommandTransitionHistory.FORMAT_VERSION
                            && kind.equals(found.getString(8))
                            && outcomeJson.equals(found.getString(9))
                            && found.getObject(10) == null
                            && baseline.decidedAt().equals(
                                    found.getObject(11, OffsetDateTime.class))
                            && found.getString(12) == null
                            && digest.equals(found.getString(13))
                            && !found.next();
                    if (!exact) throw new CustomChangeException(
                            "Existing command transition baseline is incompatible: "
                                    + command.commandId());
                    return;
                }
            }
        }
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO CM_ENGINE_COMMAND_TRANSITION
                  (COMMAND_ID_,VERSION_,TENANT_ID_,OPERATION_ID_,COMMAND_TYPE_,EXPECTED_TARGET_,
                   FROM_STATUS_,TO_STATUS_,OUTCOME_FORMAT_,OUTCOME_KIND_,OUTCOME_JSON_,
                   ACTION_SEQUENCE_,DECIDED_AT_,PREVIOUS_DECISION_DIGEST_,NEXT_DECISION_DIGEST_)
                VALUES (?,0,?,?,?,?,?,?,?,?,?,NULL,?,NULL,?)
                """)) {
            insert.setString(1, command.commandId());
            insert.setString(2, command.tenantId());
            insert.setString(3, command.operationId());
            insert.setString(4, command.commandType().name());
            insert.setString(5, command.expectedTargetIdentity());
            insert.setString(6, baseline.status().name());
            insert.setString(7, baseline.status().name());
            insert.setInt(8, EngineCommandTransitionHistory.FORMAT_VERSION);
            insert.setString(9, kind);
            insert.setString(10, outcomeJson);
            insert.setObject(11, baseline.decidedAt());
            insert.setString(12, digest);
            insert.executeUpdate();
        }
    }

    private static EngineCommandPolicy.Decision baseline(
            ResultSet row, EngineCommandPolicy.CommandContext command, String originalStatus)
            throws SQLException {
        if ("DONE".equals(originalStatus)) {
            return LegacyDoneCommandMigration.migrate(new LegacyDoneCommandMigration.LegacyDoneRow(
                    command, row.getString("LEGACY_ROW_ID_"),
                    row.getString("LEGACY_MIGRATION_REF_"),
                    row.getObject("LEGACY_MIGRATED_AT_", OffsetDateTime.class),
                    Objects.requireNonNull(row.getObject(
                            "LEGACY_FAILURE_COUNT_", Integer.class))));
        }
        return new EngineCommandPolicy.Decision(
                EngineCommandStatus.valueOf(row.getString("STATUS_")),
                row.getObject("DECIDED_AT_", OffsetDateTime.class),
                row.getObject("NEXT_ATTEMPT_AT_", OffsetDateTime.class),
                row.getString("SAFE_ERROR_CODE_"), row.getString("SAFE_SUMMARY_"),
                row.getLong("TOTAL_DISPATCH_ATTEMPTS_"), row.getInt("AUTO_ATTEMPTS_"),
                row.getLong("BUDGET_EPOCH_"), row.getInt("AUTO_BUDGET_RESET_") == 1,
                null, null, null, EngineCommandPolicy.ActionLedgerSummary.empty());
    }

    @Override public String getConfirmationMessage() {
        return "Command transition baselines seeded";
    }
    @Override public void setUp() throws SetupException { }
    @Override public void setFileOpener(ResourceAccessor resourceAccessor) { }
    @Override public ValidationErrors validate(Database database) { return new ValidationErrors(); }
}
