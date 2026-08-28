package org.casemgmt.observation;

import java.util.List;

/** Stable caller-facing result for one observation envelope. */
public record ApplyResult(String observationId, ApplyStatus status,
                          long caseVersion, List<String> eventIds) {

    /** A duplicate deliberately avoids a secondary case read. */
    public static final long UNCHANGED_CASE_VERSION = -1;

    public ApplyResult {
        if (observationId == null || observationId.isBlank()) {
            throw new IllegalArgumentException("observationId must not be blank");
        }
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        eventIds = eventIds == null ? List.of() : List.copyOf(eventIds);
    }
}
