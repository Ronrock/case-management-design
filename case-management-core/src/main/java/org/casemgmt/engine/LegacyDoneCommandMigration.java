package org.casemgmt.engine;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * Package-isolated bridge for Task 2's historical {@code DONE} row mapper.
 *
 * <p>This type is deliberately not a public runtime API. The persistence migration mapper must
 * live in this package, construct a row from the legacy repository result, and call this bridge.
 */
final class LegacyDoneCommandMigration {

    private LegacyDoneCommandMigration() {
    }

    static EngineCommandPolicy.Decision migrate(LegacyDoneRow row) {
        Objects.requireNonNull(row, "row");
        int startedDispatches = Math.incrementExact(row.legacyFailureCount());
        var evidence = new EngineCommandPolicy.LegacyConfirmationEvidence(
                row.command(), row.legacyRowId(), row.migrationReference(),
                row.migratedAt(), row.legacyFailureCount());
        return new EngineCommandPolicy.Decision(
                EngineCommandStatus.CONFIRMED, row.migratedAt(), null, null, null,
                startedDispatches, startedDispatches, 0, false,
                null, evidence, null, null,
                EngineCommandPolicy.ActionLedgerSummary.empty());
    }

    /** Strongly typed repository result for one historical row already proven to be {@code DONE}. */
    static record LegacyDoneRow(
            EngineCommandPolicy.CommandContext command,
            String legacyRowId,
            String migrationReference,
            OffsetDateTime migratedAt,
            int legacyFailureCount) {
        LegacyDoneRow {
            Objects.requireNonNull(command, "command");
            Objects.requireNonNull(legacyRowId, "legacyRowId");
            Objects.requireNonNull(migrationReference, "migrationReference");
            Objects.requireNonNull(migratedAt, "migratedAt");
            if (legacyFailureCount < 0
                    || legacyFailureCount >= EngineCommandPolicy.MAX_AUTOMATIC_ATTEMPTS) {
                throw new IllegalArgumentException(
                        "Legacy DONE failure count must be between zero and five");
            }
        }
    }
}
