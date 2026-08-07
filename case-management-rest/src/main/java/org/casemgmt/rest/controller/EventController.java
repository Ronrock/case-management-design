package org.casemgmt.rest.controller;

import org.casemgmt.domain.CaseInstance;
import org.casemgmt.error.NotFoundException;
import org.casemgmt.repo.CaseRepository;
import org.casemgmt.repo.EventRepository;
import org.casemgmt.repo.WebhookRepository;
import org.casemgmt.rest.CallerResolver;
import org.casemgmt.rest.dto.Dtos.WebhookRequest;
import org.casemgmt.rest.policy.ActionPolicy;
import org.casemgmt.service.Actor;
import org.casemgmt.service.WebhookService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The event log and webhook subscriptions — the federation surface (spec §6.2).
 *
 * <p><b>This class carried the sharpest finding of fix round 1 (Critical 2).</b>
 * {@code POST /webhooks} took {@code tenantId} verbatim from the request body, so any
 * authenticated user could register {@code https://attacker/} against another tenant and receive
 * that tenant's case events continuously — an exfiltration primitive, not merely a missing
 * filter. The tenant now comes from the principal and only from the principal; a body that names
 * a different one is refused rather than silently rewritten. The same round scoped
 * {@code GET /events} (which streamed every CloudEvent in the deployment) and
 * {@code GET /webhooks} (which listed every tenant's endpoints), and put an administration gate
 * in front of subscribing (Critical 1).
 *
 * <p>Events are returned as CloudEvents 1.0 envelopes exactly as {@code CaseEvent.toCloudEvent}
 * builds them, with one extension property added: {@code cursor}, the row's {@code SEQ_}, which
 * is what a consumer feeds back as {@code ?after=} to resume. Note the limitation
 * {@code EventRepository.after} documents and this endpoint inherits verbatim: {@code SEQ_} is
 * assigned at insert, not at commit, so a concurrent writer can leave a gap that a cursor walks
 * straight past. That is a known PoC finding, deliberately surfaced rather than papered over.
 */
@RestController
@RequestMapping("/case-api/v2")
public class EventController {

    /**
     * Page ceiling for both event feeds (fix round 1, review finding I8): {@code limit} was
     * entirely client-controlled, so one request could ask the database for every event ever
     * recorded. Matches {@code CaseController.MAX_PAGE_SIZE}.
     */
    static final int MAX_LIMIT = CaseController.MAX_PAGE_SIZE;

    private final EventRepository events;
    private final WebhookService webhooks;
    private final CaseRepository caseRepo;
    private final ActionPolicy policy;
    private final CallerResolver callers;

    public EventController(EventRepository events, WebhookService webhooks, CaseRepository caseRepo,
                           ActionPolicy policy, CallerResolver callers) {
        this.events = events;
        this.webhooks = webhooks;
        this.caseRepo = caseRepo;
        this.policy = policy;
        this.callers = callers;
    }

    @GetMapping("/events")
    public List<Map<String, Object>> allEvents(@RequestParam(defaultValue = "0") long after,
                                               @RequestParam(defaultValue = "100") int limit,
                                               Authentication authentication) {
        String tenantId = callers.tenantId(callers.actor(authentication));
        return events.afterForTenant(tenantId, after, clamp(limit)).stream()
                .map(e -> withCursor(e.event().toCloudEvent(), e.seq()))
                .toList();
    }

    @GetMapping("/cases/{caseId}/events")
    public List<Map<String, Object>> caseEvents(@PathVariable String caseId,
                                                @RequestParam(defaultValue = "0") long after,
                                                @RequestParam(defaultValue = "100") int limit,
                                                Authentication authentication) {
        Actor actor = callers.actor(authentication);
        CaseInstance c = caseRepo.require(caseId);
        callers.requireVisible("Case", caseId, c.tenantId(), actor);

        return events.forCase(caseId, after, clamp(limit)).stream()
                .map(e -> withCursor(e.event().toCloudEvent(), e.seq()))
                .toList();
    }

    @GetMapping("/webhooks")
    public List<Map<String, Object>> listWebhooks(Authentication authentication) {
        String tenantId = callers.tenantId(callers.actor(authentication));
        return webhooks.list(tenantId).stream().map(EventController::subscriptionBody).toList();
    }

    /**
     * Subscribing is an administration action ({@code ActionPolicy.listForAdministration}) and
     * always binds to the caller's own tenant. A {@code tenantId} in the body is accepted only
     * when it names that same tenant — anything else is refused outright rather than quietly
     * rewritten, so a client can never believe it subscribed to a stream it did not.
     */
    @PostMapping("/webhooks")
    public ResponseEntity<Map<String, Object>> subscribe(@RequestBody WebhookRequest request,
                                                         Authentication authentication) {
        Actor actor = callers.actor(authentication);
        policy.assertMayAdminister(callers.groups(actor), "subscribe-webhook");
        String tenantId = callers.requireTenant(actor, request.tenantId());

        WebhookService.CreatedSubscription created = webhooks.subscribe(tenantId,
                request.url(), request.eventTypes(), actor);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", created.id());
        body.put("tenantId", tenantId);
        body.put("url", created.url());
        body.put("eventTypes", created.eventTypes());
        // The plaintext secret is returned once and never again — only its HMAC key is stored.
        body.put("secret", created.secret());
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    /**
     * The dead-letter queue for one subscription (final whole-branch review, Important 6).
     *
     * <p>{@code WebhookService.deadLetters} existed and was reachable only from tests, which
     * made the DLQ half a mechanism: {@code WebhookDispatcher} could dead-letter a delivery and
     * nothing in the deployment could ever see that it had. That matters most in the one
     * scenario most likely to happen — a restart, after which the in-memory secret map is empty,
     * every pre-existing subscription's signing throws, and every pending delivery burns its
     * retries into a queue nobody could read. This does not fix that (the secret's durability is
     * the real fix, recorded in FINDINGS.md); it makes it visible.
     *
     * <p>Gated exactly like {@link #subscribe}: an administration action, plus the subscription
     * must be the caller's own tenant's. Tenancy is enforced by resolving the id through the
     * tenant-scoped {@code webhooks.list(tenantId)} rather than by a direct lookup, so another
     * tenant's subscription id is reported absent rather than forbidden — the same
     * "to this caller it does not exist" answer {@code CaseController} gives for a foreign case,
     * and it keeps the endpoint from becoming an id-probing oracle.
     */
    @GetMapping("/webhooks/{webhookId}/dead-letters")
    public List<Map<String, Object>> deadLetters(@PathVariable String webhookId,
                                                 Authentication authentication) {
        Actor actor = callers.actor(authentication);
        policy.assertMayAdminister(callers.groups(actor), "subscribe-webhook");
        String tenantId = callers.tenantId(actor);

        webhooks.list(tenantId).stream()
                .filter(s -> s.id().equals(webhookId))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("WebhookSubscription", webhookId));

        return webhooks.deadLetters(webhookId).stream()
                .map(EventController::deadLetterBody)
                .toList();
    }

    private static Map<String, Object> deadLetterBody(WebhookRepository.DeadLetter d) {
        // LinkedHashMap, not Map.of: lastStatusCode is genuinely null for a transport-level
        // failure (and for the signing failure a restart produces), and Map.of throws NPE on a
        // null value — the same trap already fixed for CM_TASK.OUTCOME_ and several other
        // response bodies in this codebase.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", d.id());
        body.put("webhookId", d.webhookId());
        body.put("eventSeq", d.eventSeq());
        body.put("attempts", d.attempts());
        body.put("lastStatusCode", d.lastStatusCode());
        body.put("lastError", d.lastError());
        return body;
    }

    private static int clamp(int limit) {
        return Math.clamp(limit, 1, MAX_LIMIT);
    }

    private static Map<String, Object> subscriptionBody(WebhookRepository.Subscription s) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", s.id());
        body.put("tenantId", s.tenantId());
        body.put("url", s.url());
        body.put("eventTypes", s.eventTypes());
        body.put("active", s.active());
        return body;
    }

    private static Map<String, Object> withCursor(Map<String, Object> cloudEvent, long seq) {
        Map<String, Object> copy = new LinkedHashMap<>(cloudEvent);
        copy.put("cursor", seq);
        return copy;
    }
}
