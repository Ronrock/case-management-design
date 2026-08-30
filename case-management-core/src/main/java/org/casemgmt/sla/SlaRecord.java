package org.casemgmt.sla;

import java.time.OffsetDateTime;

/** Runtime SLA clock for one case/target pair — backs {@code CM_SLA_RECORD}. */
public record SlaRecord(String id, String caseId, String targetId, String status,
                        OffsetDateTime startedAt, OffsetDateTime dueAt, OffsetDateTime warnAt,
                        OffsetDateTime pausedAt, String pausedReason, long pausedTotalSeconds,
                        long version, OffsetDateTime terminalAt) {

    /** Compatibility constructor for callers creating a nonterminal occurrence. */
    public SlaRecord(String id, String caseId, String targetId, String status,
                     OffsetDateTime startedAt, OffsetDateTime dueAt, OffsetDateTime warnAt,
                     OffsetDateTime pausedAt, String pausedReason, long pausedTotalSeconds,
                     long version) {
        this(id, caseId, targetId, status, startedAt, dueAt, warnAt, pausedAt, pausedReason,
                pausedTotalSeconds, version, null);
    }
}
