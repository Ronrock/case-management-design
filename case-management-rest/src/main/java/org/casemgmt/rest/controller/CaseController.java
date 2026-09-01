package org.casemgmt.rest.controller;

import org.casemgmt.domain.CaseDefinition;
import org.casemgmt.domain.CaseInstance;
import org.casemgmt.domain.CasePriority;
import org.casemgmt.domain.CaseState;
import org.casemgmt.domain.PlanItem;
import org.casemgmt.permissions.PermissionActions;
import org.casemgmt.permissions.PermissionDecision;
import org.casemgmt.permissions.ResourceTypes;
import org.casemgmt.permissions.WorkerPermissionEvaluator;
import org.casemgmt.permissions.WorkerPermissionResource;
import org.casemgmt.repo.CaseDefinitionRepository;
import org.casemgmt.repo.CaseQuery;
import org.casemgmt.repo.CaseRepository;
import org.casemgmt.repo.IdempotencyRepository;
import org.casemgmt.repo.JsonCodec;
import org.casemgmt.repo.PlanItemRepository;
import org.casemgmt.rest.CallerResolver;
import org.casemgmt.rest.dto.Dtos.CancelRequest;
import org.casemgmt.rest.dto.Dtos.CaseResponse;
import org.casemgmt.rest.dto.Dtos.CloseRequest;
import org.casemgmt.rest.dto.Dtos.CreateCaseRequest;
import org.casemgmt.rest.dto.Dtos.Page;
import org.casemgmt.rest.error.InvalidRequestException;
import org.casemgmt.rest.filter.ETagSupport;
import org.casemgmt.rest.filter.IdempotencySupport;
import org.casemgmt.rest.policy.ActionPolicy;
import org.casemgmt.rest.policy.AvailableAction;
import org.casemgmt.rules.CaseSnapshot;
import org.casemgmt.service.Actor;
import org.casemgmt.service.CaseService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;

/**
 * Case lifecycle over HTTP: create, query, read, patch, close, cancel (spec §2.1).
 *
 * <p>Every mutating method here follows the same four steps, in this order:
 * <ol>
 *   <li>resolve the caller's tenant from the principal and refuse anything outside it — a case
 *       in another tenant is reported as 404, never as data (fix round 1, Critical 2);</li>
 *   <li>resolve {@code If-Match} — absent is 428, {@code *} is resolved against the case's
 *       current version and is 412 when there is no case to match (RFC 7232 §3.1);</li>
 *   <li>{@link ActionPolicy#assertAllowed} — the authorization gate. {@code CaseService}
 *       performs no identity checks whatsoever by design (carried finding C1), so this call
 *       is the <em>only</em> thing standing between a caller with no roles on the case and a
 *       successful mutation. There is no path to {@code cases.update/close/cancel} that skips
 *       it;</li>
 *   <li>the service call, whose returned instance — never a re-read — supplies both the body
 *       and the {@code ETag} (Task 4's rule, carried finding C5: a re-read is a second
 *       statement a concurrent writer can commit in front of, and the client would then take
 *       that stranger's version into its next {@code If-Match}).</li>
 * </ol>
 */
@RestController
@RequestMapping("/case-api/v2/cases")
public class CaseController {

    /**
     * {@code pageSize} ceiling, from {@code openapi-specs.md}'s own {@code pageSize} parameter.
     * Enforced rather than trusted (fix round 1, review finding I8): each row in a page costs a
     * participant lookup and a plan-item lookup to derive its {@code availableActions[]}, so an
     * unbounded, client-chosen page size is a cheap way to make the server do arbitrary work.
     */
    static final int MAX_PAGE_SIZE = 200;
    static final int DEFAULT_PAGE_SIZE = 25;

    private final CaseService cases;
    private final CaseRepository caseRepo;
    private final CaseDefinitionRepository definitionRepo;
    private final PlanItemRepository planItemRepo;
    private final ActionPolicy policy;
    private final CallerResolver callers;
    private final IdempotencySupport idempotency;
    private final WorkerPermissionEvaluator permissions;

    public CaseController(CaseService cases, CaseRepository caseRepo,
                          CaseDefinitionRepository definitionRepo, PlanItemRepository planItemRepo,
                          ActionPolicy policy, CallerResolver callers,
                          IdempotencyRepository idempotencyRepo,
                          WorkerPermissionEvaluator permissions) {
        this.cases = cases;
        this.caseRepo = caseRepo;
        this.definitionRepo = definitionRepo;
        this.planItemRepo = planItemRepo;
        this.policy = policy;
        this.callers = callers;
        this.idempotency = new IdempotencySupport(idempotencyRepo);
        this.permissions = permissions;
    }

    /**
     * Creates a case. No {@code assertAllowed} here and none possible: there is no case yet to
     * hold roles on, so the gate is the caller's tenant plus authentication — the creator becomes
     * the case's {@code owner} participant ({@code CaseService.create}), which is what makes
     * every later call on it authorizable.
     *
     * <p>The status comes from {@link IdempotencySupport.Result#status()}, not a hardcoded 201:
     * a replay must reproduce what the original call actually returned. {@code Location} and
     * {@code ETag} are reconstructed from the (replayed or fresh) value rather than replayed
     * from storage — Task 22 scoped header replay out on exactly that ground.
     */
    @PostMapping
    public ResponseEntity<CaseResponse> create(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody CreateCaseRequest request,
            Authentication authentication) {

        Actor actor = callers.actor(authentication);
        String tenantId = callers.requireTenant(actor, request.tenantId());
        CasePriority priority = InvalidRequestException.enumValue(
                CasePriority.class, "priority", request.priority(), CasePriority.MEDIUM);
        permissions.assertAllowed(actor, tenantId, PermissionActions.CASE_CREATE,
                ResourceTypes.CASE, "new:" + request.caseDefinitionKey(),
                createContext(request, priority));

        var result = idempotency.execute(idempotencyKey, idempotencyScope(actor),
                JsonCodec.toJson(request),
                () -> cases.create(request.caseDefinitionKey(), tenantId,
                        request.businessKey(), request.title(), priority,
                        request.variables(), actor),
                // Replay path: only the id was stored, so the case has to be read back. That is
                // not a C5 violation — there is no local write to build from on a replay, and
                // the version this returns is the case's genuine current one.
                body -> caseRepo.require(JsonCodec.toMap(body).get("id").toString()),
                created -> JsonCodec.toJson(Map.of("id", created.id())),
                HttpStatus.CREATED.value());

        CaseInstance created = result.value();
        return ResponseEntity.status(result.status())
                .location(URI.create("/case-api/v2/cases/" + created.id()))
                .eTag(ETagSupport.format(created.version()))
                .header("Idempotency-Replayed", String.valueOf(result.replayed()))
                .body(response(created, actor));
    }

    /**
     * The case listing, as {@code openapi-specs.md} declares it: a {@code Page} envelope,
     * repeatable {@code state}, column-backed filters, and whitelisted sorting. The free-text
     * predicate covers title, business key, and comments; heavier ranking belongs to a projection
     * or search module rather than this transactional list endpoint.
     */
    @GetMapping
    public Page<CaseResponse> query(@RequestParam(required = false) String tenantId,
                                    @RequestParam(required = false) List<String> state,
                                    @RequestParam(required = false) String assignee,
                                    @RequestParam(required = false) String caseDefinitionKey,
                                    @RequestParam(required = false) String businessKey,
                                    @RequestParam(required = false) String participantUser,
                                    @RequestParam(required = false) String queueId,
                                    @RequestParam(required = false) String slaStatus,
                                    @RequestParam(required = false) String priority,
                                    @RequestParam(required = false) String freeText,
                                    @RequestParam(required = false) String createdAfter,
                                    @RequestParam(required = false) String createdBefore,
                                    @RequestParam(required = false) String sort,
                                    @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int pageSize,
                                    Authentication authentication) {
        Actor actor = callers.actor(authentication);
        String tenant = callers.requireTenant(actor, tenantId);

        List<CaseState> states = state == null ? List.of() : state.stream()
                .flatMap(s -> List.of(s.split(",")).stream())
                .map(s -> InvalidRequestException.enumValue(CaseState.class, "state", s, null))
                .filter(Objects::nonNull)
                .toList();
        CasePriority parsedPriority = InvalidRequestException.enumValue(
                CasePriority.class, "priority", priority, null);
        int effectivePage = Math.max(page, 0);
        int effectiveSize = Math.clamp(pageSize, 1, MAX_PAGE_SIZE);

        CaseQuery query = new CaseQuery(tenant, states, blankToNull(assignee),
                blankToNull(caseDefinitionKey), blankToNull(businessKey), blankToNull(participantUser),
                blankToNull(queueId), parseSlaStatus(slaStatus), parsedPriority, blankToNull(freeText),
                parseDateTime("createdAfter", createdAfter), parseDateTime("createdBefore", createdBefore),
                parseSort(sort), 0, effectiveSize);

        AuthorizedPage authorized = authorizedPage(query, actor, tenant, effectivePage, effectiveSize);
        long totalItems = authorized.totalItems();
        int totalPages = totalItems == 0 ? 0
                : (int) Math.min(Integer.MAX_VALUE,
                        (totalItems + effectiveSize - 1L) / effectiveSize);

        return new Page<>(withActions(authorized.items(), actor), effectivePage, effectiveSize,
                totalItems, totalPages);
    }

    @GetMapping("/{caseId}")
    public ResponseEntity<CaseResponse> get(@PathVariable String caseId, Authentication authentication) {
        Actor actor = callers.actor(authentication);
        CaseInstance c = visibleCase(caseId, actor);
        assertCasePermission(actor, c, PermissionActions.CASE_READ);
        return ResponseEntity.ok().eTag(ETagSupport.format(c.version())).body(response(c, actor));
    }

    /**
     * {@code consumes} is {@code application/merge-patch+json} per the spec. The raw map is
     * intentional: this endpoint must distinguish an absent field from a field explicitly present
     * with {@code null}, which a DTO record cannot do.
     */
    @PatchMapping(path = "/{caseId}", consumes = "application/merge-patch+json")
    public ResponseEntity<CaseResponse> patch(@PathVariable String caseId,
                                              @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                              @RequestBody(required = false) Map<String, Object> request,
                                              Authentication authentication) {
        Actor actor = callers.actor(authentication);
        long version = expectedVersion(ifMatch, caseId, actor);
        CaseInstance c = visibleCase(caseId, actor);
        assertCasePermission(actor, c, PermissionActions.CASE_UPDATE);
        policy.assertAllowed(cases.snapshot(c), callers.roles(caseId, actor), "update");

        Map<String, Object> patch = validatePatch(request == null ? Map.of() : request);

        CaseInstance updated = cases.update(caseId, version, patch, actor);
        return ResponseEntity.ok().eTag(ETagSupport.format(updated.version()))
                .body(response(updated, actor));
    }

    @PostMapping("/{caseId}/close")
    public ResponseEntity<CaseResponse> close(@PathVariable String caseId,
                                              @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                              @RequestBody(required = false) CloseRequest request,
                                              Authentication authentication) {
        Actor actor = callers.actor(authentication);
        long version = expectedVersion(ifMatch, caseId, actor);
        CaseInstance c = visibleCase(caseId, actor);
        assertCasePermission(actor, c, PermissionActions.CASE_CLOSE);
        policy.assertAllowed(cases.snapshot(c), callers.roles(caseId, actor), "close");

        CaseInstance closed = cases.close(caseId, version,
                request == null ? null : request.outcome(), actor);
        return ResponseEntity.ok().eTag(ETagSupport.format(closed.version()))
                .body(response(closed, actor));
    }

    @PostMapping("/{caseId}/cancel")
    public ResponseEntity<CaseResponse> cancel(@PathVariable String caseId,
                                               @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                               @RequestBody(required = false) CancelRequest request,
                                               Authentication authentication) {
        Actor actor = callers.actor(authentication);
        long version = expectedVersion(ifMatch, caseId, actor);
        CaseInstance c = visibleCase(caseId, actor);
        assertCasePermission(actor, c, PermissionActions.CASE_CANCEL);
        policy.assertAllowed(cases.snapshot(c), callers.roles(caseId, actor), "cancel");

        CaseInstance cancelled = cases.cancel(caseId, version,
                request == null ? null : request.reason(), actor);
        return ResponseEntity.ok().eTag(ETagSupport.format(cancelled.version()))
                .body(response(cancelled, actor));
    }

    /**
     * Scoping the idempotency key to the caller (fix round 1, review finding I4).
     *
     * <p>{@code CM_IDEMPOTENCY_KEY} is keyed on {@code (KEY_, SCOPE_)}, so a constant scope makes
     * the key namespace global: two users who both send {@code Idempotency-Key: retry-1} collide
     * across tenants. With identical bodies the second caller is handed the first caller's case
     * id and full replayed body — a cross-tenant disclosure; with different bodies they get a 409
     * for a key they never used. The user id makes the namespace per-caller, which is the level a
     * client actually reasons about when it picks a key.
     */
    private String idempotencyScope(Actor actor) {
        return "POST /cases:" + actor.userId();
    }

    private static Map<String, Object> validatePatch(Map<String, Object> request) {
        Map<String, Object> patch = new LinkedHashMap<>();
        for (String key : request.keySet()) {
            if (!Set.of("title", "variables").contains(key)) {
                throw new InvalidRequestException("Unsupported case patch field '" + key
                        + "'; supported fields are title and variables");
            }
        }
        if (request.containsKey("title")) {
            Object title = request.get("title");
            if (title != null && !(title instanceof String)) {
                throw new InvalidRequestException("Field 'title' must be a string or null");
            }
            patch.put("title", title);
        }
        if (request.containsKey("variables")) {
            Object variables = request.get("variables");
            if (variables != null && !(variables instanceof Map<?, ?>)) {
                throw new InvalidRequestException("Field 'variables' must be an object or null");
            }
            patch.put("variables", variables);
        }
        return patch;
    }

    private static OffsetDateTime parseDateTime(String field, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value);
        } catch (DateTimeParseException e) {
            throw new InvalidRequestException("Invalid date-time value '" + value + "' for " + field);
        }
    }

    private static String parseSlaStatus(String value) {
        String status = blankToNull(value);
        if (status == null) {
            return null;
        }
        if (!Set.of("NONE", "ON_TRACK", "WARNING", "BREACHED").contains(status)) {
            throw new InvalidRequestException("Invalid value '" + value
                    + "' for slaStatus; legal values are NONE, ON_TRACK, WARNING, BREACHED");
        }
        return status;
    }

    private static List<CaseQuery.SortTerm> parseSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return List.of();
        }
        Set<String> allowed = Set.of("createdAt", "updatedAt", "closedAt", "priority",
                "state", "title", "businessKey");
        List<CaseQuery.SortTerm> terms = new ArrayList<>();
        for (String raw : sort.split(",")) {
            String token = raw.trim();
            if (token.isEmpty()) {
                continue;
            }
            boolean descending = token.startsWith("-");
            String field = descending ? token.substring(1) : token;
            if (!allowed.contains(field)) {
                throw new InvalidRequestException("Invalid case sort field '" + field
                        + "'; legal values are " + allowed);
            }
            terms.add(new CaseQuery.SortTerm(field, descending));
        }
        return terms;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * Loads a case, or reports it as absent if it belongs to another tenant. Every single-case
     * endpoint goes through this — there is no route to a {@code CaseInstance} in this class that
     * skips the tenant check.
     */
    private CaseInstance visibleCase(String caseId, Actor actor) {
        CaseInstance c = cases.get(caseId);
        callers.requireVisible("Case", caseId, c.tenantId(), actor);
        return c;
    }

    /**
     * Resolves {@code If-Match} for this case, including the {@code *} wildcard — see
     * {@link ETagSupport#expectedVersion}. The read this performs for {@code *} is
     * unavoidably a check-then-act: a writer committing between it and the service call turns
     * the request into an ordinary 412 {@code version-conflict}. That is a benign, honest
     * outcome for a wildcard (the client asked for "whatever is current" and lost a race), not
     * a lost update — the optimistic-lock check downstream is what actually guarantees that.
     *
     * <p>The wildcard lookup is tenant-filtered too, so {@code If-Match: *} cannot be used to
     * probe whether an id exists in another tenant: to this caller it does not.
     */
    private long expectedVersion(String ifMatch, String caseId, Actor actor) {
        return ETagSupport.expectedVersion(ifMatch, "case " + caseId,
                () -> caseRepo.findById(caseId)
                        .filter(c -> c.tenantId() != null
                                && c.tenantId().equals(callers.tenantId(actor)))
                        .map(c -> OptionalLong.of(c.version()))
                        .orElseGet(OptionalLong::empty));
    }

    /**
     * Builds the response body around the instance the caller already holds. The snapshot is
     * derived from that same instance ({@link CaseService#snapshot(CaseInstance)}), never
     * re-read, so {@code availableActions[]} always describes the exact version the body and
     * the {@code ETag} report.
     */
    private CaseResponse response(CaseInstance c, Actor actor) {
        Set<String> roles = callers.roles(c.id(), actor);
        CaseSnapshot snapshot = cases.snapshot(c);
        List<AvailableAction> actions = filterCaseActions(c, actor,
                policy.listForCase(snapshot, roles));
        return CaseResponse.of(c, actions, policy.listForCollaboration(snapshot, roles),
                caseReadDecision(actor, c));
    }

    /**
     * The listing's {@code availableActions[]}, in a fixed number of queries rather than three
     * per row (fix round 1, review finding I8). Roles and plan items are fetched once for the
     * whole page and case definitions are loaded once per distinct definition — a page of a
     * single case type therefore costs three queries in total, where the per-row path cost
     * roughly 150 for a default page.
     */
    private List<CaseResponse> withActions(List<CaseInstance> rows, Actor actor) {
        List<String> ids = rows.stream().map(CaseInstance::id).toList();
        Map<String, Set<String>> rolesByCase = callers.roles(ids, actor);
        Map<String, List<PlanItem>> itemsByCase = planItemRepo.findByCases(ids);
        Map<String, CaseDefinition> definitions = new HashMap<>();
        Map<String, PermissionDecision> fieldDecisions = rows.isEmpty() ? Map.of()
                : permissions.evaluate(actor, rows.getFirst().tenantId(), PermissionActions.CASE_READ,
                        ResourceTypes.CASE, rows.stream()
                                .map(c -> new WorkerPermissionResource(c.id(), caseContext(c)))
                                .toList());

        return rows.stream().map(c -> {
            CaseDefinition definition =
                    definitions.computeIfAbsent(c.caseDefId(), definitionRepo::require);
            CaseSnapshot snapshot = new CaseSnapshot(c, definition,
                    itemsByCase.getOrDefault(c.id(), List.of()));
            Set<String> roles = rolesByCase.get(c.id());
            return CaseResponse.of(c, filterCaseActions(c, actor,
                    policy.listForCase(snapshot, roles)),
                    policy.listForCollaboration(snapshot, roles),
                    fieldDecisions.getOrDefault(c.id(), PermissionDecision.deny(c.id())));
        }).toList();
    }

    private List<CaseInstance> readableCases(List<CaseInstance> rows, Actor actor, String tenant) {
        if (rows.isEmpty()) {
            return rows;
        }
        Map<String, PermissionDecision> decisions = permissions.evaluate(actor, tenant,
                PermissionActions.CASE_READ, ResourceTypes.CASE,
                rows.stream()
                        .map(c -> new WorkerPermissionResource(c.id(), caseContext(c)))
                        .toList());
        return rows.stream()
                .filter(c -> decisions.getOrDefault(c.id(),
                        PermissionDecision.deny(c.id())).allowed())
                .toList();
    }

    /**
     * Worker Permissions is resource based, so authorization must happen before pagination and
     * totals are exposed. Scan deterministic repository windows, evaluate each window in one
     * permission request, and only then select the requested authorized page.
     */
    private AuthorizedPage authorizedPage(CaseQuery query, Actor actor, String tenant,
                                          int page, int pageSize) {
        final int scanSize = MAX_PAGE_SIZE;
        long rawTotal = caseRepo.count(query);
        long requestedStart = (long) page * pageSize;
        long requestedEnd = requestedStart + pageSize;
        long authorizedCount = 0;
        int rawOffset = 0;
        List<CaseInstance> pageItems = new ArrayList<>(pageSize);

        while (rawOffset < rawTotal) {
            List<CaseInstance> batch = caseRepo.query(query.withWindow(rawOffset, scanSize));
            if (batch.isEmpty()) {
                break;
            }
            for (CaseInstance readable : readableCases(batch, actor, tenant)) {
                if (authorizedCount >= requestedStart && authorizedCount < requestedEnd) {
                    pageItems.add(readable);
                }
                authorizedCount++;
            }
            rawOffset = Math.addExact(rawOffset, batch.size());
        }
        return new AuthorizedPage(List.copyOf(pageItems), authorizedCount);
    }

    private record AuthorizedPage(List<CaseInstance> items, long totalItems) {}

    private void assertCasePermission(Actor actor, CaseInstance c, String action) {
        permissions.assertAllowed(actor, c.tenantId(), action, ResourceTypes.CASE, c.id(),
                caseContext(c));
    }

    private PermissionDecision caseReadDecision(Actor actor, CaseInstance c) {
        return permissions.evaluate(actor, c.tenantId(), PermissionActions.CASE_READ,
                        ResourceTypes.CASE,
                        List.of(new WorkerPermissionResource(c.id(), caseContext(c))))
                .getOrDefault(c.id(), PermissionDecision.deny(c.id()));
    }

    private List<AvailableAction> filterCaseActions(CaseInstance c, Actor actor,
                                                    List<AvailableAction> actions) {
        return actions.stream()
                .filter(action -> {
                    String permission = casePermissionAction(action.action());
                    return permission != null && permissions.allowedOrFalse(actor, c.tenantId(),
                            permission, ResourceTypes.CASE, c.id(), caseContext(c));
                })
                .toList();
    }

    private static String casePermissionAction(String action) {
        return switch (action) {
            case "update" -> PermissionActions.CASE_UPDATE;
            case "close" -> PermissionActions.CASE_CLOSE;
            case "cancel" -> PermissionActions.CASE_CANCEL;
            default -> null;
        };
    }

    private static Map<String, Object> createContext(CreateCaseRequest request,
                                                     CasePriority priority) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("caseDefinitionKey", request.caseDefinitionKey());
        context.put("priority", priority.name());
        if (request.businessKey() != null) {
            context.put("businessKey", request.businessKey());
        }
        if (request.title() != null) {
            context.put("title", request.title());
        }
        return context;
    }

    private static Map<String, Object> caseContext(CaseInstance c) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("caseDefinitionKey", c.caseDefKey());
        context.put("state", c.state().name());
        context.put("priority", c.priority().name());
        if (c.businessKey() != null) {
            context.put("businessKey", c.businessKey());
        }
        if (c.assignee() != null) {
            context.put("assignee", c.assignee());
        }
        return context;
    }
}
