package org.casemgmt.rest.controller;

import org.casemgmt.domain.CaseInstance;
import org.casemgmt.domain.CasePriority;
import org.casemgmt.domain.CaseState;
import org.casemgmt.repo.CaseQuery;
import org.casemgmt.repo.CaseRepository;
import org.casemgmt.repo.IdempotencyRepository;
import org.casemgmt.repo.JsonCodec;
import org.casemgmt.rest.CallerResolver;
import org.casemgmt.rest.dto.Dtos.CancelRequest;
import org.casemgmt.rest.dto.Dtos.CaseResponse;
import org.casemgmt.rest.dto.Dtos.CloseRequest;
import org.casemgmt.rest.dto.Dtos.CreateCaseRequest;
import org.casemgmt.rest.dto.Dtos.PatchCaseRequest;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;

/**
 * Case lifecycle over HTTP: create, query, read, patch, close, cancel (spec §2.1).
 *
 * <p>Every mutating method here follows the same three steps, in this order:
 * <ol>
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

    private final CaseService cases;
    private final CaseRepository caseRepo;
    private final ActionPolicy policy;
    private final CallerResolver callers;
    private final IdempotencySupport idempotency;

    public CaseController(CaseService cases, CaseRepository caseRepo, ActionPolicy policy,
                          CallerResolver callers, IdempotencyRepository idempotencyRepo) {
        this.cases = cases;
        this.caseRepo = caseRepo;
        this.policy = policy;
        this.callers = callers;
        this.idempotency = new IdempotencySupport(idempotencyRepo);
    }

    /**
     * Creates a case. No {@code assertAllowed} here and none possible: there is no case yet to
     * hold roles on, so the gate is authentication plus whatever the deployment's case-definition
     * identity links say — the creator becomes the case's {@code owner} participant
     * ({@code CaseService.create}), which is what makes every later call on it authorizable.
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
        var result = idempotency.execute(idempotencyKey, "POST /cases", JsonCodec.toJson(request),
                () -> cases.create(request.caseDefinitionKey(), request.tenantId(),
                        request.businessKey(), request.title(),
                        request.priority() == null ? CasePriority.MEDIUM
                                : CasePriority.valueOf(request.priority()),
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

    @GetMapping
    public List<CaseResponse> query(@RequestParam(required = false) String tenantId,
                                    @RequestParam(required = false) CaseState state,
                                    @RequestParam(required = false) String assignee,
                                    @RequestParam(required = false) String caseDefinitionKey,
                                    @RequestParam(required = false) String businessKey,
                                    @RequestParam(defaultValue = "0") int offset,
                                    @RequestParam(defaultValue = "50") int limit,
                                    Authentication authentication) {
        Actor actor = callers.actor(authentication);
        return caseRepo.query(new CaseQuery(tenantId, state, assignee, caseDefinitionKey,
                        businessKey, offset, limit)).stream()
                .map(c -> response(c, actor))
                .toList();
    }

    @GetMapping("/{caseId}")
    public ResponseEntity<CaseResponse> get(@PathVariable String caseId, Authentication authentication) {
        Actor actor = callers.actor(authentication);
        CaseInstance c = cases.get(caseId);
        return ResponseEntity.ok().eTag(ETagSupport.format(c.version())).body(response(c, actor));
    }

    @PatchMapping("/{caseId}")
    public ResponseEntity<CaseResponse> patch(@PathVariable String caseId,
                                              @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                              @RequestBody PatchCaseRequest request,
                                              Authentication authentication) {
        Actor actor = callers.actor(authentication);
        long version = expectedVersion(ifMatch, caseId);
        policy.assertAllowed(cases.snapshot(caseId), callers.roles(caseId, actor), "update");

        Map<String, Object> patch = new LinkedHashMap<>();
        if (request.title() != null) patch.put("title", request.title());
        if (request.variables() != null) patch.put("variables", request.variables());

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
        long version = expectedVersion(ifMatch, caseId);
        policy.assertAllowed(cases.snapshot(caseId), callers.roles(caseId, actor), "close");

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
        long version = expectedVersion(ifMatch, caseId);
        policy.assertAllowed(cases.snapshot(caseId), callers.roles(caseId, actor), "cancel");

        CaseInstance cancelled = cases.cancel(caseId, version,
                request == null ? null : request.reason(), actor);
        return ResponseEntity.ok().eTag(ETagSupport.format(cancelled.version()))
                .body(response(cancelled, actor));
    }

    /**
     * Resolves {@code If-Match} for this case, including the {@code *} wildcard — see
     * {@link ETagSupport#expectedVersion}. The read this performs for {@code *} is
     * unavoidably a check-then-act: a writer committing between it and the service call turns
     * the request into an ordinary 412 {@code version-conflict}. That is a benign, honest
     * outcome for a wildcard (the client asked for "whatever is current" and lost a race), not
     * a lost update — the optimistic-lock check downstream is what actually guarantees that.
     */
    private long expectedVersion(String ifMatch, String caseId) {
        return ETagSupport.expectedVersion(ifMatch, "case " + caseId,
                () -> caseRepo.findById(caseId)
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
        List<AvailableAction> actions = policy.listForCase(snapshot, roles);
        return CaseResponse.of(c, actions);
    }
}
