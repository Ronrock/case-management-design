package org.casemgmt.engine.remote;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Boundary-only high-volume release sentinel; transport/retry detail stays in its focused suite. */
class BpmnFirstRemoteHighVolumeIT {

    @Test
    void stableHistoryPagingRetainsEveryBoundarySizeIncluding1201EqualTimestampRows() {
        Instant sameTimestamp = Instant.parse("2026-08-30T10:00:00Z");
        for (int size : List.of(499, 500, 501, 1_000, 1_201)) {
            List<RemoteHistoryPagination.Row<Integer>> source = java.util.stream.IntStream
                    .range(0, size).mapToObj(index -> new RemoteHistoryPagination.Row<>(
                            sameTimestamp, "history-%05d".formatted(index), index)).toList();

            assertThat(RemoteHistoryPagination.readAll(500, (offset, limit) -> source.subList(
                    Math.min(offset, source.size()), Math.min(offset + limit, source.size()))))
                    .extracting(RemoteHistoryPagination.Row::value)
                    .containsExactlyElementsOf(java.util.stream.IntStream.range(0, size).boxed().toList());
        }
    }
}
