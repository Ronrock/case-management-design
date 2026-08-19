package org.casemgmt.event;

import org.casemgmt.domain.CaseIds;
import org.casemgmt.repo.AuditRepository;
import org.casemgmt.repo.EventRepository;
import org.casemgmt.repo.WebhookRepository;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;

/**
 * The transactional outbox (spec §6.1). Everything here runs in the caller's transaction: no
 * HTTP, no thread hand-off, no async dispatch. {@link #audit} writes immediately on the caller's
 * connection; {@link #publish} queues in memory for the current transaction and flushes the event
 * and webhook-delivery rows immediately before commit. The deferred flush makes the append lock
 * the final lock every mutation acquires, avoiding inversion with case and plan-item row locks.
 * This class is deliberately NOT itself {@code @Transactional} (see
 * {@code org.casemgmt.config.TransactionManagerConfig}'s self-invocation warning): the entity
 * mutation, the CM_EVENT row, the CM_AUDIT_LOG row and every CM_WEBHOOK_DELIVERY fan-out row
 * commit or roll back together only because they all run inside the SAME caller-owned
 * transaction, never because this class opens one of its own. A rolled-back mutation therefore
 * emits nothing: no event, no audit row, no delivery row.
 */
public class EventPublisher {

    private final EventRepository events;
    private final AuditRepository audit;
    private final WebhookRepository webhooks;
    private final String typePrefix;
    private final String engineId;

    public EventPublisher(EventRepository events, AuditRepository audit, WebhookRepository webhooks,
                          String typePrefix, String engineId) {
        if (typePrefix == null || typePrefix.isBlank()) {
            throw new IllegalArgumentException(
                    "casemgmt.events.type-prefix must be set — there is no safe default");
        }
        this.events = events;
        this.audit = audit;
        this.webhooks = webhooks;
        this.typePrefix = typePrefix;
        this.engineId = engineId;
    }

    /**
     * Stamps {@code type} with the configured prefix AND {@code source} with the configured
     * {@code engineId} — spec §6.2 makes {@code source = casemgmt.engine-id} a hard rule for the
     * CloudEvents envelope, and federation depends on every event being reliably attributable to
     * the engine that actually produced it. A caller-supplied {@link CaseEvent#source()} is
     * deliberately discarded rather than trusted: nothing else in this class would otherwise stop
     * a wrong or stale source from being persisted and fanned out. Outside a managed transaction,
     * primarily in repository-level tests, the event is appended immediately.
     */
    public void publish(CaseEvent event) {
        CaseEvent stamped = new CaseEvent(event.id(), engineId, typePrefix + "." + event.type(),
                event.subject(), event.tenantId(), event.time(), event.data());

        // Flush events immediately before commit, after the service has finished acquiring
        // business-row locks. This gives every event-producing transaction the same lock order:
        // domain rows first, the global commit-order append lock last.
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            pendingEvents().add(stamped);
            return;
        }
        append(stamped);
    }

    private void append(CaseEvent stamped) {
        long seq = events.append(stamped);

        for (WebhookRepository.Subscription sub : webhooks.active(stamped.tenantId())) {
            if (matches(sub.eventTypes(), stamped.type())) {
                webhooks.enqueueDelivery(CaseIds.newId(), sub.id(), seq);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private List<CaseEvent> pendingEvents() {
        Object existing = TransactionSynchronizationManager.getResource(this);
        if (existing != null) {
            return (List<CaseEvent>) existing;
        }

        List<CaseEvent> pending = new ArrayList<>();
        TransactionSynchronizationManager.bindResource(this, pending);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void beforeCommit(boolean readOnly) {
                pending.forEach(EventPublisher.this::append);
            }

            @Override
            public void afterCompletion(int status) {
                TransactionSynchronizationManager.unbindResourceIfPossible(EventPublisher.this);
            }
        });
        return pending;
    }

    public void audit(String caseId, String tenantId, String actor, String action,
                      String resourceType, String resourceId, Object before, Object after) {
        audit.record(caseId, tenantId, actor, action, resourceType, resourceId, before, after);
    }

    public String engineId() {
        return engineId;
    }

    private boolean matches(List<String> patterns, String type) {
        return patterns.stream().anyMatch(p -> p.equals("*") || p.equals(type)
                || (p.endsWith(".*") && type.startsWith(p.substring(0, p.length() - 1))));
    }
}
