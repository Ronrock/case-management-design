package org.casemgmt.sla;

import org.casemgmt.OracleTestBase;
import org.casemgmt.domain.CasePriority;
import org.casemgmt.engine.*;
import org.casemgmt.error.CaseConflictException;
import org.casemgmt.error.NotFoundException;
import org.casemgmt.repo.*;
import org.casemgmt.service.Actor;
import org.casemgmt.service.CaseDefinitionService;
import org.casemgmt.service.CaseService;
import org.casemgmt.service.TestServices;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class SlaServiceTest extends OracleTestBase {

    private SlaService sla;
    private SlaRepository slaRepo;
    private CaseService cases;
    private final Actor alice = new Actor("alice", List.of("handlers"));
    private String caseId;

    // No manual DELETEs here: OracleTestBase already wipes every CM_ table (including
    // CM_SLA_RECORD/CM_SLA_TARGET/CM_SLA_POLICY/CM_BUSINESS_CALENDAR) before each test method
    // via its own @BeforeEach — see CaseServiceTest for the same convention.
    @BeforeEach
    void setUp() throws Exception {
        String json = new String(getClass().getResourceAsStream("/definitions/test-definition.json")
                .readAllBytes(), StandardCharsets.UTF_8);
        new CaseDefinitionService(new CaseDefinitionRepository(dataSource())).deploy(json, "system", "t1");

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

        cases = TestServices.caseService(dataSource(), new NoopGateway());
        sla = TestServices.slaService(jdbc());
        caseId = cases.create("widget-review", "t1", null, "T", CasePriority.MEDIUM, Map.of(), alice).id();
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
                .isInstanceOf(CaseConflictException.class);
    }

    @Test
    void pauseAndResumeRejectAnSlaIdThatBelongsToADifferentCase() {
        sla.startClocks(caseId, "pol-1", alice);
        SlaRecord record = slaRepo.findByCase(caseId).get(0);
        String otherCaseId = cases.create("widget-review", "t1", null, "Other",
                CasePriority.MEDIUM, Map.of(), alice).id();

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
        jdbc().sql("UPDATE CM_SLA_RECORD SET WARN_AT_ = SYSTIMESTAMP - INTERVAL '1' MINUTE").update();

        TestServices.slaSweeper(jdbc()).sweep();

        assertThat(eventTypes()).anySatisfy(t -> assertThat(t).endsWith("case.sla.warning"));
        assertThat(caseSlaStatus()).isEqualTo("WARNING");

        jdbc().sql("UPDATE CM_SLA_RECORD SET DUE_AT_ = SYSTIMESTAMP - INTERVAL '1' MINUTE").update();
        TestServices.slaSweeper(jdbc()).sweep();

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
        jdbc().sql("UPDATE CM_SLA_RECORD SET WARN_AT_ = SYSTIMESTAMP - INTERVAL '1' MINUTE").update();

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

        jdbc().sql("UPDATE CM_SLA_RECORD SET DUE_AT_ = SYSTIMESTAMP - INTERVAL '1' MINUTE WHERE ID_ = :id")
                .param("id", first.id()).update();
        TestServices.slaSweeper(jdbc()).sweep();
        assertThat(caseSlaStatus()).isEqualTo("BREACHED");

        jdbc().sql("UPDATE CM_SLA_RECORD SET WARN_AT_ = SYSTIMESTAMP - INTERVAL '1' MINUTE WHERE ID_ = :id")
                .param("id", second.id()).update();
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

        jdbc().sql("UPDATE CM_SLA_RECORD SET DUE_AT_ = SYSTIMESTAMP - INTERVAL '1' MINUTE WHERE ID_ = :id")
                .param("id", silentRecord.id()).update();

        TestServices.slaSweeper(jdbc()).sweep();

        // The breach itself still happened (that is a fact, not a declared "action") — only the
        // event is gated.
        assertThat(slaRepo.require(silentRecord.id()).status()).isEqualTo("BREACHED");
        assertThat(eventTypes()).noneSatisfy(t -> assertThat(t).endsWith("case.sla.breached"));
    }

    @Test
    void pausedClocksAreNeverSweptIntoBreach() {
        sla.startClocks(caseId, "pol-1", alice);
        SlaRecord record = slaRepo.findByCase(caseId).get(0);
        sla.pause(caseId, record.id(), record.version(), "WAITING_ON_CUSTOMER", alice);
        jdbc().sql("UPDATE CM_SLA_RECORD SET DUE_AT_ = SYSTIMESTAMP - INTERVAL '1' HOUR").update();

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

    private Map<String, Object> officeCalendarJson() {
        Map<String, String> hours = Map.of("from", "09:00", "to", "17:00");
        return Map.of(
                "timezone", "UTC",
                "workingHours", Map.of(
                        "MONDAY", List.of(hours), "TUESDAY", List.of(hours), "WEDNESDAY", List.of(hours),
                        "THURSDAY", List.of(hours), "FRIDAY", List.of(hours)),
                "holidays", List.of());
    }

    /**
     * Minimal no-op {@link EngineGateway}: this test only needs case creation to succeed, not
     * any recorded interaction with the engine. Deliberately local rather than reusing {@code
     * CaseServiceTest.RecordingGateway} from {@code org.casemgmt.service} — that class and its
     * nested gateway are package-private, so reusing them would mean widening another test
     * class's visibility for a dependency this task does not otherwise need.
     */
    private static final class NoopGateway implements EngineGateway {
        public EngineTaskRef createHumanTask(HumanTaskRequest r) {
            return new EngineTaskRef("engine-" + UUID.randomUUID(), r.name(), r.assignee(), r.caseId(), null);
        }
        public void claimTask(String engineTaskId, String userId) {}
        public void completeTask(String engineTaskId, Map<String, Object> variables) {}
        public EngineProcessRef startProcess(StartProcessRequest r) {
            return new EngineProcessRef("proc-" + UUID.randomUUID(), r.processDefinitionKey());
        }
        public void cancelProcess(String processInstanceId, String reason) {}
        public List<EngineTaskRef> findTasks(EngineTaskQuery query) { return List.of(); }
    }
}
