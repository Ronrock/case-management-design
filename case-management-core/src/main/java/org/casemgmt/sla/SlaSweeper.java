package org.casemgmt.sla;

import org.casemgmt.domain.CaseIds;
import org.casemgmt.domain.CaseInstance;
import org.casemgmt.event.CaseEvent;
import org.casemgmt.event.EventPublisher;
import org.casemgmt.event.EventTypes;
import org.casemgmt.repo.CaseRepository;
import org.casemgmt.repo.SlaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Polls {@code CM_SLA_RECORD} for RUNNING clocks past their warning or due threshold, emits
 * {@code sla.warning}/{@code sla.breached} and denormalises the result onto {@code CM_CASE.SLA_STATUS_}.
 * Paused clocks are excluded by {@link SlaRepository#dueRecords}'s {@code STATUS_ = 'RUNNING'}
 * predicate — that is the whole point of pause/resume.
 *
 * <p>One {@code sweep()} call is a single transaction: the claim SELECT and every record's
 * UPDATE/event/case-status write commit or roll back together. This is a plain polling job, not
 * a claim-then-do-out-of-band-I/O dispatcher like {@code WebhookDispatcher}/
 * {@code EngineCommandDispatcher} — it does no external call between reading a row and writing
 * it back, so there is no lock-release/hung-request window for a claim-by-token lease to guard.
 * A genuinely concurrent second sweeper racing the same record would still be caught safely:
 * {@link SlaRepository#update}'s optimistic version check makes the loser's write affect zero
 * rows. That loser's {@code sweep()} call then throws and rolls back its own whole batch — for a
 * scheduled single-instance job (the only caller this task wires up) that never happens, so
 * finer-grained per-record failure isolation was not built; a future concurrent scheduler would
 * need it.
 */
public class SlaSweeper {

    private final SlaRepository sla;
    private final CaseRepository cases;
    private final EventPublisher publisher;

    public SlaSweeper(SlaRepository sla, CaseRepository cases, EventPublisher publisher) {
        this.sla = sla;
        this.cases = cases;
        this.publisher = publisher;
    }

    /** @return how many due records this pass handled (warned or breached) */
    @Transactional
    public int sweep() {
        OffsetDateTime now = OffsetDateTime.now();
        int handled = 0;

        for (SlaRecord record : sla.dueRecords(now)) {
            CaseInstance c = cases.require(record.caseId());

            if (record.dueAt() != null && !record.dueAt().isAfter(now)) {
                sla.update(breached(record), record.version());
                emit(c, EventTypes.SLA_BREACHED, record);
                updateCaseStatus(c, "BREACHED");
            } else if (record.warnAt() != null && !record.warnAt().isAfter(now)) {
                emit(c, EventTypes.SLA_WARNING, record);
                updateCaseStatus(c, "WARNING");
                // Clear WARN_AT_ so the warning fires once, not on every sweep.
                sla.update(warned(record), record.version());
            }
            handled++;
        }
        return handled;
    }

    private SlaRecord breached(SlaRecord r) {
        return new SlaRecord(r.id(), r.caseId(), r.targetId(), "BREACHED", r.startedAt(),
                r.dueAt(), r.warnAt(), r.pausedAt(), r.pausedReason(), r.pausedTotalSeconds(), r.version());
    }

    private SlaRecord warned(SlaRecord r) {
        return new SlaRecord(r.id(), r.caseId(), r.targetId(), r.status(), r.startedAt(),
                r.dueAt(), null, r.pausedAt(), r.pausedReason(), r.pausedTotalSeconds(), r.version());
    }

    private void updateCaseStatus(CaseInstance c, String status) {
        CaseInstance updated = new CaseInstance(c.id(), c.engineId(), c.tenantId(), c.caseDefId(),
                c.caseDefKey(), c.caseDefVersion(), c.businessKey(), c.title(), c.state(),
                c.priority(), c.assignee(), c.queueId(), c.initiator(), status, c.outcome(),
                c.cancelReason(), c.variables(), c.version(), c.createdAt(), c.updatedAt(), c.closedAt());
        cases.update(updated, c.version());
    }

    private void emit(CaseInstance c, String type, SlaRecord record) {
        publisher.publish(new CaseEvent(CaseIds.newId(), publisher.engineId(), type, c.id(),
                c.tenantId(), OffsetDateTime.now(),
                Map.of("slaId", record.id(), "targetId", record.targetId(),
                        "dueAt", String.valueOf(record.dueAt()))));
    }
}
