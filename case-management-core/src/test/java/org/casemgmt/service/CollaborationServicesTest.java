package org.casemgmt.service;

import org.casemgmt.OracleTestBase;
import org.casemgmt.domain.CasePriority;
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
        new CaseDefinitionService(new CaseDefinitionRepository(dataSource())).deploy(json, "system");

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

        List<String> types = jdbc().sql("SELECT TYPE_ FROM CM_EVENT ORDER BY SEQ_")
                .query(String.class).list();
        assertThat(types).anySatisfy(t -> assertThat(t).endsWith("case.comment.added"));
    }

    @Test
    void startingAProcessRecordsTheCorrelation() {
        var row = processes.start(caseId, null, "decision-letter", Map.of("x", 1), alice);

        assertThat(row.processInstanceId()).isNotBlank();
        assertThat(processes.forCase(caseId)).hasSize(1);
        assertThat(gateway.startedProcesses).containsExactly("decision-letter");
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
        new MilestoneRepository(jdbc()).insert(milestoneId, caseId, reviewedPlanItemId, "Acknowledged");

        var achieved = milestones.achieve(caseId, milestoneId, alice);
        assertThat(achieved.achieved()).isTrue();

        assertThatThrownBy(() -> milestones.achieve(caseId, milestoneId, alice))
                .isInstanceOf(CaseConflictException.class)
                .hasMessageContaining("already achieved");
    }
}
