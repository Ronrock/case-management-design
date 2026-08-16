package org.casemgmt.search;

import java.util.List;

public record SearchProviderStatus(String id, String status, List<SearchScope> scopes,
                                   boolean supportsFacets, boolean supportsSuggestions,
                                   int maxProjectionLagSeconds, int currentProjectionLagSeconds,
                                   boolean partialResultsAllowed, List<SearchWarning> warnings) {

    public SearchProviderStatus {
        scopes = scopes == null ? List.of() : List.copyOf(scopes);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
