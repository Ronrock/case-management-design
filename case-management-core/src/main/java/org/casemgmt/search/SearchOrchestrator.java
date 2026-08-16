package org.casemgmt.search;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SearchOrchestrator {

    private final List<SearchProvider> providers;

    public SearchOrchestrator(List<SearchProvider> providers) {
        this.providers = providers == null ? List.of() : List.copyOf(providers);
    }

    public SearchResponse search(SearchQuery query) {
        List<SearchProvider> selected = providers.stream()
                .filter(provider -> provider.supportsAny(query.scopes()))
                .sorted(Comparator.comparingInt(provider -> provider.estimateCost(query)))
                .toList();

        List<SearchWarning> warnings = new ArrayList<>();
        List<SearchFacetGroup> facets = new ArrayList<>();
        List<SearchProviderStatus> statuses = new ArrayList<>();
        Map<String, SearchResultItem> deduplicated = new LinkedHashMap<>();

        if (selected.isEmpty()) {
            warnings.add(new SearchWarning("no-provider",
                    "No registered search provider supports the requested scopes", "search"));
            return new SearchResponse(List.of(), new SearchPage(query.page(), query.pageSize()),
                    List.of(), warnings, List.of());
        }

        int requested = Math.max((query.page() + 1) * query.pageSize(), query.pageSize());
        SearchQuery providerQuery = query.withPage(0, requested);
        for (SearchProvider provider : selected) {
            SearchProviderStatus status = provider.status();
            statuses.add(status);
            if ("disabled".equals(status.status()) || "unavailable".equals(status.status())) {
                warnings.add(new SearchWarning("provider-unavailable",
                        "Search provider is not available", provider.providerId()));
                continue;
            }
            try {
                SearchProviderResult result = provider.search(providerQuery);
                if (result.providerStatus() != null) {
                    statuses.set(statuses.size() - 1, result.providerStatus());
                }
                result.warnings().forEach(warnings::add);
                result.facets().forEach(facets::add);
                for (SearchResultItem item : result.items()) {
                    deduplicated.merge(dedupKey(item), item, SearchOrchestrator::higherScored);
                }
            } catch (RuntimeException e) {
                warnings.add(new SearchWarning("provider-unavailable",
                        "Search provider failed: " + provider.providerId(), provider.providerId()));
            }
        }

        int offset = query.page() * query.pageSize();
        List<SearchResultItem> pageItems = deduplicated.values().stream()
                .sorted(Comparator.comparingDouble(SearchResultItem::score).reversed()
                        .thenComparing(SearchResultItem::title, Comparator.nullsLast(String::compareToIgnoreCase))
                        .thenComparing(SearchResultItem::id))
                .skip(offset)
                .limit(query.pageSize())
                .toList();

        return new SearchResponse(pageItems, new SearchPage(query.page(), query.pageSize()),
                facets, warnings, query.includeProviderStatus() ? statuses : List.of());
    }

    public List<SearchProviderStatus> providerStatuses() {
        return providers.stream().map(SearchProvider::status).toList();
    }

    private static String dedupKey(SearchResultItem item) {
        return item.resultType().wireName() + ":" + item.id();
    }

    private static SearchResultItem higherScored(SearchResultItem left, SearchResultItem right) {
        return left.score() >= right.score() ? left : right;
    }
}
