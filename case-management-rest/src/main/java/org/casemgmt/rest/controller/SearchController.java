package org.casemgmt.rest.controller;

import org.casemgmt.domain.CaseState;
import org.casemgmt.rest.CallerResolver;
import org.casemgmt.rest.dto.Dtos.SearchFacetsResponse;
import org.casemgmt.rest.dto.Dtos.SearchProviderStatusResponse;
import org.casemgmt.rest.dto.Dtos.SearchProvidersResponse;
import org.casemgmt.rest.dto.Dtos.SearchRequest;
import org.casemgmt.rest.dto.Dtos.SearchResponse;
import org.casemgmt.rest.dto.Dtos.SearchSuggestionResponse;
import org.casemgmt.rest.dto.Dtos.SearchSuggestionsResponse;
import org.casemgmt.rest.dto.Dtos.SearchWarningResponse;
import org.casemgmt.rest.error.InvalidRequestException;
import org.casemgmt.search.SearchOrchestrator;
import org.casemgmt.search.SearchQuery;
import org.casemgmt.search.SearchScope;
import org.casemgmt.search.SearchWarning;
import org.casemgmt.service.Actor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/case-api/v2/search")
public class SearchController {

    private final SearchOrchestrator search;
    private final CallerResolver callers;

    public SearchController(SearchOrchestrator search, CallerResolver callers) {
        this.search = search;
        this.callers = callers;
    }

    @GetMapping("/cases")
    public SearchResponse cases(@RequestParam(required = false) String q,
                                @RequestParam(required = false) String state,
                                @RequestParam(required = false) String status,
                                @RequestParam(required = false) String caseDefinitionKey,
                                @RequestParam(required = false) String businessKey,
                                @RequestParam(required = false) String assignee,
                                @RequestParam(defaultValue = "0") int page,
                                @RequestParam(defaultValue = "" + CaseController.DEFAULT_PAGE_SIZE) int pageSize,
                                Authentication authentication) {
        Actor actor = callers.actor(authentication);
        Map<String, Object> filters = filters(state, status, caseDefinitionKey, businessKey, assignee);
        SearchQuery query = new SearchQuery(callers.tenantId(actor), q, List.of(SearchScope.CASES),
                filters, List.of(), page, pageSize, true);
        return SearchResponse.of(search.search(query));
    }

    @PostMapping("/query")
    public SearchResponse query(@RequestBody(required = false) SearchRequest request,
                                Authentication authentication) {
        Actor actor = callers.actor(authentication);
        SearchRequest effective = request == null
                ? new SearchRequest(null, null, null, null, null, null, null)
                : request;
        Map<String, Object> filters = effective.filters() == null
                ? Map.of()
                : new LinkedHashMap<>(effective.filters());
        validateCaseStateFilter(filters);

        SearchQuery query = new SearchQuery(callers.tenantId(actor), effective.q(),
                scopes(effective.scopes()), filters,
                effective.facets(), valueOr(effective.page(), 0),
                valueOr(effective.pageSize(), CaseController.DEFAULT_PAGE_SIZE),
                effective.includeProviderStatus() == null || effective.includeProviderStatus());
        return SearchResponse.of(search.search(query));
    }

    @GetMapping("/providers")
    public SearchProvidersResponse providers(Authentication authentication) {
        callers.actor(authentication);
        return new SearchProvidersResponse(search.providerStatuses().stream()
                .map(SearchProviderStatusResponse::of)
                .toList());
    }

    @GetMapping("/suggestions")
    public SearchSuggestionsResponse suggestions(@RequestParam String q,
                                                 @RequestParam(defaultValue = "cases") String scope,
                                                 @RequestParam(defaultValue = "10") int limit,
                                                 Authentication authentication) {
        if (q == null || q.trim().length() < 2) {
            throw new InvalidRequestException("Search suggestions require q with at least 2 characters");
        }
        Actor actor = callers.actor(authentication);
        SearchScope searchScope = scope(scope);
        SearchQuery query = new SearchQuery(callers.tenantId(actor), q, List.of(searchScope),
                Map.of(), List.of(), 0, Math.clamp(limit, 1, 25), false);
        return new SearchSuggestionsResponse(search.search(query).items().stream()
                .map(item -> new SearchSuggestionResponse(item.id(), item.title(),
                        item.resultType().wireName(), searchScope.wireName()))
                .toList());
    }

    @GetMapping("/facets")
    public SearchFacetsResponse facets(@RequestParam(defaultValue = "cases") String scope,
                                       @RequestParam(required = false) String q,
                                       @RequestParam(required = false) String caseDefinitionKey,
                                       Authentication authentication) {
        Actor actor = callers.actor(authentication);
        SearchScope searchScope = scope(scope);
        Map<String, Object> filters = new LinkedHashMap<>();
        if (caseDefinitionKey != null && !caseDefinitionKey.isBlank()) {
            filters.put("caseDefinitionKey", caseDefinitionKey.trim());
        }
        SearchQuery query = new SearchQuery(callers.tenantId(actor), q, List.of(searchScope),
                filters, List.of(), 0, CaseController.DEFAULT_PAGE_SIZE, true);
        org.casemgmt.search.SearchResponse response = search.search(query);
        List<SearchWarningResponse> warnings = response.warnings().stream()
                .map(SearchWarningResponse::of)
                .toList();
        if (response.facets().isEmpty()) {
            warnings = List.of(SearchWarningResponse.of(new SearchWarning("facet-unavailable",
                    "No registered provider returned facets for this query", searchScope.wireName())));
        }
        return new SearchFacetsResponse(response.facets().stream()
                .map(org.casemgmt.rest.dto.Dtos.SearchFacetGroupResponse::of)
                .toList(), warnings);
    }

    private static Map<String, Object> filters(String state, String status, String caseDefinitionKey,
                                               String businessKey, String assignee) {
        Map<String, Object> filters = new LinkedHashMap<>();
        String stateFilter = state == null ? status : state;
        if (stateFilter != null && !stateFilter.isBlank()) {
            filters.put("state", caseState(stateFilter).name());
        }
        if (caseDefinitionKey != null && !caseDefinitionKey.isBlank()) {
            filters.put("caseDefinitionKey", caseDefinitionKey.trim());
        }
        if (businessKey != null && !businessKey.isBlank()) {
            filters.put("businessKey", businessKey.trim());
        }
        if (assignee != null && !assignee.isBlank()) {
            filters.put("assignee", assignee.trim());
        }
        return filters;
    }

    private static List<SearchScope> scopes(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of(SearchScope.CASES);
        }
        return raw.stream().map(SearchController::scope).toList();
    }

    private static SearchScope scope(String raw) {
        try {
            return SearchScope.fromWire(raw);
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException(e.getMessage());
        }
    }

    private static void validateCaseStateFilter(Map<String, Object> filters) {
        Object value = filters.containsKey("state") ? filters.get("state") : filters.get("status");
        if (value == null) {
            return;
        }
        if (value instanceof List<?> list) {
            List<String> states = list.stream().map(Object::toString)
                    .map(SearchController::caseStateName)
                    .toList();
            filters.put("state", states);
            filters.remove("status");
            return;
        }
        filters.put("state", caseStateName(value.toString()));
        filters.remove("status");
    }

    private static String caseStateName(String raw) {
        return caseState(raw).name();
    }

    private static CaseState caseState(String raw) {
        try {
            return CaseState.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            throw new InvalidRequestException("Invalid value '" + raw
                    + "' for state; legal values are CREATED, ACTIVE, SUSPENDED, CLOSED, CANCELLED");
        }
    }

    private static int valueOr(Integer value, int fallback) {
        return value == null ? fallback : value;
    }
}
