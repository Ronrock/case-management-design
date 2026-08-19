package org.casemgmt.search;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

public record SearchQuery(String tenantId, String workerId, List<String> groups, String q,
                          List<SearchScope> scopes, Map<String, Object> filters,
                          List<String> facets, int page, int pageSize,
                          boolean includeProviderStatus) {

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
            filters = Map.copyOf(cleaned);
        }
        facets = facets == null ? List.of() : List.copyOf(facets);
        page = Math.max(page, 0);
        pageSize = Math.clamp(pageSize, 1, 200);
    }

    public SearchQuery(String tenantId, String q, List<SearchScope> scopes,
                       Map<String, Object> filters, List<String> facets,
                       int page, int pageSize, boolean includeProviderStatus) {
        this(tenantId, null, List.of(), q, scopes, filters, facets, page, pageSize,
                includeProviderStatus);
    }

    public SearchQuery withPage(int newPage, int newPageSize) {
        return new SearchQuery(tenantId, workerId, groups, q, scopes, filters, facets, newPage,
                newPageSize, includeProviderStatus);
    }
}
