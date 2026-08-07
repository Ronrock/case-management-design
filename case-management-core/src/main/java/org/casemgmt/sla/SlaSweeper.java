package org.casemgmt.sla;

import org.casemgmt.domain.CaseIds;
import org.casemgmt.domain.CaseInstance;
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
 * Paused clocks are excluded by {@link SlaRepository#dueRecords}'s {@code STATUS_ = 'RUNNING'}
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
     * {@code @Transactional} and iterates everything {@link SlaRepository#dueRecords} returns, so
     * for as long as it runs it holds row locks across {@code CM_SLA_RECORD} and — through
     * {@code cases.require}/{@code updateSlaStatusMonotonic} — {@code CM_CASE}. With an unbounded
     * result set that is one transaction locking the whole backlog on a live, user-facing table:
     * a lock convoy waiting for the first busy day (a backlog after an outage, the first sweep
     * after a bulk import). {@code dueRecords} now returns at most
     * {@link SlaRepository#MAX_SWEEP_BATCH} rows, oldest id first; whatever is left over is due
     * again on the next tick, which is every 60s by default. The {@code ORDER BY} is the other
     * half: without a total order two concurrent sweepers can take the same rows in different
     * sequences and deadlock (ORA-00060), and that arrives as a {@code DataAccessException} —
     * which escapes {@link #processOne}'s per-record {@code OptimisticLockException} catch and
     * takes the whole batch down with it.
     */
    @Transactional
    public int sweep() {
        OffsetDateTime now = OffsetDateTime.now();
        int handled = 0;

        for (SlaRecord record : sla.dueRecords(now)) {
            if (processOne(record, now)) {
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
    private boolean processOne(SlaRecord record, OffsetDateTime now) {
        boolean breaching = record.dueAt() != null && !record.dueAt().isAfter(now);
        boolean warning = !breaching && record.warnAt() != null && !record.warnAt().isAfter(now);
        if (!breaching && !warning) {
            return false;
        }

        try {
            if (breaching) {
                sla.update(breached(record), record.version());
            } else {
                // Clear WARN_AT_ so the warning fires once, not on every sweep.
                sla.update(warned(record), record.version());
            }
        } catch (OptimisticLockException e) {
            log.warn("SLA record {} (case {}) lost a concurrent version race during sweep; "
                    + "left for the next sweep", record.id(), record.caseId());
            return false;
        }

        CaseInstance c = cases.require(record.caseId());
        SlaRepository.TargetRow target = sla.target(record.targetId());
        if (breaching) {
            // S3: BREACH_ACTIONS_JSON_ was read and never consulted. EMIT_EVENT now actually
            // gates the event; the record/case status writes above and below are the SLA breach
            // fact itself, not a declared "action", so they always happen regardless.
            if (target.breachActions().contains("EMIT_EVENT")) {
                emit(c, EventTypes.SLA_BREACHED, record);
            }
            cases.updateSlaStatusMonotonic(c.id(), "BREACHED");
        } else {
            emit(c, EventTypes.SLA_WARNING, record);
            cases.updateSlaStatusMonotonic(c.id(), "WARNING");
        }
        return true;
    }

    private SlaRecord breached(SlaRecord r) {
        return new SlaRecord(r.id(), r.caseId(), r.targetId(), "BREACHED", r.startedAt(),
                r.dueAt(), r.warnAt(), r.pausedAt(), r.pausedReason(), r.pausedTotalSeconds(), r.version());
    }

    private SlaRecord warned(SlaRecord r) {
        return new SlaRecord(r.id(), r.caseId(), r.targetId(), r.status(), r.startedAt(),
                r.dueAt(), null, r.pausedAt(), r.pausedReason(), r.pausedTotalSeconds(), r.version());
    }

    private void emit(CaseInstance c, String type, SlaRecord record) {
        publisher.publish(new CaseEvent(CaseIds.newId(), publisher.engineId(), type, c.id(),
                c.tenantId(), OffsetDateTime.now(),
                Map.of("slaId", record.id(), "targetId", record.targetId(),
                        "dueAt", String.valueOf(record.dueAt()))));
    }
}
