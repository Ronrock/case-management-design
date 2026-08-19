package org.casemgmt.search;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

public record SearchQuery(String tenantId, String workerId, List<String> groups, String q,
                          List<SearchScope> scopes, Map<String, Object> filters,
                          List<String> facets, int page, int pageSize,
                          boolean includeProviderStatus) {

    public static final int MAX_PAGE_SIZE = 200;
    public static final int MAX_RESULT_WINDOW = 10_000;

    public SearchQuery {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("Search requires a tenant");
        }
        workerId = workerId == null || workerId.isBlank() ? null : workerId.trim();
        groups = groups == null ? List.of() : List.copyOf(groups);
        q = q == null || q.isBlank() ? null : q.trim();
        scopes = scopes == null || scopes.isEmpty()
                ? List.of(SearchScope.CASES)
                : List.copyOf(scopes);
        if (filters == null || filters.isEmpty()) {
            filters = Map.of();
        } else {
            Map<String, Object> cleaned = new LinkedHashMap<>();
            filters.forEach((key, value) -> {
                if (key != null && value != null) {
                    cleaned.put(key, value);
                }
            });
            CaseStateSearchFilter.normalize(cleaned);
            filters = Map.copyOf(cleaned);
        }
        facets = facets == null ? List.of() : List.copyOf(facets);
        page = Math.max(page, 0);
        pageSize = Math.clamp(pageSize, 1, MAX_RESULT_WINDOW);
        if (((long) page + 1L) * pageSize > MAX_RESULT_WINDOW) {
            throw new IllegalArgumentException("Search result window exceeds "
                    + String.format("%,d", MAX_RESULT_WINDOW) + " items");
        }
    }

    public SearchQuery(String tenantId, String q, List<SearchScope> scopes,
                       Map<String, Object> filters, List<String> facets,
                       int page, int pageSize, boolean includeProviderStatus) {
        this(tenantId, null, List.of(), q, scopes, filters, facets, page, pageSize,
                includeProviderStatus);
    }

    public int offset() {
        return Math.toIntExact((long) page * pageSize);
    }

    public int resultWindowEnd() {
        return Math.toIntExact(((long) page + 1L) * pageSize);
    }

    public SearchQuery forProviderWindow(int windowSize) {
        return new SearchQuery(tenantId, workerId, groups, q, scopes, filters, facets, 0,
                windowSize, includeProviderStatus);
    }
}
