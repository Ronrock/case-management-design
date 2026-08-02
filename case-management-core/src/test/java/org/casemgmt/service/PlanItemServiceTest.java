package org.casemgmt.service;

import org.casemgmt.OracleTestBase;
import org.casemgmt.domain.*;
import org.casemgmt.error.CaseConflictException;
import org.casemgmt.repo.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class PlanItemServiceTest extends OracleTestBase {

    private CaseService cases;
    private PlanItemService planItemService;
    private PlanItemRepository planItems;
    private CaseServiceTest.RecordingGateway gateway;
    private final Actor alice = new Actor("alice", List.of("handlers"));
    private String caseId;

    // No manual DELETEs here: OracleTestBase already wipes every CM_ table before each test
    // method via its own @BeforeEach (see CaseDefinitionServiceTest for the same convention).
    @BeforeEach
    void setUp() throws Exception {
        // A model with one manual-activation item, so enable/start are meaningful.
        String json = """
                {"key":"manual-model","name":"Manual","tenantId":"t1",
                 "planItems":[
                   {"defKey":"manual","type":"HUMAN_TASK","name":"Manual","manualActivation":true,"sortOrder":10},
                   {"defKey":"auto","type":"HUMAN_TASK","name":"Auto","sortOrder":20}]}""";
        new CaseDefinitionService(new CaseDefinitionRepository(dataSource())).deploy(json, "system");

        gateway = new CaseServiceTest.RecordingGateway();
        cases = TestServices.caseService(dataSource(), gateway);
        planItemService = TestServices.planItemService(dataSource(), gateway);
        planItems = new PlanItemRepository(jdbc());
        caseId = cases.create("manual-model", "t1", null, "T", CasePriority.MEDIUM, Map.of(), alice).id();
    }

    private PlanItem item(String defKey) {
        return planItems.findByCase(caseId).stream()
                .filter(i -> i.name().equals(defKey)).findFirst().orElseThrow();
    }

    @Test
    void manualItemsStartEnabledAndCanBeStarted() {
        PlanItem manual = item("manual");
        assertThat(manual.state()).isEqualTo(PlanItemState.ENABLED);

        PlanItem started = planItemService.start(caseId, manual.id(), manual.version(), alice);

        assertThat(started.state()).isEqualTo(PlanItemState.ACTIVE);
    }

    @Test
    void startingAnItemCreatesItsEngineTask() {
        PlanItem manual = item("manual");
        int before = gateway.created.size();

        planItemService.start(caseId, manual.id(), manual.version(), alice);

        assertThat(gateway.created).hasSize(before + 1);
    }

    @Test
    void startingAnAvailableItemConflicts() {
        // 'auto' is already ACTIVE — starting it again is illegal.
        PlanItem auto = item("auto");

        assertThatThrownBy(() -> planItemService.start(caseId, auto.id(), auto.version(), alice))
                .isInstanceOf(CaseConflictException.class)
                .hasMessageContaining("ACTIVE");
    }

    @Test
    void completingAnActiveItemEndsIt() {
        PlanItem auto = item("auto");

        PlanItem completed = planItemService.complete(caseId, auto.id(), auto.version(), alice);

        assertThat(completed.state()).isEqualTo(PlanItemState.COMPLETED);
        assertThat(completed.endedAt()).isNotNull();
    }

    @Test
    void terminateWorksFromAnyLiveStateAndRecordsTheReason() {
        PlanItem manual = item("manual");

        PlanItem terminated = planItemService.terminate(caseId, manual.id(), manual.version(),
                "not needed", alice);

        assertThat(terminated.state()).isEqualTo(PlanItemState.TERMINATED);
        assertThat(terminated.terminationReason()).isEqualTo("not needed");
    }

    @Test
    void staleVersionsAreRejected() {
        PlanItem auto = item("auto");
        planItemService.complete(caseId, auto.id(), auto.version(), alice);

        assertThatThrownBy(() -> planItemService.terminate(caseId, auto.id(), auto.version(), "x", alice))
                .isInstanceOf(CaseConflictException.class);
    }

    @Test
    void everyTransitionEmitsAnEvent() {
        PlanItem manual = item("manual");
        long before = jdbc().sql("SELECT COUNT(*) FROM CM_EVENT").query(Long.class).single();

        planItemService.start(caseId, manual.id(), manual.version(), alice);

        long after = jdbc().sql("SELECT COUNT(*) FROM CM_EVENT").query(Long.class).single();
        assertThat(after).isGreaterThan(before);
    }
}
