package org.casemgmt.engine;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * Persistence-only reconstruction boundary for historical PoC rows.
 *
 * <p>The live dispatcher cannot construct legacy confirmation evidence. Only a repository row
 * carrying the complete retained migration tuple can enter through this mapper.
 */
public final class EngineCommandPersistenceMapper {

    private EngineCommandPersistenceMapper() {
    }

    public static EngineCommandPolicy.Decision rehydrateLegacyDone(LegacyDoneDatabaseRow row) {
        Objects.requireNonNull(row, "row");
        return LegacyDoneCommandMigration.migrate(new LegacyDoneCommandMigration.LegacyDoneRow(
                row.command(), row.legacyRowId(), row.migrationReference(),
                row.migratedAt(), row.legacyFailureCount()));
    }

    /** Exact columns retained by the production migration for one old {@code DONE} row. */
    public record LegacyDoneDatabaseRow(
            EngineCommandPolicy.CommandContext command,
            String legacyRowId,
            String migrationReference,
            OffsetDateTime migratedAt,
            int legacyFailureCount) {
        public LegacyDoneDatabaseRow {
            Objects.requireNonNull(command, "command");
            Objects.requireNonNull(legacyRowId, "legacyRowId");
            Objects.requireNonNull(migrationReference, "migrationReference");
            Objects.requireNonNull(migratedAt, "migratedAt");
        }
    }
}
