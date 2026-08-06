package org.casemgmt.sla;

import org.casemgmt.domain.CaseIds;
import org.casemgmt.error.CaseConflictException;
import org.casemgmt.error.OptimisticLockException;
import org.casemgmt.repo.CaseRepository;
import org.casemgmt.repo.SlaRepository;
import org.casemgmt.service.Actor;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Starts, pauses and resumes SLA clocks (spec §7). {@code @Transactional} is genuine only when
 * this bean is obtained through a Spring proxy — see {@code CaseService}'s Javadoc for the same
 * caveat, and beware self-invocation losing it silently.
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

    public SlaService(SlaRepository sla, CaseRepository cases) {
        this.sla = sla;
        this.cases = cases;
    }

    /** One RUNNING {@link SlaRecord} per target on {@code policyId}, clocks started from now. */
    @Transactional
    public void startClocks(String caseId, String policyId, Actor actor) {
        cases.require(caseId);
        BusinessCalendar calendar = calendarFor(policyId);
        OffsetDateTime now = OffsetDateTime.now();

        for (SlaRepository.TargetRow target : sla.targetsFor(policyId)) {
            OffsetDateTime dueAt = calendar.addDuration(now, Duration.parse(target.durationIso()));
            OffsetDateTime warnAt = target.warningIso() == null ? null
                    : calendar.addDuration(now, Duration.parse(target.warningIso()));
            sla.insertRecord(new SlaRecord(CaseIds.newId(), caseId, target.id(), "RUNNING",
                    now, dueAt, warnAt, null, null, 0L, 0L));
        }
    }

    @Transactional
    public SlaRecord pause(String caseId, String slaId, long expectedVersion, String reason, Actor actor) {
        SlaRecord record = sla.require(slaId);
        if (!"RUNNING".equals(record.status())) {
            throw new CaseConflictException("sla-not-running",
                    "SLA clock is " + record.status(), List.of("resume"));
        }
        return save(new SlaRecord(record.id(), record.caseId(), record.targetId(), "PAUSED",
                record.startedAt(), record.dueAt(), record.warnAt(), OffsetDateTime.now(), reason,
                record.pausedTotalSeconds(), record.version()), expectedVersion);
    }

    /** Resuming shifts {@code dueAt}/{@code warnAt} forward by exactly the time spent paused. */
    @Transactional
    public SlaRecord resume(String caseId, String slaId, long expectedVersion, Actor actor) {
        SlaRecord record = sla.require(slaId);
        if (!"PAUSED".equals(record.status())) {
            throw new CaseConflictException("sla-not-paused",
                    "SLA clock is " + record.status(), List.of("pause"));
        }
        long pausedSeconds = Duration.between(record.pausedAt(), OffsetDateTime.now()).toSeconds();

        return save(new SlaRecord(record.id(), record.caseId(), record.targetId(), "RUNNING",
                record.startedAt(),
                record.dueAt().plusSeconds(pausedSeconds),
                record.warnAt() == null ? null : record.warnAt().plusSeconds(pausedSeconds),
                null, null, record.pausedTotalSeconds() + pausedSeconds, record.version()),
                expectedVersion);
    }

    public List<SlaRecord> forCase(String caseId) {
        return sla.findByCase(caseId);
    }

    private BusinessCalendar calendarFor(String policyId) {
        String calendarId = sla.calendarIdOf(policyId);
        Map<String, Object> definition = calendarId == null ? Map.of() : sla.calendarDefinition(calendarId);
        return BusinessCalendar.fromJson(definition.isEmpty() ? ALWAYS_OPEN_CALENDAR : definition);
    }

    private SlaRecord save(SlaRecord record, long expectedVersion) {
        try {
            return sla.update(record, expectedVersion);
        } catch (OptimisticLockException e) {
            throw new CaseConflictException("version-conflict", e.getMessage(), List.of());
        }
    }
}
