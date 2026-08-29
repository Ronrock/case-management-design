package org.casemgmt.engine.remote;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Reads a complete fixed-size history window. Rows are sorted locally by the engine's stable
 * timestamp/id identity before the caller advances its durable cursor.
 */
final class RemoteHistoryPagination {
    private RemoteHistoryPagination() { }

    record Row<T>(Instant timestamp, String id, T value) {
        Row {
            Objects.requireNonNull(timestamp, "timestamp");
            if (id == null || id.isBlank()) throw new IllegalArgumentException("id must not be blank");
        }
    }

    @FunctionalInterface
    interface PageFetcher<T> {
        List<Row<T>> fetch(int firstResult, int maxResults);
    }

    static <T> List<Row<T>> readAll(int pageSize, PageFetcher<T> fetcher) {
        if (pageSize < 1) throw new IllegalArgumentException("pageSize must be positive");
        List<Row<T>> all = new ArrayList<>();
        for (int offset = 0; ; offset += pageSize) {
            List<Row<T>> page = List.copyOf(fetcher.fetch(offset, pageSize));
            all.addAll(page);
            if (page.size() < pageSize) break;
        }
        all.sort(Comparator.comparing(Row<T>::timestamp).thenComparing(Row::id));
        return List.copyOf(all);
    }
}
