package org.casemgmt.rest.controller;

import org.casemgmt.domain.CaseInstance;
import org.casemgmt.error.NotFoundException;
import org.casemgmt.rest.CallerResolver;
import org.casemgmt.rest.dto.Dtos.PauseSlaRequest;
import org.casemgmt.rest.filter.ETagSupport;
import org.casemgmt.rest.policy.ActionPolicy;
import org.casemgmt.rest.policy.AvailableAction;
import org.casemgmt.rules.CaseSnapshot;
import org.casemgmt.service.Actor;
import org.casemgmt.service.CaseService;
import org.casemgmt.sla.SlaRecord;
import org.casemgmt.sla.SlaService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;

/**
 * SLA clocks for one case: read them, pause them, resume them (spec §7).
 *
 * <p><b>Authorization (fix round 1, Critical 1).</b> Pause and resume had no rule and were gated
 * by authentication alone; both now assert against {@code ActionPolicy.listForSla}, whose state
 * table (RUNNING → pause, PAUSED → resume) deliberately mirrors {@code SlaService}'s own
 * {@code sla-not-running} conflict so the projection and the enforcement cannot drift. The
 * listing and both mutations resolve the case first, so another tenant's clocks are a 404
 * (Critical 2).
 *
 * <p>Timestamps are emitted as {@link java.time.OffsetDateTime} values, not
 * {@code String.valueOf(...)} — the brief's draft used the latter, which renders a null
 * {@code warnAt} (legitimate: a target may declare no warning threshold) as the literal string
 * {@code "null"}.
 */
@RestController
@RequestMapping("/case-api/v2/cases/{caseId}/slas")
public class SlaController {

    private final SlaService sla;
    private final CaseService cases;
    private final ActionPolicy policy;
    private final CallerResolver callers;

    public SlaController(SlaService sla, CaseService cases, ActionPolicy policy,
                         CallerResolver callers) {
        this.sla = sla;
        this.cases = cases;
        this.policy = policy;
        this.callers = callers;
    }

    @GetMapping
    public List<Map<String, Object>> list(@PathVariable String caseId, Authentication authentication) {
        Actor actor = callers.actor(authentication);
        CaseSnapshot snapshot = cases.snapshot(visible(caseId, actor));
        Set<String> roles = callers.roles(caseId, actor);
        return sla.forCase(caseId).stream()
                .map(r -> body(r, policy.listForSla(snapshot, r.id(), r.status(), roles)))
                .toList();
    }

    @PostMapping("/{slaId}/pause")
    public ResponseEntity<Map<String, Object>> pause(@PathVariable String caseId, @PathVariable String slaId,
                                                     @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                                     @RequestBody(required = false) PauseSlaRequest request,
                                                     Authentication authentication) {
        Actor actor = callers.actor(authentication);
        long version = authorize(caseId, slaId, actor, ifMatch, "pause");
        SlaRecord paused = sla.pause(caseId, slaId, version,
                request == null ? null : request.reason(), actor);
        return respond(caseId, paused, actor);
    }

    @PostMapping("/{slaId}/resume")
    public ResponseEntity<Map<String, Object>> resume(@PathVariable String caseId, @PathVariable String slaId,
                                                      @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                                      Authentication authentication) {
        Actor actor = callers.actor(authentication);
        long version = authorize(caseId, slaId, actor, ifMatch, "resume");
        SlaRecord resumed = sla.resume(caseId, slaId, version, actor);
        return respond(caseId, resumed, actor);
    }

    /**
     * Tenant gate, {@code If-Match} resolution and the policy check, in that order — the single
     * path both mutations take. Returns the version the service call must assert.
     */
    private long authorize(String caseId, String slaId, Actor actor, String ifMatch, String action) {
        CaseInstance c = visible(caseId, actor);
        SlaRecord record = sla.forCase(caseId).stream()
                .filter(r -> r.id().equals(slaId)).findFirst()
                .orElseThrow(() -> new NotFoundException("SlaRecord", slaId));

        long version = ETagSupport.expectedVersion(ifMatch, "SLA record " + slaId,
                () -> OptionalLong.of(record.version()));
        policy.assertAllowedOnSla(cases.snapshot(c), slaId, record.status(),
                callers.roles(caseId, actor), action);
        return version;
    }

    private ResponseEntity<Map<String, Object>> respond(String caseId, SlaRecord record, Actor actor) {
        List<AvailableAction> actions = policy.listForSla(cases.snapshot(caseId), record.id(),
                record.status(), callers.roles(caseId, actor));
        return ResponseEntity.ok().eTag(ETagSupport.format(record.version()))
                .body(body(record, actions));
    }

    private CaseInstance visible(String caseId, Actor actor) {
        CaseInstance c = cases.get(caseId);
        callers.requireVisible("Case", caseId, c.tenantId(), actor);
        return c;
    }

    private static Map<String, Object> body(SlaRecord r, List<AvailableAction> actions) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", r.id());
        body.put("targetId", r.targetId());
        body.put("status", r.status());
        body.put("startedAt", r.startedAt());
        body.put("dueAt", r.dueAt());
        body.put("warnAt", r.warnAt());
        body.put("pausedAt", r.pausedAt());
        body.put("pausedReason", r.pausedReason());
        body.put("pausedTotalSeconds", r.pausedTotalSeconds());
        body.put("version", r.version());
        body.put("availableActions", actions);
        return body;
    }
}
