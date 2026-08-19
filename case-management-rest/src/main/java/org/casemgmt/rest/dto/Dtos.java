package org.casemgmt.rest.dto;

import org.casemgmt.domain.CaseInstance;
import org.casemgmt.domain.CaseTask;
import org.casemgmt.domain.PlanItem;
import org.casemgmt.rest.policy.AvailableAction;
import org.casemgmt.search.SearchFacetGroup;
import org.casemgmt.search.SearchFacetValue;
import org.casemgmt.search.SearchProviderStatus;
import org.casemgmt.search.SearchResultItem;
import org.casemgmt.search.SearchWarning;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Request and response shapes for the case API. Records only: these are serialised by Spring's
 * own HTTP message converters (Jackson 3, {@code tools.jackson.*} under Spring Boot 4) and this
 * file must therefore never touch a Jackson type of either generation directly — the
 * core-facing conversions it performs are plain accessor calls on domain records.
 *
 * <p>Nothing here knows a case type. Every case-shaped value ({@code caseDefinitionKey},
 * {@code priority}, {@code state}, form keys) is a string carried through from the deployed
 * definition, which is what lets a generic consumer drive the whole API with no constants.
 */
public final class Dtos {

    /**
     * The spec's {@code Page} envelope (fix round 1, review finding I6). A bare array has nowhere
     * to put totals or a next cursor and adding one later is a breaking change; the envelope keeps
     * pagination metadata stable across all list responses.
     */
    public record Page<T>(List<T> items, int page, int pageSize, long totalItems, int totalPages) {}

    public record CreateCaseRequest(String caseDefinitionKey, String tenantId, String businessKey,
                                    String title, String priority, Map<String, Object> variables) {}

    public record PatchCaseRequest(String title, Map<String, Object> variables) {}

    public record CloseRequest(String outcome) {}

    public record CancelRequest(String reason) {}

    public record TerminateRequest(String reason) {}

    public record CompleteTaskRequest(Map<String, Object> variables) {}

    public record CommentRequest(String text, String visibility) {}

    public record DocumentRequest(String name, String category, String mimeType, Long sizeBytes,
                                  String contentUrl) {}

    /**
     * {@code planItemId} is the spec's "optional plan item this process fulfils" (fix round 1,
     * review finding I6). It is not decoration: {@code LinkedProcessService.start} already takes
     * it, and Task 18 spent a whole fix round threading a {@code correlationId} through the
     * command outbox precisely so a plan-item-backed process could be reconciled — hardcoding
     * {@code null} here made that path unreachable over HTTP.
     */
    public record StartProcessRequest(String processDefinitionKey, String planItemId,
                                      Map<String, Object> variables) {}

    public record PauseSlaRequest(String reason) {}

    public record WebhookRequest(String url, List<String> eventTypes, String tenantId) {}

    public record SearchRequest(String q, List<String> scopes, Map<String, Object> filters,
                                List<String> facets, Integer page, Integer pageSize,
                                Boolean includeProviderStatus) {}

    public record SearchPageResponse(int page, int pageSize) {}

    public record SearchResponse(List<SearchResultItemResponse> items, SearchPageResponse page,
                                 List<SearchFacetGroupResponse> facets,
                                 List<SearchWarningResponse> warnings,
                                 List<SearchProviderStatusResponse> providerStatuses) {

        public static SearchResponse of(org.casemgmt.search.SearchResponse response) {
            return new SearchResponse(
                    response.items().stream().map(SearchResultItemResponse::of).toList(),
                    new SearchPageResponse(response.page().page(), response.page().pageSize()),
                    response.facets().stream().map(SearchFacetGroupResponse::of).toList(),
                    response.warnings().stream().map(SearchWarningResponse::of).toList(),
                    response.providerStatuses().stream().map(SearchProviderStatusResponse::of).toList());
        }
    }

    public record SearchResultItemResponse(String id, String resultType, String caseId,
                                           String title, String summary, String sourceProvider,
                                           double score, List<String> matchedFields,
                                           List<String> highlights, Map<String, Object> resource,
                                           String freshness) {

        public static SearchResultItemResponse of(SearchResultItem item) {
            return new SearchResultItemResponse(item.id(), item.resultType().wireName(),
                    item.caseId(), item.title(), item.summary(), item.sourceProvider(),
                    item.score(), item.matchedFields(), item.highlights(),
                    item.resource(), item.freshness());
        }
    }

    public record SearchFacetGroupResponse(String field, String label,
                                           List<SearchFacetValueResponse> values) {

        public static SearchFacetGroupResponse of(SearchFacetGroup group) {
            return new SearchFacetGroupResponse(group.field(), group.label(),
                    group.values().stream().map(SearchFacetValueResponse::of).toList());
        }
    }

    public record SearchFacetValueResponse(String value, String label, long count,
                                           boolean countSuppressed) {

        public static SearchFacetValueResponse of(SearchFacetValue value) {
            return new SearchFacetValueResponse(value.value(), value.label(), value.count(),
                    value.countSuppressed());
        }
    }

    public record SearchWarningResponse(String code, String message, String provider) {

        public static SearchWarningResponse of(SearchWarning warning) {
            return new SearchWarningResponse(warning.code(), warning.message(), warning.provider());
        }
    }

    public record SearchProviderStatusResponse(String id, String status, List<String> scopes,
                                               boolean supportsFacets, boolean supportsSuggestions,
                                               int maxProjectionLagSeconds,
                                               int currentProjectionLagSeconds,
                                               boolean partialResultsAllowed,
                                               List<SearchWarningResponse> warnings) {

        public static SearchProviderStatusResponse of(SearchProviderStatus status) {
            return new SearchProviderStatusResponse(status.id(), status.status(),
                    status.scopes().stream().map(scope -> scope.wireName()).toList(),
                    status.supportsFacets(), status.supportsSuggestions(),
                    status.maxProjectionLagSeconds(), status.currentProjectionLagSeconds(),
                    status.partialResultsAllowed(),
                    status.warnings().stream().map(SearchWarningResponse::of).toList());
        }
    }

    public record SearchProvidersResponse(List<SearchProviderStatusResponse> providers) {}

    public record SearchFacetsResponse(List<SearchFacetGroupResponse> facets,
                                       List<SearchWarningResponse> warnings) {}

    public record SearchSuggestionsResponse(List<SearchSuggestionResponse> items) {}

    public record SearchSuggestionResponse(String value, String label, String suggestionType,
                                           String scope) {}

    public record CaseResponse(String id, String engineId, String tenantId, String caseDefinitionKey,
                               int caseDefinitionVersion, String businessKey, String title,
                               String state, String priority, String assignee, String slaStatus,
                               String outcome, Map<String, Object> variables, long version,
                               OffsetDateTime createdAt, OffsetDateTime updatedAt,
                               OffsetDateTime closedAt,
                               List<AvailableAction> availableActions,
                               List<AvailableAction> collaborationActions) {

        public static CaseResponse of(CaseInstance c, List<AvailableAction> actions,
                                      List<AvailableAction> collaborationActions) {
            return new CaseResponse(c.id(), c.engineId(), c.tenantId(), c.caseDefKey(),
                    c.caseDefVersion(), c.businessKey(), c.title(), c.state().name(),
                    c.priority().name(), c.assignee(), c.slaStatus(), c.outcome(), c.variables(),
                    c.version(), c.createdAt(), c.updatedAt(), c.closedAt(), actions,
                    collaborationActions);
        }
    }

    public record PlanItemResponse(String id, String caseId, String type, String name, String state,
                                   String parentStageId, int repetitionNo, long version,
                                   List<AvailableAction> availableActions) {

        public static PlanItemResponse of(PlanItem i, List<AvailableAction> actions) {
            return new PlanItemResponse(i.id(), i.caseId(), i.type().name(), i.name(),
                    i.state().name(), i.parentStageId(), i.repetitionNo(), i.version(), actions);
        }
    }

    public record TaskResponse(String id, String caseId, String planItemId, String name, String state,
                               String assignee, List<String> candidateGroups, String formKey,
                               String engineSync, long version, List<AvailableAction> availableActions) {

        public static TaskResponse of(CaseTask t, List<AvailableAction> actions) {
            return new TaskResponse(t.id(), t.caseId(), t.planItemId(), t.name(), t.state().name(),
                    t.assignee(), t.candidateGroups(), t.formKey(), t.engineSync().name(),
                    t.version(), actions);
        }
    }

    private Dtos() {}
}
