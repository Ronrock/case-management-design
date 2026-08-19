package org.casemgmt.search;

import org.casemgmt.domain.CaseInstance;
import org.casemgmt.permissions.PermissionActions;
import org.casemgmt.permissions.PermissionDecision;
import org.casemgmt.permissions.ResourceTypes;
import org.casemgmt.permissions.WorkerPermissionRequest;
import org.casemgmt.permissions.WorkerPermissionResource;
import org.casemgmt.permissions.WorkerPermissionsClient;
import org.casemgmt.repo.CaseRepository;
import org.casemgmt.repo.CaseSearchQuery;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class CaseProjectionSearchProvider implements SearchProvider {

    public static final String PROVIDER_ID = "case-projection";

    private final CaseRepository cases;
    private final WorkerPermissionsClient permissions;

    public CaseProjectionSearchProvider(CaseRepository cases) {
        this(cases, WorkerPermissionsClient.allowAll());
    }

    public CaseProjectionSearchProvider(CaseRepository cases, WorkerPermissionsClient permissions) {
        this.cases = cases;
        this.permissions = permissions;
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
        if (query.workerId() == null) {
            SearchWarning warning = authUnavailable("Search request has no worker identity");
            return new SearchProviderResult(List.of(), List.of(), List.of(warning),
                    degradedStatus(warning));
        }
        CaseSearchQuery caseQuery = new CaseSearchQuery(query.tenantId(), query.q(),
                CaseStateSearchFilter.states(query.filters()), stringFilter(query, "assignee"),
                stringFilter(query, "caseDefinitionKey"), stringFilter(query, "businessKey"),
                query.offset(), query.pageSize());
        List<CaseInstance> candidates = cases.search(caseQuery);
        if (candidates.isEmpty()) {
            return new SearchProviderResult(List.of(), List.of(), List.of(), status());
        }

        Map<String, PermissionDecision> decisions;
        try {
            decisions = permissions.evaluate(new WorkerPermissionRequest(query.tenantId(),
                    query.workerId(), query.groups(), PermissionActions.CASE_READ,
                    ResourceTypes.CASE, candidates.stream()
                            .map(c -> new WorkerPermissionResource(c.id(), permissionContext(c)))
                            .toList()));
        } catch (RuntimeException e) {
            SearchWarning warning = authUnavailable("Case authorization is unavailable");
            return new SearchProviderResult(List.of(), List.of(), List.of(warning),
                    degradedStatus(warning));
        }

        List<SearchResultItem> items = candidates.stream()
                .map(c -> Map.entry(c, decisions.getOrDefault(c.id(),
                        PermissionDecision.deny(c.id()))))
                .filter(entry -> entry.getValue().allowed())
                .filter(entry -> canDiscloseMatch(entry.getKey(), query.q(), entry.getValue()))
                .map(entry -> toResult(entry.getKey(), query.q(), entry.getValue()))
                .toList();
        return new SearchProviderResult(items, List.of(), List.of(), status());
    }

    private static String stringFilter(SearchQuery query, String name) {
        Object value = query.filters().get(name);
        return value == null || value.toString().isBlank() ? null : value.toString().trim();
    }

    private static SearchResultItem toResult(CaseInstance c, String q, PermissionDecision decision) {
        List<String> matched = matchedFields(c, q);
        return new SearchResultItem(c.id(), SearchResultType.CASE, c.id(),
                fieldAllowed(decision, "title") ? c.title() : "Case " + c.id(),
                summary(c, decision), PROVIDER_ID, score(c, q, matched),
                matched.stream().filter(field -> fieldAllowed(decision, field)).toList(),
                highlights(c, q, decision), resource(c, decision), c.updatedAt(),
                "fresh");
    }

    private static String summary(CaseInstance c, PermissionDecision decision) {
        String businessKey = fieldAllowed(decision, "businessKey")
                ? c.businessKey() == null ? "no business key" : c.businessKey()
                : "masked business key";
        return c.caseDefKey() + " / " + businessKey + " / " + c.state().name();
    }

    private static Map<String, Object> resource(CaseInstance c, PermissionDecision decision) {
        Map<String, Object> resource = new java.util.LinkedHashMap<>();
        resource.put("engineId", c.engineId());
        resource.put("tenantId", c.tenantId());
        resource.put("caseDefinitionKey", c.caseDefKey());
        resource.put("caseDefinitionVersion", c.caseDefVersion());
        putIfAllowed(resource, decision, "businessKey", c.businessKey() == null ? "" : c.businessKey());
        resource.put("state", c.state().name());
        putIfAllowed(resource, decision, "priority", c.priority().name());
        putIfAllowed(resource, decision, "assignee", c.assignee() == null ? "" : c.assignee());
        putIfAllowed(resource, decision, "slaStatus", c.slaStatus() == null ? "NONE" : c.slaStatus());
        resource.put("version", c.version());
        return resource;
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

    private static boolean canDiscloseMatch(CaseInstance c, String q, PermissionDecision decision) {
        return q == null || q.isBlank()
                || matchedFields(c, q).stream().anyMatch(field -> fieldAllowed(decision, field));
    }

    private static List<String> highlights(CaseInstance c, String q, PermissionDecision decision) {
        if (q == null || q.isBlank()) {
            return List.of();
        }
        String term = q.toLowerCase(Locale.ROOT);
        if (fieldAllowed(decision, "title")
                && c.title() != null && c.title().toLowerCase(Locale.ROOT).contains(term)) {
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

    private static Map<String, Object> permissionContext(CaseInstance c) {
        Map<String, Object> context = new java.util.LinkedHashMap<>();
        context.put("caseDefinitionKey", c.caseDefKey());
        context.put("state", c.state().name());
        if (c.businessKey() != null) {
            context.put("businessKey", c.businessKey());
        }
        if (c.assignee() != null) {
            context.put("assignee", c.assignee());
        }
        return context;
    }

    private static SearchWarning authUnavailable(String message) {
        return new SearchWarning("authorization-unavailable", message, PROVIDER_ID);
    }

    private static SearchProviderStatus degradedStatus(SearchWarning warning) {
        return new SearchProviderStatus(PROVIDER_ID, "degraded", List.of(SearchScope.CASES),
                false, true, 30, 0, false, List.of(warning));
    }

    private static boolean fieldAllowed(PermissionDecision decision, String field) {
        if ("caseId".equals(field)) {
            return true;
        }
        return decision.allowedFields().isEmpty()
                || decision.allowedFields().contains("*")
                || decision.allowedFields().contains(field);
    }

    private static void putIfAllowed(Map<String, Object> target, PermissionDecision decision,
                                     String field, Object value) {
        if (fieldAllowed(decision, field)) {
            target.put(field, value);
        }
    }
}
