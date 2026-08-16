package org.casemgmt.search;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SearchOrchestratorTest {

    @Test
    void returnsWarningWhenNoProviderSupportsTheRequestedScope() {
        SearchOrchestrator orchestrator = new SearchOrchestrator(List.of());

        SearchResponse response = orchestrator.search(new SearchQuery("t1", "abc",
                List.of(SearchScope.CASES), Map.of(), List.of(), 0, 25, true));

        assertThat(response.items()).isEmpty();
        assertThat(response.warnings()).extracting(SearchWarning::code).containsExactly("no-provider");
    }

    @Test
    void mergesRanksDeduplicatesAndReportsProviderStatus() {
        SearchResultItem lowScore = item("eng-a:1", 10, "cases-a");
        SearchResultItem highScore = item("eng-a:1", 90, "cases-b");
        SearchResultItem second = item("eng-a:2", 50, "cases-b");
        SearchOrchestrator orchestrator = new SearchOrchestrator(List.of(
                provider("cases-a", 20, List.of(lowScore)),
                provider("cases-b", 10, List.of(highScore, second))));

        SearchResponse response = orchestrator.search(new SearchQuery("t1", "BK-1",
                List.of(SearchScope.CASES), Map.of(), List.of(), 0, 25, true));

        assertThat(response.items()).extracting(SearchResultItem::id)
                .containsExactly("eng-a:1", "eng-a:2");
        assertThat(response.items().get(0).score()).isEqualTo(90);
        assertThat(response.providerStatuses()).extracting(SearchProviderStatus::id)
                .containsExactly("cases-b", "cases-a");
    }

    @Test
    void providerFailureBecomesWarningInsteadOfAFalseCompleteResult() {
        SearchOrchestrator orchestrator = new SearchOrchestrator(List.of(new SearchProvider() {
            @Override public String providerId() { return "broken"; }
            @Override public List<SearchScope> supportedScopes() { return List.of(SearchScope.CASES); }
            @Override public SearchProviderStatus status() {
                return new SearchProviderStatus("broken", "available", supportedScopes(),
                        false, false, 30, 0, true, List.of());
            }
            @Override public SearchProviderResult search(SearchQuery query) {
                throw new IllegalStateException("boom");
            }
        }));

        SearchResponse response = orchestrator.search(new SearchQuery("t1", "abc",
                List.of(SearchScope.CASES), Map.of(), List.of(), 0, 25, true));

        assertThat(response.items()).isEmpty();
        assertThat(response.warnings()).extracting(SearchWarning::code)
                .containsExactly("provider-unavailable");
    }

    private static SearchProvider provider(String id, int cost, List<SearchResultItem> items) {
        return new SearchProvider() {
            @Override public String providerId() { return id; }
            @Override public List<SearchScope> supportedScopes() { return List.of(SearchScope.CASES); }
            @Override public int estimateCost(SearchQuery query) { return cost; }
            @Override public SearchProviderStatus status() {
                return new SearchProviderStatus(id, "available", supportedScopes(), false, true,
                        30, 0, false, List.of());
            }
            @Override public SearchProviderResult search(SearchQuery query) {
                return new SearchProviderResult(items, List.of(), List.of(), status());
            }
        };
    }

    private static SearchResultItem item(String id, double score, String provider) {
        return new SearchResultItem(id, SearchResultType.CASE, id, "Case " + id, null,
                provider, score, List.of("businessKey"), List.of(), Map.of(), "fresh");
    }
}
