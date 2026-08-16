package org.casemgmt.search;

import java.util.List;

public record SearchFacetGroup(String field, String label, List<SearchFacetValue> values) {
    public SearchFacetGroup {
        values = values == null ? List.of() : List.copyOf(values);
    }
}
