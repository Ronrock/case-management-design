package org.casemgmt.rest.dto;

import org.casemgmt.domain.CaseInstance;
import org.casemgmt.domain.CaseTask;
import org.casemgmt.domain.PlanItem;
import org.casemgmt.rest.policy.AvailableAction;

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
     * to put a total or a next cursor and adding one later is a breaking change; event pagination
     * already carries a known cursor-gap flaw that will eventually need exactly that space.
     *
     * <p>{@code totalItems}/{@code totalPages} are declared by the spec but not emitted yet —
     * they need a COUNT query {@code CaseRepository} does not have. Adding them to this record
     * later is additive for every client.
     */
    public record Page<T>(List<T> items, int page, int pageSize) {}

    public record CreateCaseRequest(String caseDefinitionKey, String tenantId, String businessKey,
                                    String title, String priority, Map<String, Object> variables) {}

    public record PatchCaseRequest(String title, Map<String, Object> variables) {}

    public record CloseRequest(String outcome) {}

    public record CancelRequest(String reason) {}

    public record TerminateRequest(String reason) {}

    public record CompleteTaskRequest(Map<String, Object> variables) {}

    public record CommentRequest(String text, String visibility) {}

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

    public record CaseResponse(String id, String engineId, String tenantId, String caseDefinitionKey,
                               int caseDefinitionVersion, String businessKey, String title,
                               String state, String priority, String assignee, String slaStatus,
                               String outcome, Map<String, Object> variables, long version,
                               OffsetDateTime createdAt, OffsetDateTime closedAt,
                               List<AvailableAction> availableActions) {

        public static CaseResponse of(CaseInstance c, List<AvailableAction> actions) {
            return new CaseResponse(c.id(), c.engineId(), c.tenantId(), c.caseDefKey(),
                    c.caseDefVersion(), c.businessKey(), c.title(), c.state().name(),
                    c.priority().name(), c.assignee(), c.slaStatus(), c.outcome(), c.variables(),
                    c.version(), c.createdAt(), c.closedAt(), actions);
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
