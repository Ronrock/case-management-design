package org.casemgmt.rest.controller;

import org.casemgmt.domain.PlanItem;
import org.casemgmt.repo.PlanItemRepository;
import org.casemgmt.rest.CallerResolver;
import org.casemgmt.rest.dto.Dtos.PlanItemResponse;
import org.casemgmt.rest.dto.Dtos.TerminateRequest;
import org.casemgmt.rest.filter.ETagSupport;
import org.casemgmt.rest.policy.ActionPolicy;
import org.casemgmt.rest.policy.AvailableAction;
import org.casemgmt.rules.CaseSnapshot;
import org.casemgmt.service.Actor;
import org.casemgmt.service.CaseService;
import org.casemgmt.service.PlanItemService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Function;

/**
 * The manual half of the plan-item state machine over HTTP (spec §3.2).
 *
 * <p>{@code PlanItemService} performs no identity checks — it validates the source state and
 * the version and nothing else (carried finding C1). {@link #act} is therefore the single
 * choke point through which all four actions pass, and it calls
 * {@link ActionPolicy#assertAllowedOnPlanItem} before every one of them. No action method
 * reaches {@code PlanItemService} by any other route.
 */
@RestController
@RequestMapping("/case-api/v2/cases/{caseId}/plan-items")
public class PlanItemController {

    private final PlanItemService planItems;
    private final PlanItemRepository repo;
    private final CaseService cases;
    private final ActionPolicy policy;
    private final CallerResolver callers;

    public PlanItemController(PlanItemService planItems, PlanItemRepository repo, CaseService cases,
                              ActionPolicy policy, CallerResolver callers) {
        this.planItems = planItems;
        this.repo = repo;
        this.cases = cases;
        this.policy = policy;
        this.callers = callers;
    }

    @GetMapping
    public List<PlanItemResponse> list(@PathVariable String caseId, Authentication authentication) {
        Actor actor = callers.actor(authentication);
        CaseSnapshot snapshot = cases.snapshot(caseId);
        Set<String> roles = callers.roles(caseId, actor);
        return snapshot.planItems().stream()
                .map(i -> PlanItemResponse.of(i, policy.listForPlanItem(snapshot, i, roles)))
                .toList();
    }

    @PostMapping("/{itemId}/enable")
    public ResponseEntity<PlanItemResponse> enable(@PathVariable String caseId, @PathVariable String itemId,
                                                   @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                                   Authentication authentication) {
        return act(caseId, itemId, ifMatch, "enable", authentication,
                version -> planItems.enable(caseId, itemId, version, callers.actor(authentication)));
    }

    @PostMapping("/{itemId}/start")
    public ResponseEntity<PlanItemResponse> start(@PathVariable String caseId, @PathVariable String itemId,
                                                  @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                                  Authentication authentication) {
        return act(caseId, itemId, ifMatch, "start", authentication,
                version -> planItems.start(caseId, itemId, version, callers.actor(authentication)));
    }

    @PostMapping("/{itemId}/complete")
    public ResponseEntity<PlanItemResponse> complete(@PathVariable String caseId, @PathVariable String itemId,
                                                     @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                                     Authentication authentication) {
        return act(caseId, itemId, ifMatch, "complete", authentication,
                version -> planItems.complete(caseId, itemId, version, callers.actor(authentication)));
    }

    @PostMapping("/{itemId}/terminate")
    public ResponseEntity<PlanItemResponse> terminate(@PathVariable String caseId, @PathVariable String itemId,
                                                      @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                                      @RequestBody(required = false) TerminateRequest request,
                                                      Authentication authentication) {
        return act(caseId, itemId, ifMatch, "terminate", authentication,
                version -> planItems.terminate(caseId, itemId, version,
                        request == null ? null : request.reason(), callers.actor(authentication)));
    }

    /**
     * If-Match, then authorize, then act. The item read for the policy check is a check-then-act
     * against the service's own re-read, which is fine: the service re-validates the source
     * state and the version inside its transaction, so the worst a racing writer can do is turn
     * this into a 409 or a 412 — never a mutation the policy would have refused, because the
     * policy's decision only ever widens with a state the caller could not have caused.
     */
    private ResponseEntity<PlanItemResponse> act(String caseId, String itemId, String ifMatch,
                                                 String action, Authentication authentication,
                                                 Function<Long, PlanItem> operation) {
        Actor actor = callers.actor(authentication);
        long version = ETagSupport.expectedVersion(ifMatch, "plan item " + itemId,
                () -> repo.findById(itemId)
                        .map(i -> OptionalLong.of(i.version()))
                        .orElseGet(OptionalLong::empty));

        PlanItem current = repo.require(itemId);
        policy.assertAllowedOnPlanItem(cases.snapshot(caseId), current,
                callers.roles(caseId, actor), action);

        PlanItem updated = operation.apply(version);
        List<AvailableAction> actions = policy.listForPlanItem(
                cases.snapshot(caseId), updated, callers.roles(caseId, actor));
        return ResponseEntity.ok().eTag(ETagSupport.format(updated.version()))
                .body(PlanItemResponse.of(updated, actions));
    }
}
