package org.casemgmt.search;

import org.casemgmt.OracleTestBase;
import org.casemgmt.domain.CaseInstance;
import org.casemgmt.domain.CasePriority;
import org.casemgmt.domain.CaseState;
import org.casemgmt.repo.CaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CaseProjectionSearchProviderTest extends OracleTestBase {

    private CaseRepository cases;
    private CaseProjectionSearchProvider provider;

    @BeforeEach
    void setUp() {
        cases = new CaseRepository(jdbc());
        provider = new CaseProjectionSearchProvider(cases);
        jdbc().sql("""
                INSERT INTO CM_CASE_DEF (ID_, KEY_, VERSION_NO_, NAME_, TENANT_ID_)
                VALUES ('widget-review:1', 'widget-review', 1, 'Widget review', 't1')""").update();
    }

    @Test
    void searchesVisibleCasesByBusinessKeyAndTitleInsideTheCallerTenant() {
        cases.insert(newCase("eng-a:1", "t1", "BK-100", "Broken payment widget", CaseState.ACTIVE));
        cases.insert(newCase("eng-a:2", "t2", "BK-100", "Broken payment widget", CaseState.ACTIVE));
        cases.insert(newCase("eng-a:3", "t1", "BK-200", "Card maintenance", CaseState.ACTIVE));

        SearchProviderResult byBusinessKey = provider.search(new SearchQuery("t1", "BK-100",
                List.of(SearchScope.CASES), Map.of(), List.of(), 0, 25, true));
        SearchProviderResult byTitle = provider.search(new SearchQuery("t1", "payment",
                List.of(SearchScope.CASES), Map.of(), List.of(), 0, 25, true));

        assertThat(byBusinessKey.items()).extracting(SearchResultItem::id)
                .containsExactly("eng-a:1");
        assertThat(byBusinessKey.items().get(0).matchedFields()).contains("businessKey");
        assertThat(byTitle.items()).extracting(SearchResultItem::id)
                .containsExactly("eng-a:1");
        assertThat(byTitle.items().get(0).matchedFields()).contains("title");
    }

    @Test
    void appliesStructuredFiltersBeforeReturningResults() {
        cases.insert(newCase("eng-a:4", "t1", "BK-300", "Open request", CaseState.ACTIVE));
        cases.insert(newCase("eng-a:5", "t1", "BK-301", "Closed request", CaseState.CLOSED));

        SearchProviderResult response = provider.search(new SearchQuery("t1", "request",
                List.of(SearchScope.CASES), Map.of("state", "CLOSED"), List.of(), 0, 25, true));

        assertThat(response.items()).extracting(SearchResultItem::id).containsExactly("eng-a:5");
    }

    private static CaseInstance newCase(String id, String tenantId, String businessKey,
                                        String title, CaseState state) {
        OffsetDateTime now = OffsetDateTime.now();
        return new CaseInstance(id, "eng-a", tenantId, "widget-review:1", "widget-review", 1,
                businessKey, title, state, CasePriority.HIGH, null, null, "alice", "NONE",
                null, null, Map.of("channel", "web"), 0L, now, now, null);
    }
}
