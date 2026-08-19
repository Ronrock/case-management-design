package org.casemgmt.search;

import java.util.List;

public record SearchProviderResult(List<SearchResultItem> items, List<SearchFacetGroup> facets,
                                   List<SearchWarning> warnings,
                                   SearchProviderStatus providerStatus) {

    public SearchProviderResult {
        items = items == null ? List.of() : List.copyOf(items);
        facets = facets == null ? List.of() : List.copyOf(facets);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public static SearchProviderResult empty(SearchProviderStatus status) {
        return new SearchProviderResult(List.of(), List.of(), List.of(), status);
    }
}
