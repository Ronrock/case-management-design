package org.casemgmt.sla;

import org.casemgmt.domain.CaseIds;
import org.casemgmt.domain.CaseInstance;
import org.casemgmt.error.NotFoundException;
import org.casemgmt.error.OptimisticLockException;
import org.casemgmt.event.CaseEvent;
import org.casemgmt.event.EventPublisher;
import org.casemgmt.event.EventTypes;
import org.casemgmt.repo.CaseRepository;
import org.casemgmt.repo.SlaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Polls {@code CM_SLA_RECORD} for RUNNING clocks past their warning or due threshold, emits
 * {@code sla.warning}/{@code sla.breached} and denormalises the result onto {@code CM_CASE.SLA_STATUS_}.
 * Paused clocks are excluded by {@link SlaRepository#claimDueRecords}'s {@code STATUS_ = 'RUNNING'}
 * predicate — that is the whole point of pause/resume.
 *
 * <p><b>Real background job (fix round 1, I2):</b> Task 25 registers this as a Spring bean and
 * Task 26 schedules {@link #sweep()} every 60s by default — this is not a hypothetical concurrent
 * caller, it is a routine background job racing routine user edits on a live table. The original
 * version of this class denormalised {@code SLA_STATUS_} through {@code CaseRepository.update}'s
 * full-row optimistic write, which meant ANY user editing ANY field on a case with a due SLA
 * clock — not just a second sweeper — could make {@code sweep()} throw {@code
 * OptimisticLockException} and roll back every other record's already-processed writes in the
 * same batch. Fixed two ways: {@link CaseRepository#updateSlaStatusMonotonic} is a targeted,
 * versionless write of just {@code SLA_STATUS_} (so an unrelated user edit can never collide with
 * it at all — see that method's Javadoc), and each due record's own {@code CM_SLA_RECORD} write is
 * isolated in its own {@code try/catch} for the ONE conflict that can still genuinely happen: a
 * second sweeper (or a user's own {@code pause}/{@code resume} call) racing the SAME record's
 * {@code VERSION_}. That specific, expected, benign race is caught and the record is simply left
 * for the next sweep; nothing else about it is guessed at or retried. Any OTHER exception —
 * a real bug, a downstream failure in {@link EventPublisher#publish} — is deliberately NOT
 * caught here and propagates out of {@code sweep()}, rolling back the whole batch via {@code
 * @Transactional}: that is the correct, conservative behaviour for something genuinely
 * unexpected, and the scheduled job simply retries on its next tick. See {@code
 * SlaServiceTransactionalIntegrationTest} in {@code org.casemgmt.service} for both the per-record
 * isolation proof and the whole-batch-rollback-on-a-real-failure proof, using the REAL
 * {@code @Transactional} proxy (this module's {@code TestServices} builds every service with a
 * plain {@code new}, which never puts a bean behind the Spring AOP proxy that makes
 * {@code @Transactional} genuine).
 */
public class SlaSweeper {

    private static final Logger log = LoggerFactory.getLogger(SlaSweeper.class);

    private final SlaRepository sla;
    private final CaseRepository cases;
    private final EventPublisher publisher;

    public SlaSweeper(SlaRepository sla, CaseRepository cases, EventPublisher publisher) {
        this.sla = sla;
        this.cases = cases;
        this.publisher = publisher;
    }

    /**
     * @return how many due records this pass actually warned or breached
     *
     * <p><b>Bounded, ordered batch</b> (final whole-branch review, Important 8). This method is
     * {@code @Transactional} and iterates everything {@link SlaRepository#claimDueRecords}
     * returns, so for as long as it runs it holds row locks across {@code CM_SLA_RECORD} and —
     * through {@code cases.require}/{@code updateSlaStatusMonotonic} — {@code CM_CASE}. With an
     * unbounded result set that is one transaction locking the whole backlog on a live,
     * user-facing table: a lock convoy waiting for the first busy day. The claim query now
     * returns at most {@link SlaRepository#MAX_SWEEP_BATCH} rows, oldest id first, and stamps
     * them with a claim token before processing; whatever is left over is due again on the next
     * tick, which is every 60s by default.
     */
    @Transactional
    public int sweep() {
        OffsetDateTime now = OffsetDateTime.now();
        int handled = 0;
        var claimedRecords = sla.claimDueRecords(now);
        Map<String, SlaRepository.TargetRow> targetsById = sla.targetsById(
                claimedRecords.stream().map(c -> c.record().targetId()).toList());

        for (SlaRepository.ClaimedRecord claimed : claimedRecords) {
            SlaRepository.TargetRow target = targetsById.get(claimed.record().targetId());
            if (target == null) {
                throw new NotFoundException("SlaTarget", claimed.record().targetId());
            }
            if (processOne(claimed, target, now)) {
                handled++;
            }
        }
        return handled;
    }

    /**
     * Processes one due record. The record's own {@code CM_SLA_RECORD} version-checked update
     * happens FIRST in both branches (see the class Javadoc): if it loses a genuine concurrent
     * race, nothing else for this record has happened yet, so catching and skipping here leaves
     * no partial state — no event fired for a status change that didn't actually stick.
     */
    private boolean processOne(SlaRepository.ClaimedRecord claimed, SlaRepository.TargetRow target,
                               OffsetDateTime now) {
        SlaRecord record = claimed.record();
        boolean breaching = record.dueAt() != null && !record.dueAt().isAfter(now);
        boolean warning = !breaching && record.warnAt() != null && !record.warnAt().isAfter(now);
        if (!breaching && !warning) {
            return false;
        }

        try {
            if (breaching) {
                sla.updateClaimed(breached(record), record.version(), claimed.claimToken());
            } else {
                // Clear WARN_AT_ so the warning fires once, not on every sweep.
                sla.updateClaimed(warned(record), record.version(), claimed.claimToken());
            }
        } catch (OptimisticLockException e) {
            log.warn("SLA record {} (case {}) lost a concurrent version race during sweep; "
                    + "left for the next sweep", record.id(), record.caseId());
            return false;
        }

        CaseInstance c = cases.require(record.caseId());
        if (breaching) {
            cases.updateSlaStatusMonotonic(c.id(), "BREACHED");
            // S3: BREACH_ACTIONS_JSON_ was read and never consulted. EMIT_EVENT now actually
            // gates the breach event; ESCALATE emits its own escalation event and audit row. The
            // record/case status writes above and below are the SLA breach fact itself, not a
            // declared "action", so they always happen regardless.
            if (target.breachActions().contains("EMIT_EVENT")) {
                emit(c, EventTypes.SLA_BREACHED, record);
            }
            if (target.breachActions().contains("ESCALATE")) {
                escalate(c, target, record);
            }
        } else {
            cases.updateSlaStatusMonotonic(c.id(), "WARNING");
            emit(c, EventTypes.SLA_WARNING, record);
        }
        return true;
    }

    private SlaRecord breached(SlaRecord r) {
        return new SlaRecord(r.id(), r.caseId(), r.targetId(), "BREACHED", r.startedAt(),
                r.dueAt(), r.warnAt(), r.pausedAt(), r.pausedReason(), r.pausedTotalSeconds(), r.version(),
                OffsetDateTime.now());
    }

    private SlaRecord warned(SlaRecord r) {
        return new SlaRecord(r.id(), r.caseId(), r.targetId(), r.status(), r.startedAt(),
                r.dueAt(), null, r.pausedAt(), r.pausedReason(), r.pausedTotalSeconds(), r.version(),
                r.terminalAt());
    }

    private void emit(CaseInstance c, String type, SlaRecord record) {
        publisher.publish(new CaseEvent(CaseIds.newId(), publisher.engineId(), type, c.id(),
                c.tenantId(), OffsetDateTime.now(),
                Map.of("slaId", record.id(), "targetId", record.targetId(),
                        "dueAt", String.valueOf(record.dueAt()))));
    }

    private void escalate(CaseInstance c, SlaRepository.TargetRow target, SlaRecord record) {
        Map<String, Object> data = Map.of(
                "slaId", record.id(),
                "targetId", record.targetId(),
                "targetKey", target.targetKey(),
                "dueAt", String.valueOf(record.dueAt()));
        publisher.publish(new CaseEvent(CaseIds.newId(), publisher.engineId(),
                EventTypes.SLA_ESCALATED, c.id(), c.tenantId(), OffsetDateTime.now(), data));
        publisher.audit(c.id(), c.tenantId(), "system", "sla.escalate", "SlaRecord",
                record.id(), Map.of("status", "RUNNING"), data);
    }
}
