package org.casemgmt.search;

import java.util.Collection;
import java.util.List;

public interface SearchProvider {

    String providerId();

    List<SearchScope> supportedScopes();

    default int estimateCost(SearchQuery query) {
        return 100;
    }

    SearchProviderStatus status();

    SearchProviderResult search(SearchQuery query);

    default boolean supportsAny(Collection<SearchScope> scopes) {
        return supportedScopes().stream().anyMatch(scopes::contains);
    }
}
