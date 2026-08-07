package org.casemgmt.rest.controller;

import org.casemgmt.repo.EventRepository;
import org.casemgmt.repo.WebhookRepository;
import org.casemgmt.rest.CallerResolver;
import org.casemgmt.rest.dto.Dtos.WebhookRequest;
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

    private final EventRepository events;
    private final WebhookService webhooks;
    private final CallerResolver callers;

    public EventController(EventRepository events, WebhookService webhooks, CallerResolver callers) {
        this.events = events;
        this.webhooks = webhooks;
        this.callers = callers;
    }

    @GetMapping("/events")
    public List<Map<String, Object>> allEvents(@RequestParam(defaultValue = "0") long after,
                                               @RequestParam(defaultValue = "100") int limit) {
        return events.after(after, limit).stream()
                .map(e -> withCursor(e.event().toCloudEvent(), e.seq()))
                .toList();
    }

    @GetMapping("/cases/{caseId}/events")
    public List<Map<String, Object>> caseEvents(@PathVariable String caseId,
                                                @RequestParam(defaultValue = "0") long after,
                                                @RequestParam(defaultValue = "100") int limit) {
        return events.forCase(caseId, after, limit).stream()
                .map(e -> withCursor(e.event().toCloudEvent(), e.seq()))
                .toList();
    }

    @GetMapping("/webhooks")
    public List<Map<String, Object>> listWebhooks() {
        return webhooks.list().stream().map(EventController::subscriptionBody).toList();
    }

    @PostMapping("/webhooks")
    public ResponseEntity<Map<String, Object>> subscribe(@RequestBody WebhookRequest request,
                                                         Authentication authentication) {
        WebhookService.CreatedSubscription created = webhooks.subscribe(request.tenantId(),
                request.url(), request.eventTypes(), callers.actor(authentication));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", created.id());
        body.put("url", created.url());
        body.put("eventTypes", created.eventTypes());
        // The plaintext secret is returned once and never again — only its HMAC key is stored.
        body.put("secret", created.secret());
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
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
