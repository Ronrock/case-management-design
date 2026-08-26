package org.casemgmt.orchestration;

import org.casemgmt.domain.CaseTask;
import org.casemgmt.engine.EngineGateway;
import org.casemgmt.engine.EngineProcessRef;
import org.casemgmt.repo.LinkedProcessRepository;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.casemgmt.rules.PlanModelFixtures.caseInstance;
import static org.casemgmt.rules.PlanModelFixtures.definition;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BpmnOrchestrationTest {

    @Test
    void startsAndRecordsTheRootProcessAndDisablesExplicitClose() {
        EngineGateway engine = mock(EngineGateway.class);
        LinkedProcessRepository processes = mock(LinkedProcessRepository.class);
        when(engine.startProcess(any())).thenReturn(new EngineProcessRef("proc-1", "d", "eng-a:1"));
        BpmnOrchestration orchestration = new BpmnOrchestration(engine, processes);

        orchestration.onCaseCreated(caseInstance(Map.of("amount", 10)), definition());

        assertThat(orchestration.mode()).isEqualTo(OrchestrationMode.BPMN);
        assertThat(orchestration.allowsExplicitClose()).isFalse();
        verify(processes).insertRoot(any(), eq("eng-a:1"), eq("proc-1"), eq("d"),
                eq(CaseTask.EngineSync.SYNCED));
    }
}
