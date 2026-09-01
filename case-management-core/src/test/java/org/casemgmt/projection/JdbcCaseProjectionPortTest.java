package org.casemgmt.projection;

import org.casemgmt.OracleTestBase;
import org.casemgmt.domain.CaseInstance;
import org.casemgmt.domain.CasePriority;
import org.casemgmt.domain.CaseState;
import org.casemgmt.domain.CaseTask;
import org.casemgmt.domain.PlanItem;
import org.casemgmt.domain.PlanItemState;
import org.casemgmt.domain.PlanItemType;
import org.casemgmt.domain.TaskState;
import org.casemgmt.repo.CaseRepository;
import org.casemgmt.repo.CaseTaskRepository;
import org.casemgmt.repo.LinkedProcessRepository;
import org.casemgmt.repo.PlanItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcCaseProjectionPortTest extends OracleTestBase {

    private CaseRepository cases;
    private PlanItemRepository planItems;
    private CaseTaskRepository tasks;
    private LinkedProcessRepository processes;
    private JdbcCaseProjectionPort projections;
    private final java.util.ArrayList<String> completions = new java.util.ArrayList<>();

    @BeforeEach
    void setUp() {
        jdbc().sql("INSERT INTO CM_CASE_DEF (ID_, KEY_, VERSION_NO_, NAME_, ORCHESTRATION_MODE_) "
                + "VALUES ('sample-case:1','sample-case',1,'Sample case','BPMN')").update();
        cases = new CaseRepository(jdbc());
        planItems = new PlanItemRepository(jdbc());
        tasks = new CaseTaskRepository(jdbc());
        processes = new LinkedProcessRepository(jdbc());
        completions.clear();
        projections = new JdbcCaseProjectionPort(jdbc(),
                (caseId, state, at) -> completions.add(caseId + ":" + state));

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        cases.insert(new CaseInstance("case-1", "eng-a", "t1", "sample-case:1", "sample-case", 1,
                "C-1", "Sample case", CaseState.ACTIVE, CasePriority.MEDIUM, null, null,
                "alice", null, null, null, Map.of(), 0, now, now, null));
        processes.insertRoot("root-link", "case-1", "root-process", "sample-case",
                CaseTask.EngineSync.SYNCED);
        processes.insert("child-link", "case-1", null, "child-process", "child-work",
                CaseTask.EngineSync.SYNCED);
        planItems.insert(new PlanItem("adhoc-plan", "case-1", "adhoc:investigate",
                PlanItemType.HUMAN_TASK, "Investigate", PlanItemState.ACTIVE, null, true, 1,
                "engine-task", null, null, 0, now, now, null));
        tasks.insert(new CaseTask("adhoc-task", "case-1", "adhoc-plan", "engine-task",
                "Investigate", null, TaskState.OPEN, null, null, List.of("handlers"),
                "investigate", 50, null, null, CaseTask.EngineSync.SYNCED,
                0, now, now, null));
    }

    @Test
    void onlyRootCompletionClosesCaseAndTerminalizesDiscretionaryWorkIdempotently() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        projections.observe(completed("child-process", now));

        assertThat(cases.require("case-1").state()).isEqualTo(CaseState.ACTIVE);
        assertThat(tasks.require("adhoc-task").state()).isEqualTo(TaskState.OPEN);
        assertThat(completions).isEmpty();

        projections.observe(completed("root-process", now));
        long closedVersion = cases.require("case-1").version();

        assertThat(cases.require("case-1").state()).isEqualTo(CaseState.CLOSED);
        assertThat(tasks.require("adhoc-task").state()).isEqualTo(TaskState.TERMINATED);
        assertThat(planItems.require("adhoc-plan").state()).isEqualTo(PlanItemState.TERMINATED);
        assertThat(processes.findByCase("case-1")).filteredOn(p -> p.id().equals("child-link"))
                .extracting(LinkedProcessRepository.LinkedProcessRow::state)
                .containsExactly("COMPLETED");

        projections.observe(completed("root-process", now.plusSeconds(1)));
        assertThat(cases.require("case-1").version()).isEqualTo(closedVersion);
        assertThat(completions).containsExactly("case-1:CLOSED");
    }

    @Test
    void handlerSafeRootProjectionReturnsAuthoritativeTransitionWithoutLegacyCallback() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        ProcessProjectionResult first = projections.observeFromHandler(completed("root-process", now));
        ProcessProjectionResult replay = projections.observeFromHandler(
                completed("root-process", now.plusSeconds(1)));

        assertThat(first.rootTransitioned()).isTrue();
        assertThat(first.caseVersion()).isEqualTo(cases.require("case-1").version());
        assertThat(replay).isEqualTo(new ProcessProjectionResult(false, first.caseVersion()));
        assertThat(completions).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"COMPLETED", "TERMINATED"})
    void explicitActiveProcessEvidenceRestoresOnlyItsStaleLinkedRowAndReplayIsANoOp(
            String staleState) {
        OffsetDateTime terminalAt = OffsetDateTime.parse("2026-08-30T10:00:00Z");
        OffsetDateTime observedAt = terminalAt.plusMinutes(5);
        jdbc().sql("""
                UPDATE CM_LINKED_PROCESS SET STATE_ = :state, ENDED_AT_ = :terminalAt
                WHERE CASE_ID_ = 'case-1' AND PROC_INST_ID_ = 'child-process'""")
                .param("state", staleState)
                .param("terminalAt", terminalAt)
                .update();

        ProcessStartObservation started = new ProcessStartObservation(
                "case-1", "child-process", observedAt.minusDays(2), observedAt);

        assertThat(projections.observeStartedFromHandler(started)).isTrue();
        assertThat(projections.observeStartedFromHandler(started)).isFalse();

        assertThat(jdbc().sql("""
                SELECT STATE_, ENDED_AT_ FROM CM_LINKED_PROCESS
                WHERE CASE_ID_ = 'case-1' AND PROC_INST_ID_ = 'child-process'""")
                .query((rs, row) -> Map.entry(rs.getString("STATE_"),
                        rs.getObject("ENDED_AT_") == null))
                .single()).isEqualTo(Map.entry("ACTIVE", true));
        assertThat(processes.findByProcessInstanceId("root-process")).get()
                .extracting(LinkedProcessRepository.LinkedProcessRow::state)
                .isEqualTo("ACTIVE");
    }

    @Test
    void delayedActiveSnapshotCannotReopenANewerTerminalProjection() {
        OffsetDateTime activeSnapshotAt = OffsetDateTime.parse("2026-08-30T10:00:00Z");
        OffsetDateTime terminalProjectedAt = activeSnapshotAt.plusSeconds(1);
        projections.observeFromHandler(completed("child-process", terminalProjectedAt));

        boolean restored = projections.observeStartedFromHandler(new ProcessStartObservation(
                "case-1", "child-process", activeSnapshotAt.minusDays(2), activeSnapshotAt));

        assertThat(restored).isFalse();
        assertThat(processes.findByProcessInstanceId("child-process")).get()
                .extracting(LinkedProcessRepository.LinkedProcessRow::state)
                .isEqualTo("COMPLETED");
    }

    @Test
    void globallyCollidingTaskAndActivityIdsCannotCrossCaseTenantOrProcessOwnership() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc().sql("INSERT INTO CM_CASE_DEF (ID_, KEY_, VERSION_NO_, NAME_, TENANT_ID_, ORCHESTRATION_MODE_) "
                + "VALUES ('other:1','other',1,'Other','t2','BPMN')").update();
        cases.insert(new CaseInstance("case-2", "eng-a", "t2", "other:1", "other", 1,
                "C-2", "Other case", CaseState.ACTIVE, CasePriority.MEDIUM, null, null,
                "bob", null, null, null, Map.of(), 0, now, now, null));
        processes.insertRoot("other-root-link", "case-2", "other-process", "other",
                CaseTask.EngineSync.SYNCED);
        projections.observe(new TaskObservation("case-2", "other-process", "shared-task",
                "shared-activity", "review", "Review", "create", null,
                List.of("reviewers"), null, 50, null, now, now));

        assertThatThrownBy(() -> projections.observe(new TaskObservation(
                "case-1", "root-process", "shared-task", "shared-activity", "review",
                "Hijack", "complete", "mallory", List.of(), null, 50, null, now, now)))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> projections.observe(new ActivityObservation(
                "case-1", "root-process", "shared-activity", "stage", "Hijack stage",
                ActivityObservation.Kind.STAGE, null, "end", now, now)))
                .isInstanceOf(SecurityException.class);

        assertThat(tasks.findByCase("case-2")).singleElement()
                .satisfies(task -> {
                    assertThat(task.name()).isEqualTo("Review");
                    assertThat(task.state()).isEqualTo(TaskState.OPEN);
                });
        assertThat(planItems.findByCase("case-2")).singleElement()
                .satisfies(item -> assertThat(item.name()).isEqualTo("Review"));
        assertThat(tasks.findByCase("case-1")).extracting(CaseTask::engineTaskId)
                .doesNotContain("shared-task");
    }

    @Test
    void sameCaseLegacyProjectionCanAcquireProcessProvenanceWithoutMigrationGuessing() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        projections.observe(new TaskObservation("case-1", "legacy-task", "legacy-activity",
                "review", "Legacy", "create", null, List.of(), null, 50, null, now, now));

        projections.observe(new TaskObservation("case-1", "root-process", "legacy-task",
                "legacy-activity", "review", "Claimed", "claim", "alice", List.of(), null,
                50, null, now.plusSeconds(1), now.plusSeconds(1)));

        assertThat(jdbc().sql("SELECT PROC_INST_ID_ FROM CM_TASK WHERE CAMUNDA_TASK_ID_ = 'legacy-task'")
                .query(String.class).single()).isEqualTo("root-process");
        assertThat(jdbc().sql("""
                SELECT PROC_INST_ID_ FROM CM_PLAN_ITEM
                WHERE ENGINE_ACTIVITY_ID_ = 'legacy-activity'""")
                .query(String.class).single()).isEqualTo("root-process");
    }

    @Test
    void taskCannotBeReboundToAnotherActivityInTheSameCaseAndProcess() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        projections.observe(new TaskObservation("case-1", "root-process", "bound-task",
                "bound-activity", "review", "Review", "create", null, List.of(), null,
                50, null, now, now));

        assertThatThrownBy(() -> projections.observe(new TaskObservation("case-1",
                "root-process", "bound-task", "different-activity", "review", "Review",
                "claim", "alice", List.of(), null, 50, null, now.plusSeconds(1),
                now.plusSeconds(1))))
                .isInstanceOf(ProjectionOwnershipException.class)
                .extracting(error -> ((ProjectionOwnershipException) error).classification())
                .isEqualTo(ProjectionOwnershipException.Classification.RELATIONSHIP_MISMATCH);
    }

    @Test
    void taskProjectionExecutesInsertAndKnownProvenanceUpdateOnOracle() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        projections.observe(new TaskObservation("case-1", "root-process", "task-insert-update",
                "activity-insert-update", "review", "Review", "create", null, List.of(),
                null, 50, null, now, now));

        projections.observe(new TaskObservation("case-1", "root-process", "task-insert-update",
                "activity-insert-update", "review", "Review claimed", "claim", "alice",
                List.of(), null, 50, null, now.plusSeconds(1), now.plusSeconds(1)));

        assertThat(tasks.findByCase("case-1")).filteredOn(task ->
                        "task-insert-update".equals(task.engineTaskId()))
                .singleElement()
                .satisfies(task -> {
                    assertThat(task.state()).isEqualTo(TaskState.CLAIMED);
                    assertThat(task.assignee()).isEqualTo("alice");
                });
        assertThat(planItems.findByCase("case-1")).filteredOn(item ->
                        "activity-insert-update".equals(item.engineActivityId()))
                .singleElement()
                .satisfies(item -> assertThat(item.name()).isEqualTo("Review claimed"));
    }

    @Test
    void concurrentCrossCaseEntityCollisionHasOneOwnerAndOneBoundedRejection()
            throws Exception {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        cases.insert(new CaseInstance("case-2", "eng-a", "t1", "sample-case:1",
                "sample-case", 1, "C-2", "Second", CaseState.ACTIVE, CasePriority.MEDIUM,
                null, null, "bob", null, null, null, Map.of(), 0, now, now, null));
        processes.insertRoot("root-link-2", "case-2", "root-process-2", "sample-case",
                CaseTask.EngineSync.SYNCED);
        TaskObservation first = new TaskObservation("case-1", "root-process", "race-task",
                "race-activity", "review", "First", "create", null, List.of(), null,
                50, null, now, now);
        TaskObservation second = new TaskObservation("case-2", "root-process-2", "race-task",
                "race-activity", "review", "Second", "create", null, List.of(), null,
                50, null, now, now);
        TransactionTemplate transaction = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource()));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        int applied = 0;
        int rejected = 0;

        try (var pool = Executors.newFixedThreadPool(2)) {
            var futures = List.of(
                    pool.submit(() -> transaction.executeWithoutResult(status -> {
                        ready.countDown();
                        await(start);
                        new JdbcCaseProjectionPort(JdbcClient.create(dataSource())).observe(first);
                    })),
                    pool.submit(() -> transaction.executeWithoutResult(status -> {
                        ready.countDown();
                        await(start);
                        new JdbcCaseProjectionPort(JdbcClient.create(dataSource())).observe(second);
                    })));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (var future : futures) {
                try {
                    future.get(10, TimeUnit.SECONDS);
                    applied++;
                } catch (ExecutionException failure) {
                    assertThat(failure.getCause()).isInstanceOf(ProjectionOwnershipException.class);
                    rejected++;
                }
            }
        } finally {
            start.countDown();
        }

        assertThat(applied).isEqualTo(1);
        assertThat(rejected).isEqualTo(1);
        assertThat(jdbc().sql("SELECT COUNT(*) FROM CM_TASK WHERE CAMUNDA_TASK_ID_ = 'race-task'")
                .query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void milestoneReachedReopenedAndCancelledHaveDistinctPersistentSemantics() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        projections.observe(milestone("end", now));
        assertMilestone("COMPLETED", 1, true);

        projections.observe(milestone("start", now.plusSeconds(1)));
        assertMilestone("ACTIVE", 0, false);

        projections.observe(milestone("delete", now.plusSeconds(2)));
        assertMilestone("TERMINATED", 0, false);
    }

    private void assertMilestone(String planState, int achieved, boolean hasAchievedAt) {
        assertThat(jdbc().sql("""
                SELECT STATE_ FROM CM_PLAN_ITEM
                WHERE CASE_ID_ = 'case-1' AND ENGINE_ACTIVITY_ID_ = 'milestone-instance'""")
                .query(String.class).single()).isEqualTo(planState);
        assertThat(jdbc().sql("""
                SELECT ACHIEVED_, ACHIEVED_AT_ FROM CM_MILESTONE milestone
                JOIN CM_PLAN_ITEM item ON item.ID_ = milestone.PLAN_ITEM_ID_
                WHERE item.CASE_ID_ = 'case-1'
                  AND item.ENGINE_ACTIVITY_ID_ = 'milestone-instance'""")
                .query((rs, row) -> Map.entry(rs.getInt("ACHIEVED_"),
                        rs.getObject("ACHIEVED_AT_", OffsetDateTime.class) != null))
                .single()).isEqualTo(Map.entry(achieved, hasAchievedAt));
    }

    private static ActivityObservation milestone(String event, OffsetDateTime at) {
        return new ActivityObservation("case-1", "root-process", "milestone-instance",
                "accepted", "Accepted", ActivityObservation.Kind.MILESTONE, "accepted",
                event, at, at);
    }

    private static ProcessCompletionObservation completed(String processId,
                                                           OffsetDateTime observedAt) {
        return new ProcessCompletionObservation("case-1", processId, "sample-case", "completed",
                observedAt, observedAt);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("Timed out");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }
}
