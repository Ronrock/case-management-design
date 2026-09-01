package org.casemgmt.observation;

import java.time.Instant;
import java.util.Objects;

/** Total ordering position for an engine-history row. */
public record ObservationCursor(Instant timestamp, String id) implements Comparable<ObservationCursor> {
    public ObservationCursor {
        Objects.requireNonNull(timestamp, "timestamp");
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id must not be blank");
    }

    @Override
    public int compareTo(ObservationCursor other) {
        int byTime = timestamp.compareTo(other.timestamp);
        return byTime != 0 ? byTime : id.compareTo(other.id);
    }
}
