package org.casemgmt.sla;

import java.time.OffsetDateTime;

/** Runtime SLA clock for one case/target pair — backs {@code CM_SLA_RECORD}. */
public record SlaRecord(String id, String caseId, String targetId, String status,
                        OffsetDateTime startedAt, OffsetDateTime dueAt, OffsetDateTime warnAt,
                        OffsetDateTime pausedAt, String pausedReason, long pausedTotalSeconds,
                        long version) {}
