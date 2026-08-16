package org.casemgmt.search;

import org.casemgmt.domain.CaseInstance;
import org.casemgmt.domain.CaseState;
import org.casemgmt.repo.CaseRepository;
import org.casemgmt.repo.CaseSearchQuery;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class CaseProjectionSearchProvider implements SearchProvider {

    public static final String PROVIDER_ID = "case-projection";

    private final CaseRepository cases;

    public CaseProjectionSearchProvider(CaseRepository cases) {
        this.cases = cases;
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public List<SearchScope> supportedScopes() {
        return List.of(SearchScope.CASES);
    }

    @Override
    public int estimateCost(SearchQuery query) {
        return exactIdentifier(query.q()) ? 1 : 10;
    }

    @Override
    public SearchProviderStatus status() {
        return new SearchProviderStatus(PROVIDER_ID, "available", supportedScopes(),
                false, true, 30, 0, false, List.of());
    }

    @Override
    public SearchProviderResult search(SearchQuery query) {
        CaseSearchQuery caseQuery = new CaseSearchQuery(query.tenantId(), query.q(),
                states(query), stringFilter(query, "assignee"), stringFilter(query, "caseDefinitionKey"),
                stringFilter(query, "businessKey"), query.page() * query.pageSize(), query.pageSize());
        List<SearchResultItem> items = cases.search(caseQuery).stream()
                .map(c -> toResult(c, query.q()))
                .toList();
        return new SearchProviderResult(items, List.of(), List.of(), status());
    }

    private static List<CaseState> states(SearchQuery query) {
        Object raw = query.filters().containsKey("state")
                ? query.filters().get("state")
                : query.filters().get("status");
        if (raw == null) {
            return List.of();
        }
        if (raw instanceof List<?> list) {
            return list.stream().map(Object::toString).map(CaseProjectionSearchProvider::state).toList();
        }
        return List.of(state(raw.toString()));
    }

    private static CaseState state(String value) {
        return CaseState.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }

    private static String stringFilter(SearchQuery query, String name) {
        Object value = query.filters().get(name);
        return value == null || value.toString().isBlank() ? null : value.toString().trim();
    }

    private static SearchResultItem toResult(CaseInstance c, String q) {
        List<String> matched = matchedFields(c, q);
        return new SearchResultItem(c.id(), SearchResultType.CASE, c.id(), c.title(),
                summary(c), PROVIDER_ID, score(c, q, matched), matched, highlights(c, q),
                Map.of(
                        "engineId", c.engineId(),
                        "tenantId", c.tenantId(),
                        "caseDefinitionKey", c.caseDefKey(),
                        "caseDefinitionVersion", c.caseDefVersion(),
                        "businessKey", c.businessKey() == null ? "" : c.businessKey(),
                        "state", c.state().name(),
                        "priority", c.priority().name(),
                        "assignee", c.assignee() == null ? "" : c.assignee(),
                        "slaStatus", c.slaStatus() == null ? "NONE" : c.slaStatus(),
                        "version", c.version()),
                "fresh");
    }

    private static String summary(CaseInstance c) {
        String businessKey = c.businessKey() == null ? "no business key" : c.businessKey();
        return c.caseDefKey() + " / " + businessKey + " / " + c.state().name();
    }

    private static List<String> matchedFields(CaseInstance c, String q) {
        if (q == null || q.isBlank()) {
            return List.of();
        }
        String term = q.toLowerCase(Locale.ROOT);
        List<String> fields = new ArrayList<>();
        if (c.id().equalsIgnoreCase(q)) {
            fields.add("caseId");
        }
        if (c.businessKey() != null && c.businessKey().toLowerCase(Locale.ROOT).contains(term)) {
            fields.add("businessKey");
        }
        if (c.title() != null && c.title().toLowerCase(Locale.ROOT).contains(term)) {
            fields.add("title");
        }
        return fields;
    }

    private static List<String> highlights(CaseInstance c, String q) {
        if (q == null || q.isBlank()) {
            return List.of();
        }
        String term = q.toLowerCase(Locale.ROOT);
        if (c.title() != null && c.title().toLowerCase(Locale.ROOT).contains(term)) {
            return List.of(c.title());
        }
        return List.of();
    }

    private static double score(CaseInstance c, String q, List<String> matchedFields) {
        if (q == null || q.isBlank()) {
            return 10;
        }
        if (c.id().equalsIgnoreCase(q)) {
            return 100;
        }
        if (c.businessKey() != null && c.businessKey().equalsIgnoreCase(q)) {
            return 90;
        }
        if (c.businessKey() != null && c.businessKey().toLowerCase(Locale.ROOT)
                .contains(q.toLowerCase(Locale.ROOT))) {
            return 70;
        }
        if (matchedFields.contains("title")) {
            return 50;
        }
        return 1;
    }

    private static boolean exactIdentifier(String q) {
        return q != null && (q.contains(":") || q.length() >= 8);
    }
}
