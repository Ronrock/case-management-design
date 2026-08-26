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

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

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
    void ignoresEngineTaskAndActivityEventsForLegacyPlanModelCases() {
        jdbc().sql("INSERT INTO CM_CASE_DEF (ID_, KEY_, VERSION_NO_, NAME_, ORCHESTRATION_MODE_) "
                + "VALUES ('legacy:1','legacy',1,'Legacy','PLAN_MODEL')").update();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        cases.insert(new CaseInstance("legacy-case", "eng-a", "t1", "legacy:1", "legacy", 1,
                null, "Legacy", CaseState.ACTIVE, CasePriority.MEDIUM, null, null,
                "alice", null, null, null, Map.of(), 0, now, now, null));

        projections.observe(new TaskObservation("legacy-case", "legacy-engine-task",
                "legacy-engine-task", "review", "Review", "create", null,
                List.of("handlers"), "reviewForm", 50, null, now, now));
        projections.observe(new ActivityObservation("legacy-case", "legacy-stage-instance",
                "stage", "Stage", ActivityObservation.Kind.STAGE, null, "start", now, now));

        assertThat(tasks.findByCase("legacy-case")).isEmpty();
        assertThat(planItems.findByCase("legacy-case")).isEmpty();
    }

    private static ProcessCompletionObservation completed(String processId,
                                                           OffsetDateTime observedAt) {
        return new ProcessCompletionObservation("case-1", processId, "sample-case", "completed",
                observedAt, observedAt);
    }
}
