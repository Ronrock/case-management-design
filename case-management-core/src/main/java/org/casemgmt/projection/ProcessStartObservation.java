package org.casemgmt.projection;

import java.time.OffsetDateTime;

/** Explicit engine evidence that one already-linked process instance is currently active. */
public record ProcessStartObservation(
        String caseId,
        String processInstanceId,
        OffsetDateTime engineUpdatedAt,
        OffsetDateTime observedAt) {
}
