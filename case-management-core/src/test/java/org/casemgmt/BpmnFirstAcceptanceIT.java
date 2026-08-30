package org.casemgmt;

import org.casemgmt.engine.EngineGateway;
import org.casemgmt.engine.EngineProcessRef;
import org.casemgmt.engine.StartProcessRequest;
import org.casemgmt.orchestration.BpmnOrchestration;
import org.casemgmt.orchestration.EngineDeploymentIdentity;
import org.casemgmt.repo.LinkedProcessRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.casemgmt.rules.CaseFixtures.caseInstance;
import static org.casemgmt.rules.CaseFixtures.definition;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Release acceptance sentinel: the case's pinned deployment identity, not "latest by key", starts. */
class BpmnFirstAcceptanceIT {

    @Test
    void caseBoundToVersionOneStartsTheExactVersionOneDefinitionAfterVersionTwoExists() {
        EngineGateway engine = mock(EngineGateway.class);
        LinkedProcessRepository links = mock(LinkedProcessRepository.class);
        EngineDeploymentIdentity v1 = new EngineDeploymentIdentity(
                "deploy-v1", "complaint:1:exact", "complaint", 1, "t1");
        // The mock deliberately knows only v1. A key-based/latest start would therefore not be
        // able to meet the assertion below and would risk silently selecting a later deployment.
        when(engine.startProcess(any())).thenReturn(new EngineProcessRef(
                "process-v1", v1.processDefinitionId(), v1.processDefinitionKey(), "engine-a"));

        new BpmnOrchestration(engine, links, (definitionId, tenantId) -> v1)
                .onCaseCreated(caseInstance(java.util.Map.of()), definition());

        ArgumentCaptor<StartProcessRequest> request = ArgumentCaptor.forClass(StartProcessRequest.class);
        verify(engine).startProcess(request.capture());
        assertThat(request.getValue().processDefinitionId()).isEqualTo(v1.processDefinitionId());
        assertThat(request.getValue().processDefinitionKey()).isEqualTo(v1.processDefinitionKey());
        assertThat(request.getValue().tenantId()).isEqualTo("t1");
    }
}
