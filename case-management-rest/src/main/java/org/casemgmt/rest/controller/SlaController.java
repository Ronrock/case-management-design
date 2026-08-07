package org.casemgmt.rest.controller;

import org.casemgmt.rest.CallerResolver;
import org.casemgmt.rest.dto.Dtos.PauseSlaRequest;
import org.casemgmt.rest.filter.ETagSupport;
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

/**
 * SLA clocks for one case: read them, pause them, resume them (spec §7).
 *
 * <p>Timestamps are emitted as {@link java.time.OffsetDateTime} values, not
 * {@code String.valueOf(...)} — the brief's draft used the latter, which renders a null
 * {@code warnAt} (legitimate: a target may declare no warning threshold) as the literal string
 * {@code "null"}. Same defect shape as {@code CollaborationController}'s milestone timestamps;
 * same fix.
 *
 * <p>Like {@code CollaborationController}, these mutations are authenticated but have no
 * {@code ActionPolicy} rule to assert — Task 23's table covers the case, plan-item and task
 * surfaces only. Recorded as a known gap; see that class for why a borrowed action name would
 * be worse than an honest one.
 */
@RestController
@RequestMapping("/case-api/v2/cases/{caseId}/slas")
public class SlaController {

    private final SlaService sla;
    private final CallerResolver callers;

    public SlaController(SlaService sla, CallerResolver callers) {
        this.sla = sla;
        this.callers = callers;
    }

    @GetMapping
    public List<Map<String, Object>> list(@PathVariable String caseId) {
        return sla.forCase(caseId).stream().map(SlaController::body).toList();
    }

    @PostMapping("/{slaId}/pause")
    public ResponseEntity<Map<String, Object>> pause(@PathVariable String caseId, @PathVariable String slaId,
                                                     @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                                     @RequestBody(required = false) PauseSlaRequest request,
                                                     Authentication authentication) {
        SlaRecord paused = sla.pause(caseId, slaId, expectedVersion(ifMatch, caseId, slaId),
                request == null ? null : request.reason(), callers.actor(authentication));
        return ResponseEntity.ok().eTag(ETagSupport.format(paused.version())).body(body(paused));
    }

    @PostMapping("/{slaId}/resume")
    public ResponseEntity<Map<String, Object>> resume(@PathVariable String caseId, @PathVariable String slaId,
                                                      @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                                      Authentication authentication) {
        SlaRecord resumed = sla.resume(caseId, slaId, expectedVersion(ifMatch, caseId, slaId),
                callers.actor(authentication));
        return ResponseEntity.ok().eTag(ETagSupport.format(resumed.version())).body(body(resumed));
    }

    private long expectedVersion(String ifMatch, String caseId, String slaId) {
        return ETagSupport.expectedVersion(ifMatch, "SLA record " + slaId,
                () -> sla.forCase(caseId).stream()
                        .filter(r -> r.id().equals(slaId))
                        .mapToLong(SlaRecord::version)
                        .findFirst());
    }

    private static Map<String, Object> body(SlaRecord r) {
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
        return body;
    }
}
