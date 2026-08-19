package org.casemgmt.search;

import org.casemgmt.OracleTestBase;
import org.casemgmt.domain.CaseInstance;
import org.casemgmt.domain.CasePriority;
import org.casemgmt.domain.CaseState;
import org.casemgmt.permissions.PermissionDecision;
import org.casemgmt.repo.CaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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
    void searchesVisibleCasesByPartialBusinessKeyAndTitleInsideTheCallerTenant() {
        cases.insert(newCase("eng-a:1", "t1", "BK-100", "Broken payment widget", CaseState.ACTIVE));
        cases.insert(newCase("eng-a:2", "t2", "BK-100", "Broken payment widget", CaseState.ACTIVE));
        cases.insert(newCase("eng-a:3", "t1", "BK-200", "Card maintenance", CaseState.ACTIVE));

        SearchProviderResult byBusinessKey = provider.search(query("K-10", Map.of()));
        SearchProviderResult byTitle = provider.search(query("PAYMENT", Map.of()));

        assertThat(byBusinessKey.items()).extracting(SearchResultItem::id)
                .containsExactly("eng-a:1");
        assertThat(byBusinessKey.items().get(0).matchedFields()).contains("businessKey");
        assertThat(byTitle.items()).extracting(SearchResultItem::id)
                .containsExactly("eng-a:1");
        assertThat(byTitle.items().get(0).matchedFields()).contains("title");
    }

    @Test
    void escapesOracleLikeWildcardsInFreeText() {
        cases.insert(newCase("eng-a:6", "t1", "BK-100%_SAFE",
                "Literal 100%_ marker", CaseState.ACTIVE));
        cases.insert(newCase("eng-a:7", "t1", "BK-100XA-SAFE",
                "Literal 100XA marker", CaseState.ACTIVE));

        SearchProviderResult response = provider.search(query("100%_", Map.of()));

        assertThat(response.items()).extracting(SearchResultItem::id)
                .containsExactly("eng-a:6");
        assertThat(response.items().get(0).matchedFields())
                .contains("businessKey", "title");
    }

    @Test
    void ranksExactBusinessKeyBeforePartialBusinessKeyAndTitleMatches() {
        cases.insert(newCase("eng-a:8", "t1", "BK-42", "Unrelated title", CaseState.ACTIVE));
        cases.insert(newCase("eng-a:9", "t1", "BK-42-SUFFIX", "Unrelated title", CaseState.ACTIVE));
        cases.insert(newCase("eng-a:10", "t1", "BK-999", "Contains BK-42 in title", CaseState.ACTIVE));

        SearchProviderResult response = provider.search(query("BK-42", Map.of()));

        assertThat(response.items()).extracting(SearchResultItem::id)
                .containsExactly("eng-a:8", "eng-a:9", "eng-a:10");
        assertThat(response.items()).extracting(SearchResultItem::score)
                .containsExactly(90.0, 70.0, 50.0);
    }

    @Test
    void appliesStructuredFiltersBeforeReturningResults() {
        cases.insert(newCase("eng-a:4", "t1", "BK-300", "Open request", CaseState.ACTIVE));
        cases.insert(newCase("eng-a:5", "t1", "BK-301", "Closed request", CaseState.CLOSED));

        SearchProviderResult response = provider.search(query("request", Map.of("state", "CLOSED")));

        assertThat(response.items()).extracting(SearchResultItem::id).containsExactly("eng-a:5");
    }

    @Test
    void filtersCaseResultsThroughWorkerPermissionsBeforeReturningAnything() {
        cases.insert(newCase("eng-a:11", "t1", "BK-ALLOW", "Allowed request", CaseState.ACTIVE));
        cases.insert(newCase("eng-a:12", "t1", "BK-DENY", "Denied request", CaseState.ACTIVE));
        provider = new CaseProjectionSearchProvider(cases, request -> request.resources().stream()
                .collect(Collectors.toMap(resource -> resource.id(),
                        resource -> Set.of("eng-a:11").contains(resource.id())
                                ? PermissionDecision.allow(resource.id())
                                : PermissionDecision.deny(resource.id()))));

        SearchProviderResult response = provider.search(query("request", Map.of()));

        assertThat(response.items()).extracting(SearchResultItem::id)
                .containsExactly("eng-a:11");
    }

    @Test
    void failsClosedWhenCaseAuthorizationIsUnavailable() {
        cases.insert(newCase("eng-a:13", "t1", "BK-DOWN", "Permission outage", CaseState.ACTIVE));
        provider = new CaseProjectionSearchProvider(cases, request -> {
            throw new IllegalStateException("permissions down");
        });

        SearchProviderResult response = provider.search(query("Permission", Map.of()));

        assertThat(response.items()).isEmpty();
        assertThat(response.warnings()).extracting(SearchWarning::code)
                .containsExactly("authorization-unavailable");
        assertThat(response.providerStatus().status()).isEqualTo("degraded");
    }

    private static CaseInstance newCase(String id, String tenantId, String businessKey,
                                        String title, CaseState state) {
        OffsetDateTime now = OffsetDateTime.now();
        return new CaseInstance(id, "eng-a", tenantId, "widget-review:1", "widget-review", 1,
                businessKey, title, state, CasePriority.HIGH, null, null, "alice", "NONE",
                null, null, Map.of("channel", "web"), 0L, now, now, null);
    }

    private static SearchQuery query(String text, Map<String, Object> filters) {
        return new SearchQuery("t1", "alice", List.of("users", "tenant:t1"), text,
                List.of(SearchScope.CASES), filters, List.of(), 0, 25, true);
    }
}
