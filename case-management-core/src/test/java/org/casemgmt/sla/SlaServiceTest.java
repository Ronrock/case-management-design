package org.casemgmt.sla;

import org.casemgmt.OracleTestBase;
import org.casemgmt.domain.CaseDefinition;
import org.casemgmt.error.CaseConflictException;
import org.casemgmt.error.NotFoundException;
import org.casemgmt.repo.*;
import org.casemgmt.service.Actor;
import org.casemgmt.service.TestServices;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class SlaServiceTest extends OracleTestBase {

    private SlaService sla;
    private SlaRepository slaRepo;
    private CaseDefinition definition;
    private final Actor alice = new Actor("alice", List.of("handlers"));
    private String caseId;

    // No manual DELETEs here: OracleTestBase already wipes every CM_ table (including
    // CM_SLA_RECORD/CM_SLA_TARGET/CM_SLA_POLICY/CM_BUSINESS_CALENDAR) before each test method
    // via its own @BeforeEach — see CaseServiceTest for the same convention.
    @BeforeEach
    void setUp() {
        definition = TestServices.deployBpmnDefinition(dataSource(), "widget-review", "t1");

        slaRepo = new SlaRepository(jdbc());
        slaRepo.insertCalendar("cal-nl", Map.of(
                "timezone", "Europe/Amsterdam",
                "workingHours", Map.of(
                        "MONDAY", List.of(Map.of("from", "00:00", "to", "23:59")),
                        "TUESDAY", List.of(Map.of("from", "00:00", "to", "23:59")),
                        "WEDNESDAY", List.of(Map.of("from", "00:00", "to", "23:59")),
                        "THURSDAY", List.of(Map.of("from", "00:00", "to", "23:59")),
                        "FRIDAY", List.of(Map.of("from", "00:00", "to", "23:59")),
                        "SATURDAY", List.of(Map.of("from", "00:00", "to", "23:59")),
                        "SUNDAY", List.of(Map.of("from", "00:00", "to", "23:59"))),
                "holidays", List.of()));
        slaRepo.insertPolicy("pol-1", "Standard", null, "cal-nl");
        slaRepo.insertTarget("tgt-first", "pol-1", "firstResponse", "First response",
                "PT4H", "PT3H", List.of("WAITING_ON_CUSTOMER"), List.of("EMIT_EVENT"));

        sla = TestServices.slaService(jdbc());
        caseId = TestServices.insertBpmnCase(dataSource(), definition, "T", alice.userId()).id();
    }

    @Test
    void startingClocksCreatesOneRecordPerTarget() {
        // I6: pin the exact magnitude, not just "after now" / "warn before due" — an
        // implementation that added one minute, or swapped duration and warning, must fail this.
        // Bracketed by addDuration itself (Task 20's already-hardened primitive used as an
        // independent oracle, not a re-read of SlaService's own output) between two "now" reads
        // taken immediately either side of the call, to absorb real test-execution latency.
        BusinessCalendar calNl = calNl();
        OffsetDateTime before = OffsetDateTime.now();
        sla.startClocks(caseId, "pol-1", alice);
        OffsetDateTime after = OffsetDateTime.now();

        SlaRecord record = slaRepo.findByCase(caseId).get(0);
        assertThat(record.status()).isEqualTo("RUNNING");
        assertThat(record.dueAt()).isBetween(
                calNl.addDuration(before, Duration.ofHours(4)), calNl.addDuration(after, Duration.ofHours(4)));
        assertThat(record.warnAt()).isBetween(
                calNl.addDuration(before, Duration.ofHours(3)), calNl.addDuration(after, Duration.ofHours(3)));
    }

    /**
     * Fix round 2: every other S2 test in this class calls {@code startClocks} as setup BEFORE
     * capturing its event/audit baseline, so deleting {@code startClocks}'s own {@code
     * publisher.publish}/{@code publisher.audit} calls (SlaService.java ~67-72) left every
     * existing test green — the baseline already included whatever startClocks wrote. This
     * captures the baseline first.
     */
    @Test
    void startClocksWritesAnSlaStartedEventAndAuditEntryPerTarget() {
        int eventCountBefore = eventTypes().size();
        int auditCountBefore = auditActions().size();

        sla.startClocks(caseId, "pol-1", alice);

        // setUp() seeds exactly one target ("tgt-first") on "pol-1".
        List<String> events = eventTypes();
        assertThat(events).hasSize(eventCountBefore + 1);
        assertThat(events.get(events.size() - 1)).endsWith("case.sla.started");
        List<String> actions = auditActions();
        assertThat(actions).hasSize(auditCountBefore + 1);
        assertThat(actions.get(actions.size() - 1)).isEqualTo("sla.start");
    }

    @Test
    void pauseRecordsWhenTheClockStopped() {
        sla.startClocks(caseId, "pol-1", alice);
        SlaRecord record = slaRepo.findByCase(caseId).get(0);
        int eventCountBefore = eventTypes().size();
        int auditCountBefore = auditActions().size();

        SlaRecord paused = sla.pause(caseId, record.id(), record.version(), "WAITING_ON_CUSTOMER", alice);

        assertThat(paused.status()).isEqualTo("PAUSED");
        assertThat(paused.pausedAt()).isNotNull();
        assertThat(paused.pausedReason()).isEqualTo("WAITING_ON_CUSTOMER");

        // S2: row + event + audit, the module's established convention for a mutation.
        List<String> events = eventTypes();
        assertThat(events).hasSize(eventCountBefore + 1);
        assertThat(events.get(events.size() - 1)).endsWith("case.sla.paused");
        List<String> actions = auditActions();
        assertThat(actions).hasSize(auditCountBefore + 1);
        assertThat(actions.get(actions.size() - 1)).isEqualTo("sla.pause");
    }

    @Test
    void resumeWritesAnSlaResumedEventAndAuditEntry() {
        sla.startClocks(caseId, "pol-1", alice);
        SlaRecord record = slaRepo.findByCase(caseId).get(0);
        SlaRecord paused = sla.pause(caseId, record.id(), record.version(), "WAITING_ON_CUSTOMER", alice);
        int eventCountBefore = eventTypes().size();
        int auditCountBefore = auditActions().size();

        sla.resume(caseId, paused.id(), paused.version(), alice);

        List<String> events = eventTypes();
        assertThat(events).hasSize(eventCountBefore + 1);
        assertThat(events.get(events.size() - 1)).endsWith("case.sla.resumed");
        List<String> actions = auditActions();
        assertThat(actions).hasSize(auditCountBefore + 1);
        assertThat(actions.get(actions.size() - 1)).isEqualTo("sla.resume");
    }

    @Test
    void resumeShiftsTheDeadlineByThePauseLength() {
        sla.startClocks(caseId, "pol-1", alice);
        SlaRecord record = slaRepo.findByCase(caseId).get(0);
        OffsetDateTime originalDue = record.dueAt();

        SlaRecord paused = sla.pause(caseId, record.id(), record.version(), "WAITING_ON_CUSTOMER", alice);
        // Simulate two hours of paused time.
        jdbc().sql("UPDATE CM_SLA_RECORD SET PAUSED_AT_ = PAUSED_AT_ - INTERVAL '2' HOUR WHERE ID_ = :id")
                .param("id", record.id()).update();
        SlaRecord reloaded = slaRepo.require(record.id());

        SlaRecord resumed = sla.resume(caseId, reloaded.id(), reloaded.version(), alice);

        assertThat(resumed.status()).isEqualTo("RUNNING");
        assertThat(resumed.pausedTotalSeconds()).isBetween(7000L, 7400L);
        assertThat(Duration.between(originalDue, resumed.dueAt()).toMinutes()).isBetween(110L, 130L);
    }

    /**
     * I4/I7: the human partner ruled that resume must re-derive the remaining deadline through
     * {@link BusinessCalendar}, the same way {@code startClocks} does — a wall-clock shift is
     * wrong once a pause can span non-working time. {@code cal-nl} everywhere else in this class
     * is open 00:00-23:59 every day, which can never actually exercise that: this test is the
     * only one in the class using a real Mon-Fri 09:00-17:00 calendar.
     *
     * <p>{@code pausedAt}/{@code dueAt} are pinned to fixed, hand-picked instants (a real Friday
     * and the following Monday, verified independently, not derived from "now") so the "remaining
     * business time" figure is fully hand-computable: Friday 16:00-17:00 (1h) + Monday 09:00-10:00
     * (1h) = 2h, skipping the closed weekend entirely — the exact scenario from the ruling. The
     * expected result is then bracketed by feeding that hand-computed 2h into a FRESH {@link
     * BusinessCalendar} instance built independently in this test (Task 20's already-hardened
     * {@code addDuration}, not a re-read of {@code SlaService}'s own computation) around the real
     * "now" at resume time, since this test does not control the wall clock.
     */
    @Test
    void resumeAcrossAWeekendRecomputesTheDeadlineThroughTheBusinessCalendar() {
        Map<String, Object> officeJson = officeCalendarJson();
        slaRepo.insertCalendar("cal-office", officeJson);
        slaRepo.insertPolicy("pol-office", "Office hours", null, "cal-office");
        slaRepo.insertTarget("tgt-office", "pol-office", "resolution", "Resolution",
                "PT8H", null, List.of("WAITING_ON_CUSTOMER"), List.of("EMIT_EVENT"));

        sla.startClocks(caseId, "pol-office", alice);
        SlaRecord record = slaRepo.findByCase(caseId).get(0);
        SlaRecord paused = sla.pause(caseId, record.id(), record.version(), "WAITING_ON_CUSTOMER", alice);

        OffsetDateTime pausedAtFixed = OffsetDateTime.parse("2026-01-02T16:00:00Z"); // a Friday
        OffsetDateTime dueAtFixed = OffsetDateTime.parse("2026-01-05T10:00:00Z");    // the following Monday
        jdbc().sql("UPDATE CM_SLA_RECORD SET PAUSED_AT_ = :pausedAt, DUE_AT_ = :dueAt WHERE ID_ = :id")
                .param("pausedAt", pausedAtFixed).param("dueAt", dueAtFixed).param("id", record.id())
                .update();
        SlaRecord reloaded = slaRepo.require(record.id());

        BusinessCalendar office = BusinessCalendar.fromJson(officeJson);
        OffsetDateTime beforeResume = OffsetDateTime.now();
        SlaRecord resumed = sla.resume(caseId, reloaded.id(), reloaded.version(), alice);
        OffsetDateTime afterResume = OffsetDateTime.now();

        assertThat(resumed.status()).isEqualTo("RUNNING");
        assertThat(resumed.dueAt()).isBetween(
                office.addDuration(beforeResume, Duration.ofHours(2)),
                office.addDuration(afterResume, Duration.ofHours(2)));
    }

    @Test
    void pausingAnAlreadyPausedClockConflicts() {
        sla.startClocks(caseId, "pol-1", alice);
        SlaRecord record = slaRepo.findByCase(caseId).get(0);
        SlaRecord paused = sla.pause(caseId, record.id(), record.version(), "WAITING_ON_CUSTOMER", alice);

        assertThatThrownBy(() -> sla.pause(caseId, paused.id(), paused.version(), "WAITING_ON_CUSTOMER", alice))
                .isInstanceOf(CaseConflictException.class)
                .extracting(e -> ((CaseConflictException) e).code())
                .isEqualTo("sla-not-running");
    }

    @Test
    void resumingARunningClockConflictsWithPauseAsTheAvailableAction() {
        sla.startClocks(caseId, "pol-1", alice);
        SlaRecord record = slaRepo.findByCase(caseId).get(0);

        assertThatThrownBy(() -> sla.resume(caseId, record.id(), record.version(), alice))
                .isInstanceOf(CaseConflictException.class)
                .satisfies(e -> {
                    CaseConflictException conflict = (CaseConflictException) e;
                    assertThat(conflict.code()).isEqualTo("sla-not-paused");
                    assertThat(conflict.availableActions()).containsExactly("pause");
                });
    }

    @Test
    void pauseAndResumeRejectAnSlaIdThatBelongsToADifferentCase() {
        sla.startClocks(caseId, "pol-1", alice);
        SlaRecord record = slaRepo.findByCase(caseId).get(0);
        String otherCaseId = TestServices.insertBpmnCase(
                dataSource(), definition, "Other", alice.userId()).id();

        assertThatThrownBy(() ->
                sla.pause(otherCaseId, record.id(), record.version(), "WAITING_ON_CUSTOMER", alice))
                .isInstanceOf(NotFoundException.class);

        SlaRecord paused = sla.pause(caseId, record.id(), record.version(), "WAITING_ON_CUSTOMER", alice);

        assertThatThrownBy(() -> sla.resume(otherCaseId, paused.id(), paused.version(), alice))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void pauseRejectsAReasonNotConfiguredForTheTarget() {
        sla.startClocks(caseId, "pol-1", alice);
        SlaRecord record = slaRepo.findByCase(caseId).get(0);

        assertThatThrownBy(() ->
                sla.pause(caseId, record.id(), record.version(), "NOT_A_CONFIGURED_REASON", alice))
                .isInstanceOf(CaseConflictException.class);

        // The rejected attempt must not have touched the record.
        assertThat(slaRepo.require(record.id()).status()).isEqualTo("RUNNING");
    }

    /**
     * Fix round 2 regression: a target with no configured {@code PAUSED_STATES_JSON_} accepts
     * any reason — including none. {@code assertValidPauseReason} short-circuits on the empty
     * list, so a null reason used to flow straight into two {@code Map.of(...)} calls (event data
     * and audit "after"), which reject null values with an NPE. Phase 6's REST layer will bind
     * this as an optional body field, so a null reason is reachable in production.
     */
    @Test
    void pauseAcceptsANullReasonWhenNoPauseReasonsAreConfigured() {
        slaRepo.insertTarget("tgt-open", "pol-1", "open", "Open target", "PT1H", null,
                List.of(), List.of());
        sla.startClocks(caseId, "pol-1", alice);
        SlaRecord record = slaRepo.findByCase(caseId).stream()
                .filter(r -> slaRepo.target(r.targetId()).targetKey().equals("open"))
                .findFirst().orElseThrow();

        SlaRecord paused = sla.pause(caseId, record.id(), record.version(), null, alice);

        assertThat(paused.status()).isEqualTo("PAUSED");
        assertThat(paused.pausedReason()).isNull();
    }

    @Test
    void sweeperEmitsWarningThenBreach() {
        sla.startClocks(caseId, "pol-1", alice);
        forceWarnAtPast();

        assertThat(TestServices.slaSweeper(jdbc()).sweep()).isEqualTo(1);

        assertThat(eventTypes()).anySatisfy(t -> assertThat(t).endsWith("case.sla.warning"));
        assertThat(caseSlaStatus()).isEqualTo("WARNING");

        forceDueAtPast();
        assertThat(TestServices.slaSweeper(jdbc()).sweep()).isEqualTo(1);

        assertThat(eventTypes()).anySatisfy(t -> assertThat(t).endsWith("case.sla.breached"));
        assertThat(slaRepo.findByCase(caseId).get(0).status()).isEqualTo("BREACHED");
    }

    /**
     * I1: the "warning fires once" mechanism (clearing WARN_AT_ after emitting) was previously
     * covered by no test that could actually falsify it — every existing test either breaches
     * immediately after warning (masking the clear) or never sweeps twice. This sweeps three
     * times with ONLY WARN_AT_ in the past (DUE_AT_ left untouched, far in the future) and asserts
     * an exact count.
     */
    @Test
    void warningFiresExactlyOnceAcrossRepeatedSweeps() {
        sla.startClocks(caseId, "pol-1", alice);
        forceWarnAtPast();

        TestServices.slaSweeper(jdbc()).sweep();
        TestServices.slaSweeper(jdbc()).sweep();
        TestServices.slaSweeper(jdbc()).sweep();

        assertThat(eventTypes()).filteredOn(t -> t.endsWith("case.sla.warning")).hasSize(1);
        assertThat(slaRepo.findByCase(caseId).get(0).status()).isEqualTo("RUNNING");
    }

    /**
     * I3: {@code dueRecords} has no stable ordering, and the sweeper runs in repeated separate
     * passes, so a case's denormalised {@code SLA_STATUS_} must never be downgraded from BREACHED
     * back to WARNING just because a different target's warning threshold is crossed later.
     */
    @Test
    void aLaterWarningNeverDowngradesAnAlreadyBreachedCase() {
        slaRepo.insertTarget("tgt-second", "pol-1", "resolution", "Resolution",
                "PT8H", "PT6H", List.of(), List.of("EMIT_EVENT"));
        sla.startClocks(caseId, "pol-1", alice);
        List<SlaRecord> records = slaRepo.findByCase(caseId);
        SlaRecord first = records.stream()
                .filter(r -> slaRepo.target(r.targetId()).targetKey().equals("firstResponse"))
                .findFirst().orElseThrow();
        SlaRecord second = records.stream()
                .filter(r -> slaRepo.target(r.targetId()).targetKey().equals("resolution"))
                .findFirst().orElseThrow();

        forceDueAtPast(first.id());
        TestServices.slaSweeper(jdbc()).sweep();
        assertThat(caseSlaStatus()).isEqualTo("BREACHED");

        forceWarnAtPast(second.id());
        TestServices.slaSweeper(jdbc()).sweep();

        assertThat(caseSlaStatus()).isEqualTo("BREACHED");
    }

    /** S3: BREACH_ACTIONS_JSON_ now actually gates whether the breach event fires. */
    @Test
    void breachEventIsSuppressedWhenTheTargetDoesNotConfigureEmitEvent() {
        slaRepo.insertTarget("tgt-silent", "pol-1", "silent", "Silent target",
                "PT1H", null, List.of(), List.of());
        sla.startClocks(caseId, "pol-1", alice);
        SlaRecord silentRecord = slaRepo.findByCase(caseId).stream()
                .filter(r -> slaRepo.target(r.targetId()).targetKey().equals("silent"))
                .findFirst().orElseThrow();

        forceDueAtPast(silentRecord.id());

        TestServices.slaSweeper(jdbc()).sweep();

        // The breach itself still happened (that is a fact, not a declared "action") — only the
        // event is gated.
        assertThat(slaRepo.require(silentRecord.id()).status()).isEqualTo("BREACHED");
        assertThat(eventTypes()).noneSatisfy(t -> assertThat(t).endsWith("case.sla.breached"));
    }

    @Test
    void escalateBreachActionEmitsEscalationEventAndAuditWithoutForcingBreachEvent() {
        slaRepo.insertTarget("tgt-escalate", "pol-1", "escalate", "Escalating target",
                "PT1H", null, List.of(), List.of("ESCALATE"));
        sla.startClocks(caseId, "pol-1", alice);
        SlaRecord escalatingRecord = slaRepo.findByCase(caseId).stream()
                .filter(r -> slaRepo.target(r.targetId()).targetKey().equals("escalate"))
                .findFirst().orElseThrow();

        forceDueAtPast(escalatingRecord.id());

        TestServices.slaSweeper(jdbc()).sweep();

        assertThat(slaRepo.require(escalatingRecord.id()).status()).isEqualTo("BREACHED");
        assertThat(eventTypes()).anySatisfy(t -> assertThat(t).endsWith("case.sla.escalated"));
        assertThat(eventTypes()).noneSatisfy(t -> assertThat(t).endsWith("case.sla.breached"));
        assertThat(auditActions()).contains("sla.escalate");
    }

    @Test
    void pausedClocksAreNeverSweptIntoBreach() {
        sla.startClocks(caseId, "pol-1", alice);
        SlaRecord record = slaRepo.findByCase(caseId).get(0);
        sla.pause(caseId, record.id(), record.version(), "WAITING_ON_CUSTOMER", alice);
        forceDueAtPast();

        TestServices.slaSweeper(jdbc()).sweep();

        assertThat(slaRepo.findByCase(caseId).get(0).status()).isEqualTo("PAUSED");
        assertThat(eventTypes()).noneSatisfy(t -> assertThat(t).endsWith("case.sla.breached"));
    }

    private List<String> eventTypes() {
        return jdbc().sql("SELECT TYPE_ FROM CM_EVENT ORDER BY SEQ_").query(String.class).list();
    }

    private List<String> auditActions() {
        return jdbc().sql("SELECT ACTION_ FROM CM_AUDIT_LOG ORDER BY TS_").query(String.class).list();
    }

    private String caseSlaStatus() {
        return jdbc().sql("SELECT SLA_STATUS_ FROM CM_CASE WHERE ID_ = :id")
                .param("id", caseId).query(String.class).single();
    }

    private void forceWarnAtPast() {
        jdbc().sql("UPDATE CM_SLA_RECORD SET WARN_AT_ = :ts")
                .param("ts", OffsetDateTime.now().minusMinutes(1)).update();
    }

    private void forceWarnAtPast(String recordId) {
        jdbc().sql("UPDATE CM_SLA_RECORD SET WARN_AT_ = :ts WHERE ID_ = :id")
                .param("ts", OffsetDateTime.now().minusMinutes(1))
                .param("id", recordId).update();
    }

    private void forceDueAtPast() {
        jdbc().sql("UPDATE CM_SLA_RECORD SET DUE_AT_ = :ts")
                .param("ts", OffsetDateTime.now().minusMinutes(1)).update();
    }

    private void forceDueAtPast(String recordId) {
        jdbc().sql("UPDATE CM_SLA_RECORD SET DUE_AT_ = :ts WHERE ID_ = :id")
                .param("ts", OffsetDateTime.now().minusMinutes(1))
                .param("id", recordId).update();
    }

    private BusinessCalendar calNl() {
        return BusinessCalendar.fromJson(Map.of(
                "timezone", "Europe/Amsterdam",
                "workingHours", Map.of(
                        "MONDAY", List.of(Map.of("from", "00:00", "to", "23:59")),
                        "TUESDAY", List.of(Map.of("from", "00:00", "to", "23:59")),
                        "WEDNESDAY", List.of(Map.of("from", "00:00", "to", "23:59")),
                        "THURSDAY", List.of(Map.of("from", "00:00", "to", "23:59")),
                        "FRIDAY", List.of(Map.of("from", "00:00", "to", "23:59")),
                        "SATURDAY", List.of(Map.of("from", "00:00", "to", "23:59")),
                        "SUNDAY", List.of(Map.of("from", "00:00", "to", "23:59"))),
                "holidays", List.of()));
    }

    /**
     * Final whole-branch review, Important 8: {@code SlaSweeper.sweep()} is
     * {@code @Transactional} and iterates everything {@code dueRecords} returns, so an unbounded,
     * unordered result set means one transaction holding row locks across {@code CM_SLA_RECORD}
     * and {@code CM_CASE} for the whole backlog — a lock convoy — and two sweepers taking rows in
     * different orders can deadlock (ORA-00060), which escapes {@code processOne}'s per-record
     * {@code OptimisticLockException} catch and rolls the entire batch back.
     *
     * <p>Seeds {@code MAX_SWEEP_BATCH + 5} due records and asserts BOTH halves at once:
     * <ul>
     *   <li>the batch is capped at {@code MAX_SWEEP_BATCH} — not merely "fewer than everything",
     *       which any accidental filter would satisfy;</li>
     *   <li>the rows returned are exactly the {@code MAX_SWEEP_BATCH} lowest ids in ascending
     *       order — which pins the {@code ORDER BY} itself, not just that some prefix came back.
     *       Ids are minted so their lexical order is a shuffle of their insertion order, so a
     *       query with no {@code ORDER BY} would have to return insertion order AND have it
     *       coincide with id order to pass by luck.</li>
     * </ul>
     * The records are inserted directly rather than through {@code startClocks} because this is
     * about the query's shape, and 205 policy-driven clock starts would be slow for no gain.
     */
    @Test
    void dueRecordsIsCappedAndOrderedSoTheSweeperCannotLockTheWholeBacklog() {
        int total = SlaRepository.MAX_SWEEP_BATCH + 5;
        OffsetDateTime overdue = OffsetDateTime.now().minusHours(1);
        List<String> ids = new java.util.ArrayList<>();
        for (int i = 0; i < total; i++) {
            // Lexically ordered ids whose sequence deliberately does NOT follow insertion order:
            // inserted 0, 204, 1, 203, ... so "returns the lowest ids" and "returns the
            // first-inserted rows" are different answers and the assertion can tell them apart.
            int n = (i % 2 == 0) ? i / 2 : total - 1 - (i / 2);
            String id = "sla-%04d".formatted(n);
            ids.add(id);
            slaRepo.insertRecord(new SlaRecord(id, caseId, "tgt-first", "RUNNING",
                    overdue.minusHours(1), overdue, overdue, null, null, 0L, 0L));
        }

        List<SlaRecord> due = slaRepo.dueRecords(OffsetDateTime.now());

        assertThat(due).hasSize(SlaRepository.MAX_SWEEP_BATCH);
        assertThat(due).extracting(SlaRecord::id)
                .containsExactlyElementsOf(ids.stream().sorted()
                        .limit(SlaRepository.MAX_SWEEP_BATCH).toList());
    }

    @Test
    void claimedDueRecordsAreNotClaimedByASecondSweeperPass() {
        OffsetDateTime overdue = OffsetDateTime.now().minusHours(1);
        OffsetDateTime futureDue = OffsetDateTime.now().plusHours(1);
        for (int i = 0; i < 3; i++) {
            slaRepo.insertRecord(new SlaRecord("sla-claim-" + i, caseId, "tgt-first", "RUNNING",
                    overdue.minusHours(1), futureDue, overdue, null, null, 0L, 0L));
        }

        List<SlaRepository.ClaimedRecord> firstClaim = slaRepo.claimDueRecords(OffsetDateTime.now());
        List<SlaRepository.ClaimedRecord> secondClaim = slaRepo.claimDueRecords(OffsetDateTime.now());

        assertThat(firstClaim).hasSize(3);
        assertThat(secondClaim).isEmpty();
        assertThat(firstClaim).extracting(SlaRepository.ClaimedRecord::claimToken)
                .allSatisfy(token -> assertThat(token).isNotNull());

        SlaRepository.ClaimedRecord claimed = firstClaim.get(0);
        SlaRecord record = claimed.record();
        SlaRecord warned = new SlaRecord(record.id(), record.caseId(), record.targetId(),
                record.status(), record.startedAt(), record.dueAt(), null, record.pausedAt(),
                record.pausedReason(), record.pausedTotalSeconds(), record.version());

        assertThatThrownBy(() -> slaRepo.updateClaimed(warned, record.version(), "not-the-claim"))
                .isInstanceOf(org.casemgmt.error.OptimisticLockException.class);

        assertThat(slaRepo.updateClaimed(warned, record.version(), claimed.claimToken()).warnAt())
                .isNull();
        assertThat(slaRepo.claimDueRecords(OffsetDateTime.now()).stream()
                .map(c -> c.record().id()).toList())
                .doesNotContain(record.id());
    }

    @Test
    void insertRecordPersistsPauseAndVersionFieldsItWasGiven() {
        OffsetDateTime started = OffsetDateTime.now().minusHours(3);
        OffsetDateTime due = OffsetDateTime.now().plusHours(1);
        OffsetDateTime warn = OffsetDateTime.now().minusHours(1);
        OffsetDateTime pausedAt = OffsetDateTime.now().minusMinutes(30);

        slaRepo.insertRecord(new SlaRecord("sla-custom", caseId, "tgt-first", "PAUSED",
                started, due, warn, pausedAt, "WAITING_ON_CUSTOMER", 123L, 7L));

        SlaRecord restored = slaRepo.require("sla-custom");
        assertThat(restored.status()).isEqualTo("PAUSED");
        assertThat(restored.pausedAt()).isEqualTo(pausedAt);
        assertThat(restored.pausedReason()).isEqualTo("WAITING_ON_CUSTOMER");
        assertThat(restored.pausedTotalSeconds()).isEqualTo(123L);
        assertThat(restored.version()).isEqualTo(7L);
    }

    private Map<String, Object> officeCalendarJson() {
        Map<String, String> hours = Map.of("from", "09:00", "to", "17:00");
        return Map.of(
                "timezone", "UTC",
                "workingHours", Map.of(
                        "MONDAY", List.of(hours), "TUESDAY", List.of(hours), "WEDNESDAY", List.of(hours),
                        "THURSDAY", List.of(hours), "FRIDAY", List.of(hours)),
                "holidays", List.of());
    }

}
