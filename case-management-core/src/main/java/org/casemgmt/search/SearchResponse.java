package org.casemgmt.search;

import java.util.List;

public record SearchResponse(List<SearchResultItem> items, SearchPage page,
                             List<SearchFacetGroup> facets, List<SearchWarning> warnings,
                             List<SearchProviderStatus> providerStatuses) {

    public SearchResponse {
        items = items == null ? List.of() : List.copyOf(items);
        facets = facets == null ? List.of() : List.copyOf(facets);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        providerStatuses = providerStatuses == null ? List.of() : List.copyOf(providerStatuses);
    }
}
