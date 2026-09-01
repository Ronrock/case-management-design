package org.casemgmt.engine.remote;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RemoteHistoryPaginationTest {

    @Test
    void readsAll1201RowsWithOneTimestampInStableTimestampAndIdOrder() {
        Instant sameInstant = Instant.parse("2026-08-29T10:00:00Z");
        List<RemoteHistoryPagination.Row<String>> source = new ArrayList<>();
        for (int index = 1200; index >= 0; index--) {
            source.add(new RemoteHistoryPagination.Row<>(sameInstant,
                    "task-%04d".formatted(index), "value-%04d".formatted(index)));
        }

        List<String> seen = RemoteHistoryPagination.readAll(500,
                (offset, limit) -> source.subList(Math.min(offset, source.size()),
                        Math.min(offset + limit, source.size())))
                .stream().map(RemoteHistoryPagination.Row::value).toList();

        assertThat(seen).hasSize(1201)
                .containsExactlyElementsOf(java.util.stream.IntStream.range(0, 1201)
                        .mapToObj(i -> "value-%04d".formatted(i)).toList());
    }

    @Test
    void readsBoundarySizesWithoutSkippingRows() {
        for (int size : List.of(499, 500, 501, 1000)) {
            List<RemoteHistoryPagination.Row<Integer>> source = java.util.stream.IntStream
                    .range(0, size)
                    .mapToObj(i -> new RemoteHistoryPagination.Row<>(
                            Instant.parse("2026-08-29T10:00:00Z").plusSeconds(i / 5),
                            "id-%04d".formatted(i), i))
                    .toList();

            assertThat(RemoteHistoryPagination.readAll(500,
                    (offset, limit) -> source.subList(Math.min(offset, source.size()),
                            Math.min(offset + limit, source.size()))))
                    .extracting(RemoteHistoryPagination.Row::value)
                    .containsExactlyElementsOf(java.util.stream.IntStream.range(0, size).boxed().toList());
        }
    }
}
