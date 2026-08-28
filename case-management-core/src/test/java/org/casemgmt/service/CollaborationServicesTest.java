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
    private DocumentService documents;
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
        documents = TestServices.documentService(dataSource());
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
    void documentsAreReferencesAndEmitEvents() {
        var row = documents.add(caseId, "passport.pdf", "evidence", "application/pdf",
                123L, "https://dms.example/documents/passport", alice);

        assertThat(documents.forCase(caseId)).extracting(DocumentRepository.DocumentRow::id)
                .containsExactly(row.id());
        documents.remove(caseId, row.id(), alice);
        assertThat(documents.forCase(caseId)).isEmpty();

        List<String> types = jdbc().sql("SELECT TYPE_ FROM CM_EVENT ORDER BY SEQ_")
                .query(String.class).list();
        assertThat(types)
                .contains("org.example.cm.case.document.added",
                        "org.example.cm.case.document.removed");
    }

    @Test
    void startingAProcessRecordsTheCorrelation() {
        var row = processes.start(caseId, null, "letter-process", Map.of("x", 1), alice);

        assertThat(row.processInstanceId()).isNotBlank();
        assertThat(processes.forCase(caseId)).hasSize(1);
        assertThat(gateway.startedProcesses).containsExactly("letter-process");
    }

    /** A remote start persists correlation, while engine identity remains unknown and null. */
    @Test
    void startingAProcessInRemoteModeStoresCorrelationWithoutAPlaceholderIdentity() {
        LinkedProcessService remoteProcesses = TestServices.processService(dataSource(), new NullInstanceGateway());

        var row = remoteProcesses.start(caseId, null, "letter-process", Map.of(), alice);

        assertThat(row.correlationId()).isEqualTo(row.id());
        assertThat(row.processInstanceId()).isNull();
        assertThat(row.engineSync()).isEqualTo(CaseTask.EngineSync.PENDING);

        // Re-read persistence rather than trusting only the service return value.
        var reloaded = remoteProcesses.forCase(caseId).stream()
                .filter(r -> r.id().equals(row.id())).findFirst().orElseThrow();
        assertThat(reloaded.correlationId()).isEqualTo(row.id());
        assertThat(reloaded.processInstanceId()).isNull();
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
        public EngineProcessRef startProcessByKey(org.casemgmt.engine.StartProcessByKeyRequest r) {
            return new EngineProcessRef(null, r.processDefinitionKey(), r.caseId());
        }
        public void cancelProcess(String id, String reason) {}
        public List<EngineTaskRef> findTasks(EngineTaskQuery q) { return List.of(); }
    }

    /** The dispatcher reconciles an asynchronous start against its durable correlation key. */
    @Test
    void remoteModeReconciliationStoresTheConfirmedRealEngineId() {
        EngineCommandRepository commands = new EngineCommandRepository(jdbc());
        OutboxEngineGateway outbox = new OutboxEngineGateway(commands, id -> {});
        LinkedProcessService remoteProcesses = TestServices.processService(dataSource(), outbox);

        var started = remoteProcesses.start(caseId, null, "letter-process", Map.of(), alice);
        assertThat(started.correlationId()).isEqualTo(started.id());
        assertThat(started.processInstanceId()).isNull();
        assertThat(started.engineSync()).isEqualTo(CaseTask.EngineSync.PENDING);

        int pendingCommands = jdbc().sql(
                "SELECT COUNT(*) FROM CM_ENGINE_COMMAND WHERE TYPE_ = 'START_PROCESS' AND STATUS_ = 'PENDING'")
                .query(Integer.class).single();
        assertThat(pendingCommands).isEqualTo(1);

        LinkedProcessRepository processRepo = new LinkedProcessRepository(jdbc());
        EngineGateway realEngine = new CaseServiceTest.RecordingGateway(); // returns EngineProcessRef("proc-1", key)
        EngineCommandDispatcher dispatcher = new EngineCommandDispatcher(
                commands, realEngine, new EngineCommandDispatcher.SyncReporter() {
                    @Override
                    public void report(String correlationId, CaseTask.EngineSync sync,
                                       String engineId) {
                        processRepo.markSync(correlationId, sync, null);
                    }

                    @Override
                    public void confirmProcessStarted(String confirmedCaseId, String correlationId,
                                                      String engineId,
                                                      java.time.OffsetDateTime confirmedAt) {
                        processRepo.confirmStarted(
                                confirmedCaseId, correlationId, engineId, confirmedAt);
                    }
                });

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
