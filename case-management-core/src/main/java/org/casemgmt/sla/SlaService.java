package org.casemgmt.sla;

import org.casemgmt.domain.CaseIds;
import org.casemgmt.domain.CaseInstance;
import org.casemgmt.error.CaseConflictException;
import org.casemgmt.error.NotFoundException;
import org.casemgmt.error.OptimisticLockException;
import org.casemgmt.event.CaseEvent;
import org.casemgmt.event.EventPublisher;
import org.casemgmt.event.EventTypes;
import org.casemgmt.repo.CaseRepository;
import org.casemgmt.repo.SlaRepository;
import org.casemgmt.service.Actor;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Starts, pauses and resumes SLA clocks (spec §7). {@code @Transactional} is genuine only when
 * this bean is obtained through a Spring proxy — see {@code CaseService}'s Javadoc for the same
 * caveat, and beware self-invocation losing it silently. Every mutating method follows the
 * module's row + event + audit convention (see {@code CommentService}/{@code DocumentService}):
 * the {@code CM_SLA_RECORD} write, the {@code CM_EVENT} row and the {@code CM_AUDIT_LOG} row
 * commit or roll back together.
 */
public class SlaService {

    /** Fallback for a policy with no configured calendar: every day, all day, no holidays. */
    private static final Map<String, Object> ALWAYS_OPEN_CALENDAR = Map.of(
            "timezone", "UTC", "workingHours", Map.of(
                    "MONDAY", List.of(Map.of("from", "00:00", "to", "23:59")),
                    "TUESDAY", List.of(Map.of("from", "00:00", "to", "23:59")),
                    "WEDNESDAY", List.of(Map.of("from", "00:00", "to", "23:59")),
                    "THURSDAY", List.of(Map.of("from", "00:00", "to", "23:59")),
                    "FRIDAY", List.of(Map.of("from", "00:00", "to", "23:59")),
                    "SATURDAY", List.of(Map.of("from", "00:00", "to", "23:59")),
                    "SUNDAY", List.of(Map.of("from", "00:00", "to", "23:59"))));

    private final SlaRepository sla;
    private final CaseRepository cases;
    private final EventPublisher publisher;

    public SlaService(SlaRepository sla, CaseRepository cases, EventPublisher publisher) {
        this.sla = sla;
        this.cases = cases;
        this.publisher = publisher;
    }

    /** One RUNNING {@link SlaRecord} per target on {@code policyId}, clocks started from now. */
    @Transactional
    public void startClocks(String caseId, String policyId, Actor actor) {
        CaseInstance c = cases.require(caseId);
        BusinessCalendar calendar = calendarFor(policyId);
        OffsetDateTime now = OffsetDateTime.now();

        for (SlaRepository.TargetRow target : sla.targetsFor(policyId)) {
            OffsetDateTime dueAt = calendar.addDuration(now, Duration.parse(target.durationIso()));
            OffsetDateTime warnAt = target.warningIso() == null ? null
                    : calendar.addDuration(now, Duration.parse(target.warningIso()));
            SlaRecord record = new SlaRecord(CaseIds.newId(), caseId, target.id(), "RUNNING",
                    now, dueAt, warnAt, null, null, 0L, 0L, null);
            sla.insertRecord(record);

            publisher.publish(new CaseEvent(CaseIds.newId(), publisher.engineId(),
                    EventTypes.SLA_STARTED, caseId, c.tenantId(), OffsetDateTime.now(),
                    Map.of("slaId", record.id(), "targetId", target.id(),
                            "dueAt", String.valueOf(dueAt))));
            publisher.audit(caseId, c.tenantId(), actor.userId(), "sla.start", "SlaRecord",
                    record.id(), null, Map.of("targetId", target.id(), "dueAt", String.valueOf(dueAt)));
        }
    }

    @Transactional
    public SlaRecord pause(String caseId, String slaId, long expectedVersion, String reason, Actor actor) {
        CaseInstance c = cases.require(caseId);
        SlaRecord record = requireOwnedByCase(caseId, slaId);
        if (!"RUNNING".equals(record.status())) {
            throw new CaseConflictException("sla-not-running",
                    "SLA clock is " + record.status(), List.of("resume"));
        }
        SlaRepository.TargetRow target = sla.target(record.targetId());
        assertValidPauseReason(target, reason);

        SlaRecord paused = save(new SlaRecord(record.id(), record.caseId(), record.targetId(), "PAUSED",
                record.startedAt(), record.dueAt(), record.warnAt(), OffsetDateTime.now(), reason,
                record.pausedTotalSeconds(), record.version(), record.terminalAt()), expectedVersion);

        // reason may be null (a target with no configured PAUSED_STATES_JSON_ accepts any
        // reason, including none — see assertValidPauseReason). Map.of rejects null values, and
        // Phase 6's REST layer will bind this as an optional body field, so this is reachable in
        // production, not just theoretically. Omit the key entirely rather than store a null or
        // substitute an empty string that could be misread as "reason given but blank".
        Map<String, Object> eventData = new HashMap<>();
        eventData.put("slaId", paused.id());
        eventData.put("targetId", paused.targetId());
        if (reason != null) {
            eventData.put("reason", reason);
        }
        publisher.publish(new CaseEvent(CaseIds.newId(), publisher.engineId(), EventTypes.SLA_PAUSED,
                caseId, c.tenantId(), OffsetDateTime.now(), eventData));

        Map<String, Object> auditAfter = new HashMap<>();
        auditAfter.put("status", "PAUSED");
        if (reason != null) {
            auditAfter.put("reason", reason);
        }
        publisher.audit(caseId, c.tenantId(), actor.userId(), "sla.pause", "SlaRecord", paused.id(),
                Map.of("status", "RUNNING"), auditAfter);

        return paused;
    }

    /**
     * Resuming re-derives the remaining BUSINESS duration (not wall-clock time) between the
     * pause and the original deadline, then re-adds exactly that much business time from now —
     * ruled by the human partner in fix round 1 (I4), overriding this task's original brief: a
     * pause taken Friday 16:00 and resumed Monday 09:00 on a 09:00-17:00 calendar must shift the
     * deadline by 1 business hour, not by the ~65 wall-clock hours between those instants.
     */
    @Transactional
    public SlaRecord resume(String caseId, String slaId, long expectedVersion, Actor actor) {
        CaseInstance c = cases.require(caseId);
        SlaRecord record = requireOwnedByCase(caseId, slaId);
        if (!"PAUSED".equals(record.status())) {
            throw new CaseConflictException("sla-not-paused",
                    "SLA clock is " + record.status(), List.of("pause"));
        }
        SlaRepository.TargetRow target = sla.target(record.targetId());
        BusinessCalendar calendar = calendarFor(target.policyId());
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime pausedAt = record.pausedAt();

        OffsetDateTime newDueAt = calendar.addDuration(now,
                calendar.workingDurationBetween(pausedAt, record.dueAt()));
        OffsetDateTime newWarnAt = record.warnAt() == null ? null
                : calendar.addDuration(now, calendar.workingDurationBetween(pausedAt, record.warnAt()));

        // I5: guard against a negative shift. Duration.between(pausedAt, now) can go negative if
        // pausedAt is ever ahead of now (NTP step-back, mixed-clock deployment, a manual DB
        // edit) — this is purely the observability metric "how long was this paused", already
        // decoupled from the deadline math above by workingDurationBetween/addDuration, which
        // never accepts or produces a negative duration; Math.max(0, ...) keeps this metric from
        // going negative too rather than trusting the raw clock delta.
        long pausedSeconds = Math.max(0, Duration.between(pausedAt, now).toSeconds());

        SlaRecord resumed = save(new SlaRecord(record.id(), record.caseId(), record.targetId(), "RUNNING",
                record.startedAt(), newDueAt, newWarnAt, null, null,
                record.pausedTotalSeconds() + pausedSeconds, record.version(), record.terminalAt()), expectedVersion);

        publisher.publish(new CaseEvent(CaseIds.newId(), publisher.engineId(), EventTypes.SLA_RESUMED,
                caseId, c.tenantId(), OffsetDateTime.now(),
                Map.of("slaId", resumed.id(), "targetId", resumed.targetId(),
                        "dueAt", String.valueOf(newDueAt))));
        publisher.audit(caseId, c.tenantId(), actor.userId(), "sla.resume", "SlaRecord", resumed.id(),
                Map.of("status", "PAUSED"), Map.of("status", "RUNNING", "dueAt", String.valueOf(newDueAt)));

        return resumed;
    }

    public List<SlaRecord> forCase(String caseId) {
        return sla.findByCase(caseId);
    }

    /**
     * S1 fix: a caller can address any {@code slaId} through any {@code caseId} path segment
     * unless this is checked — Phase 6's REST layer passes {@code caseId} as a path variable that
     * was never validated against the record's actual owner. Fails as {@link NotFoundException}
     * (404), the same as an unknown {@code slaId}, rather than a more specific conflict: telling
     * a caller "this SLA record belongs to a different case" would confirm the record's existence
     * to someone who only has rights to the case they guessed, which is the same reason a
     * mismatched id looks identical to a missing one elsewhere in this module.
     */
    private SlaRecord requireOwnedByCase(String caseId, String slaId) {
        SlaRecord record = sla.require(slaId);
        if (!record.caseId().equals(caseId)) {
            throw new NotFoundException("SlaRecord", slaId);
        }
        return record;
    }

    /**
     * S3 fix: the legacy {@code PAUSED_STATES_JSON_} column was read from the database and never consulted,
     * making it a configuration field with no effect. Wired here as the set of reasons this
     * target accepts a pause for — an empty list (no restriction configured) accepts any reason,
     * matching this target's behaviour before this fix.
     */
    private void assertValidPauseReason(SlaRepository.TargetRow target, String reason) {
        List<String> allowed = target.pauseReasons();
        if (!allowed.isEmpty() && !allowed.contains(reason)) {
            throw new CaseConflictException("invalid-pause-reason",
                    "'" + reason + "' is not a configured pause reason for target '"
                            + target.targetKey() + "' — expected one of " + allowed, List.of());
        }
    }

    private BusinessCalendar calendarFor(String policyId) {
        String calendarId = sla.calendarIdOf(policyId);
        Map<String, Object> definition = calendarId == null ? Map.of() : sla.calendarDefinition(calendarId);
        String source = calendarId == null ? "Default SLA calendar"
                : "Business calendar '" + calendarId + "'";
        return BusinessCalendar.fromJson(source, definition.isEmpty() ? ALWAYS_OPEN_CALENDAR : definition);
    }

    private SlaRecord save(SlaRecord record, long expectedVersion) {
        try {
            return sla.update(record, expectedVersion);
        } catch (OptimisticLockException e) {
            throw new CaseConflictException("version-conflict", e.getMessage(), List.of());
        }
    }
}
