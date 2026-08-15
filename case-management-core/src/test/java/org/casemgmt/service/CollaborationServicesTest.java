package org.casemgmt.service;

import org.casemgmt.OracleTestBase;
import org.casemgmt.domain.CasePriority;
import org.casemgmt.domain.CaseTask;
import org.casemgmt.engine.*;
import org.casemgmt.error.CaseConflictException;
import org.casemgmt.repo.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Task 18: {@link CommentService}, {@link LinkedProcessService} and {@link MilestoneService},
 * built with a plain {@code new} via {@link TestServices} — like every other
 * {@code *ServiceTest} in this module, this proves behaviour, not transactionality. The
 * {@code @Transactional} guarantee (all three services write a row, an event and an audit row
 * that must commit or roll back together) is proved separately, behind a real Spring proxy, by
 * {@code CollaborationServicesTransactionalIntegrationTest}.
 */
class CollaborationServicesTest extends OracleTestBase {

    private CaseService cases;
    private CommentService comments;
    private MilestoneService milestones;
    private LinkedProcessService processes;
    private CaseServiceTest.RecordingGateway gateway;
    private final Actor alice = new Actor("alice", List.of("reviewers"));
    private String caseId;

    @BeforeEach
    void setUp() throws Exception {
        String json = new String(getClass().getResourceAsStream("/definitions/test-definition.json")
                .readAllBytes(), StandardCharsets.UTF_8);
        new CaseDefinitionService(new CaseDefinitionRepository(dataSource())).deploy(json, "system", "t1");

        gateway = new CaseServiceTest.RecordingGateway();
        cases = TestServices.caseService(dataSource(), gateway);
        comments = TestServices.commentService(dataSource());
        milestones = TestServices.milestoneService(dataSource());
        processes = TestServices.processService(dataSource(), gateway);
        caseId = cases.create("widget-review", "t1", null, "T", CasePriority.MEDIUM, Map.of(), alice).id();
    }

    @Test
    void internalAndExternalCommentsAreSeparable() {
        comments.add(caseId, "worker note", "internal", alice);
        comments.add(caseId, "letter to customer", "external", alice);

        assertThat(comments.forCase(caseId, "external")).hasSize(1);
        assertThat(comments.forCase(caseId, null)).hasSize(2);
    }

    @Test
    void anInvalidVisibilityIsRejected() {
        assertThatThrownBy(() -> comments.add(caseId, "x", "secret", alice))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void commentsEmitEvents() {
        comments.add(caseId, "note", "internal", alice);

        // Exact match, not endsWith: the type prefix is known in this test context
        // ("org.example.cm", wired by TestServices) and EventPublisher.publish's whole job is to
        // stamp it deterministically — asserting only the suffix would still pass if the prefix
        // came out wrong or missing entirely.
        List<String> types = jdbc().sql("SELECT TYPE_ FROM CM_EVENT ORDER BY SEQ_")
                .query(String.class).list();
        assertThat(types).contains("org.example.cm.case.comment.added");
    }

    @Test
    void startingAProcessRecordsTheCorrelation() {
        var row = processes.start(caseId, null, "letter-process", Map.of("x", 1), alice);

        assertThat(row.processInstanceId()).isNotBlank();
        assertThat(processes.forCase(caseId)).hasSize(1);
        assertThat(gateway.startedProcesses).containsExactly("letter-process");
    }

    /**
     * Task 18 review round 2, Important 1: {@code CaseServiceTest.RecordingGateway#startProcess}
     * (used by every other test in this class) always returns a non-null {@code "proc-1"}, so
     * {@code LinkedProcessService.start}'s {@code ref.processInstanceId() == null ? id : ...}
     * placeholder branch was never exercised by this suite — a future edit that flipped the
     * ternary or dropped the null-guard would have compiled and passed all of it, then thrown
     * {@code ORA-01400} (CM_LINKED_PROCESS.PROC_INST_ID_ is NOT NULL) the first time remote mode
     * actually ran. This test uses a gateway that mimics {@code OutboxEngineGateway}'s contract —
     * {@code startProcess} always returns a null {@code processInstanceId} — and asserts exactly
     * what gets persisted: the row's own locally-minted id, not blank, not null, and marked
     * {@code PENDING} rather than the embedded/remote-synchronous default of {@code SYNCED}.
     */
    @Test
    void startingAProcessInRemoteModeUsesTheRowIdAsAPlaceholder() {
        LinkedProcessService remoteProcesses = TestServices.processService(dataSource(), new NullInstanceGateway());

        var row = remoteProcesses.start(caseId, null, "letter-process", Map.of(), alice);

        assertThat(row.processInstanceId()).isEqualTo(row.id());
        assertThat(row.engineSync()).isEqualTo(CaseTask.EngineSync.PENDING);

        // Persisted, not just returned: the placeholder actually made it into CM_LINKED_PROCESS,
        // via a fresh read rather than trusting the in-memory row start() handed back.
        var reloaded = remoteProcesses.forCase(caseId).stream()
                .filter(r -> r.id().equals(row.id())).findFirst().orElseThrow();
        assertThat(reloaded.processInstanceId()).isEqualTo(row.id());
        assertThat(reloaded.engineSync()).isEqualTo(CaseTask.EngineSync.PENDING);
    }

    /** Mimics {@code OutboxEngineGateway}'s contract for {@code startProcess}: no synchronous engine call, no real id yet. */
    static class NullInstanceGateway implements EngineGateway {
        public EngineTaskRef createHumanTask(HumanTaskRequest r) {
            throw new UnsupportedOperationException("not used by this test");
        }
        public void claimTask(String id, String user) {}
        public void completeTask(String id, Map<String, Object> v) {}
        public EngineProcessRef startProcess(StartProcessRequest r) {
            return new EngineProcessRef(null, r.processDefinitionKey(), r.caseId());
        }
        public void cancelProcess(String id, String reason) {}
        public List<EngineTaskRef> findTasks(EngineTaskQuery q) { return List.of(); }
    }

    /**
     * Task 18 review round 2, Important 2: proves the reconciliation loop actually closes, not
     * merely that a correlation id exists (see {@link #startingAProcessInRemoteModeUsesTheRowIdAsAPlaceholder}
     * for that narrower proof). Wires the genuine remote-mode path — {@code OutboxEngineGateway}
     * enqueues a CM_ENGINE_COMMAND instead of calling the engine, {@code EngineCommandDispatcher}
     * later drains it against a "real" delegate gateway (a stand-in for the actual remote Operaton
     * engine) and reports the confirmed {@code processInstanceId} back — and asserts the end
     * state: CM_LINKED_PROCESS.PROC_INST_ID_ holds the ENGINE's id afterward, not the placeholder
     * {@code LinkedProcessService.start} minted, and ENGINE_SYNC_ has flipped to {@code SYNCED}.
     *
     * <p>Before this review round, {@code EngineCommandDispatcher} reported the confirmation keyed
     * by {@code planItemId}, which this ad hoc process's {@code null} planItemId made structurally
     * impossible to correlate on — the SyncReporter lambda below would simply never have been
     * invoked with this row's id. Verified manually by reverting {@code EngineCommandDispatcher}'s
     * START_PROCESS case to report {@code str(p, "planItemId")} instead of {@code
     * str(p, "correlationId")}: this test then fails with the row still holding its placeholder
     * id and {@code ENGINE_SYNC_} stuck at {@code PENDING}.
     */
    @Test
    void remoteModeReconciliationReplacesThePlaceholderWithTheRealEngineId() {
        EngineCommandRepository commands = new EngineCommandRepository(jdbc());
        OutboxEngineGateway outbox = new OutboxEngineGateway(commands, id -> {});
        LinkedProcessService remoteProcesses = TestServices.processService(dataSource(), outbox);

        var started = remoteProcesses.start(caseId, null, "letter-process", Map.of(), alice);
        assertThat(started.processInstanceId()).isEqualTo(started.id());
        assertThat(started.engineSync()).isEqualTo(CaseTask.EngineSync.PENDING);

        int pendingCommands = jdbc().sql(
                "SELECT COUNT(*) FROM CM_ENGINE_COMMAND WHERE TYPE_ = 'START_PROCESS' AND STATUS_ = 'PENDING'")
                .query(Integer.class).single();
        assertThat(pendingCommands).isEqualTo(1);

        LinkedProcessRepository processRepo = new LinkedProcessRepository(jdbc());
        EngineGateway realEngine = new CaseServiceTest.RecordingGateway(); // returns EngineProcessRef("proc-1", key)
        EngineCommandDispatcher dispatcher = new EngineCommandDispatcher(commands, realEngine,
                (correlationId, sync, engineId) -> processRepo.markSync(correlationId, sync, engineId));

        int processed = dispatcher.drainOnce();
        assertThat(processed).isEqualTo(1);

        var reconciled = remoteProcesses.forCase(caseId).stream()
                .filter(r -> r.id().equals(started.id())).findFirst().orElseThrow();
        assertThat(reconciled.processInstanceId()).isEqualTo("proc-1");
        assertThat(reconciled.processInstanceId()).isNotEqualTo(started.id());
        assertThat(reconciled.engineSync()).isEqualTo(CaseTask.EngineSync.SYNCED);

        String status = jdbc().sql("SELECT STATUS_ FROM CM_ENGINE_COMMAND WHERE TYPE_ = 'START_PROCESS'")
                .query(String.class).single();
        assertThat(status).isEqualTo("DONE");
    }

    @Test
    void milestonesCanBeAchievedManuallyAndOnlyOnce() {
        // CM_MILESTONE.PLAN_ITEM_ID_ is NOT NULL with an FK to CM_PLAN_ITEM, so a manually
        // achieved milestone still has to be backed by a real MILESTONE-type plan item — here,
        // "reviewed" from test-definition.json, which CaseService.create() already instantiated
        // as a CM_PLAN_ITEM row (unachieved: its entry criteria on "review" haven't fired yet).
        String reviewedPlanItemId = new PlanItemRepository(jdbc()).findByCase(caseId).stream()
                .filter(i -> i.name().equals("reviewed")).findFirst().orElseThrow().id();
        String milestoneId = org.casemgmt.domain.CaseIds.newId();
        new MilestoneRepository(jdbc()).insert(milestoneId, caseId, reviewedPlanItemId, "Confirmed");

        var achieved = milestones.achieve(caseId, milestoneId, alice);
        assertThat(achieved.achieved()).isTrue();

        // Exact message, not a substring: MilestoneService.achieve's whole "which milestone,
        // which state" contract is in this string, and a substring match would still pass if the
        // wording drifted or the wrong milestone's name leaked in.
        assertThatThrownBy(() -> milestones.achieve(caseId, milestoneId, alice))
                .isInstanceOf(CaseConflictException.class)
                .hasMessage("Milestone Confirmed is already achieved");
    }
}
