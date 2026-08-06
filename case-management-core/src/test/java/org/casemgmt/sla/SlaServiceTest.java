package org.casemgmt.sla;

import org.casemgmt.OracleTestBase;
import org.casemgmt.domain.CasePriority;
import org.casemgmt.engine.*;
import org.casemgmt.error.CaseConflictException;
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
        new CaseDefinitionService(new CaseDefinitionRepository(dataSource())).deploy(json, "system");

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
        sla.startClocks(caseId, "pol-1", alice);

        assertThat(slaRepo.findByCase(caseId)).hasSize(1)
                .allSatisfy(r -> {
                    assertThat(r.status()).isEqualTo("RUNNING");
                    assertThat(r.dueAt()).isAfter(OffsetDateTime.now());
                    assertThat(r.warnAt()).isBefore(r.dueAt());
                });
    }

    @Test
    void pauseRecordsWhenTheClockStopped() {
        sla.startClocks(caseId, "pol-1", alice);
        SlaRecord record = slaRepo.findByCase(caseId).get(0);

        SlaRecord paused = sla.pause(caseId, record.id(), record.version(), "WAITING_ON_CUSTOMER", alice);

        assertThat(paused.status()).isEqualTo("PAUSED");
        assertThat(paused.pausedAt()).isNotNull();
        assertThat(paused.pausedReason()).isEqualTo("WAITING_ON_CUSTOMER");
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

    @Test
    void pausingAnAlreadyPausedClockConflicts() {
        sla.startClocks(caseId, "pol-1", alice);
        SlaRecord record = slaRepo.findByCase(caseId).get(0);
        SlaRecord paused = sla.pause(caseId, record.id(), record.version(), "reason", alice);

        assertThatThrownBy(() -> sla.pause(caseId, paused.id(), paused.version(), "again", alice))
                .isInstanceOf(CaseConflictException.class);
    }

    @Test
    void sweeperEmitsWarningThenBreach() {
        sla.startClocks(caseId, "pol-1", alice);
        jdbc().sql("UPDATE CM_SLA_RECORD SET WARN_AT_ = SYSTIMESTAMP - INTERVAL '1' MINUTE").update();

        TestServices.slaSweeper(jdbc()).sweep();

        assertThat(eventTypes()).anySatisfy(t -> assertThat(t).endsWith("case.sla.warning"));
        assertThat(jdbc().sql("SELECT SLA_STATUS_ FROM CM_CASE WHERE ID_ = :id")
                .param("id", caseId).query(String.class).single()).isEqualTo("WARNING");

        jdbc().sql("UPDATE CM_SLA_RECORD SET DUE_AT_ = SYSTIMESTAMP - INTERVAL '1' MINUTE").update();
        TestServices.slaSweeper(jdbc()).sweep();

        assertThat(eventTypes()).anySatisfy(t -> assertThat(t).endsWith("case.sla.breached"));
        assertThat(slaRepo.findByCase(caseId).get(0).status()).isEqualTo("BREACHED");
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
