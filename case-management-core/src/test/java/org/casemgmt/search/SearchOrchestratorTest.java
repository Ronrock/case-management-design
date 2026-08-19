package org.casemgmt.search;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.time.OffsetDateTime;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

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

    @Test
    void paginatesAfterMergingAndCanSuppressProviderStatus() {
        SearchOrchestrator orchestrator = new SearchOrchestrator(List.of(
                provider("cases-a", 10, List.of(
                        item("eng-a:1", 100, "cases-a"),
                        item("eng-a:2", 80, "cases-a"),
                        item("eng-a:3", 60, "cases-a")))));

        SearchResponse response = orchestrator.search(new SearchQuery("t1", "BK",
                List.of(SearchScope.CASES), Map.of(), List.of(), 1, 1, false));

        assertThat(response.items()).extracting(SearchResultItem::id)
                .containsExactly("eng-a:2");
        assertThat(response.page()).isEqualTo(new SearchPage(1, 1));
        assertThat(response.providerStatuses()).isEmpty();
    }

    @Test
    void usesRecencyAsTheStableTieBreakerAcrossProviders() {
        OffsetDateTime older = OffsetDateTime.parse("2026-01-01T10:00:00Z");
        OffsetDateTime newer = older.plusDays(1);
        SearchOrchestrator orchestrator = new SearchOrchestrator(List.of(
                provider("cases-a", 10, List.of(item("older", 50, "cases-a", older))),
                provider("cases-b", 20, List.of(item("newer", 50, "cases-b", newer)))));

        SearchResponse response = orchestrator.search(new SearchQuery("t1", "BK",
                List.of(SearchScope.CASES), Map.of(), List.of(), 0, 25, true));

        assertThat(response.items()).extracting(SearchResultItem::id)
                .containsExactly("newer", "older");
    }

    @Test
    void deepPagesUseAnInternalProviderWindowLargerThanThePublicPageLimit() {
        AtomicInteger providerWindow = new AtomicInteger();
        SearchProvider provider = new SearchProvider() {
            @Override public String providerId() { return "cases"; }
            @Override public List<SearchScope> supportedScopes() { return List.of(SearchScope.CASES); }
            @Override public SearchProviderStatus status() {
                return new SearchProviderStatus("cases", "available", supportedScopes(), false,
                        true, 30, 0, false, List.of());
            }
            @Override public SearchProviderResult search(SearchQuery query) {
                providerWindow.set(query.pageSize());
                List<SearchResultItem> items = IntStream.range(0, query.pageSize())
                        .mapToObj(i -> item("case-" + i, 1_000 - i, "cases"))
                        .toList();
                return new SearchProviderResult(items, List.of(), List.of(), status());
            }
        };
        SearchOrchestrator orchestrator = new SearchOrchestrator(List.of(provider));

        SearchResponse response = orchestrator.search(new SearchQuery("t1", "BK",
                List.of(SearchScope.CASES), Map.of(), List.of(), 2, 100, false));

        assertThat(providerWindow).hasValue(300);
        assertThat(response.items()).hasSize(100);
        assertThat(response.items().getFirst().id()).isEqualTo("case-200");
    }

    @Test
    void estimatesEachProviderCostOnlyOnce() {
        AtomicInteger calls = new AtomicInteger();
        SearchProvider provider = new SearchProvider() {
            @Override public String providerId() { return "cases"; }
            @Override public List<SearchScope> supportedScopes() { return List.of(SearchScope.CASES); }
            @Override public int estimateCost(SearchQuery query) { calls.incrementAndGet(); return 10; }
            @Override public SearchProviderStatus status() {
                return new SearchProviderStatus("cases", "available", supportedScopes(), false,
                        true, 30, 0, false, List.of());
            }
            @Override public SearchProviderResult search(SearchQuery query) {
                return new SearchProviderResult(List.of(), List.of(), List.of(), status());
            }
        };

        new SearchOrchestrator(List.of(provider)).search(new SearchQuery("t1", "BK",
                List.of(SearchScope.CASES), Map.of(), List.of(), 0, 25, false));

        assertThat(calls).hasValue(1);
    }

    @Test
    void rejectsPagesBeyondTheBoundedResultWindowWithoutOverflow() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new SearchQuery("t1", "BK",
                List.of(SearchScope.CASES), Map.of(), List.of(), Integer.MAX_VALUE, 200, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("10,000");
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
        return item(id, score, provider, null);
    }

    private static SearchResultItem item(String id, double score, String provider,
                                         OffsetDateTime updatedAt) {
        return new SearchResultItem(id, SearchResultType.CASE, id, "Case " + id, null,
                provider, score, List.of("businessKey"), List.of(), Map.of(), updatedAt, "fresh");
    }
}
