package org.casemgmt.orchestration;

import org.casemgmt.domain.CaseTask;
import org.casemgmt.engine.EngineGateway;
import org.casemgmt.engine.EngineProcessRef;
import org.casemgmt.engine.StartProcessRequest;
import org.casemgmt.repo.LinkedProcessRepository;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.casemgmt.rules.CaseFixtures.caseInstance;
import static org.casemgmt.rules.CaseFixtures.definition;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

class BpmnOrchestrationTest {

    @Test
    void startsAndRecordsTheRootProcessByExactDefinitionIdentity() {
        EngineGateway engine = mock(EngineGateway.class);
        LinkedProcessRepository processes = mock(LinkedProcessRepository.class);
        when(engine.startProcess(any())).thenReturn(new EngineProcessRef(
                "proc-1", "orders:1:exact", "orders", "eng-a:1"));
        EngineDeploymentIdentity identity = new EngineDeploymentIdentity(
                "deployment-1", "orders:1:exact", "orders", 1, null);
        EngineDeploymentIdentityResolver identities = (caseDefinitionId, tenantId) -> identity;
        BpmnOrchestration orchestration = new BpmnOrchestration(engine, processes, identities);

        orchestration.onCaseCreated(caseInstance(Map.of("amount", 10)), definition());

        assertThat(orchestration.mode()).isEqualTo(OrchestrationMode.BPMN);
        ArgumentCaptor<StartProcessRequest> request = ArgumentCaptor.forClass(StartProcessRequest.class);
        ArgumentCaptor<String> correlation = ArgumentCaptor.forClass(String.class);
        InOrder ordered = inOrder(processes, engine);
        ordered.verify(processes).insertRoot(correlation.capture(), eq("eng-a:1"), eq(null),
                eq("orders:1:exact"), eq("orders"), eq(CaseTask.EngineSync.PENDING));
        ordered.verify(engine).startProcess(request.capture());
        ordered.verify(processes).confirmStarted(eq("eng-a:1"), eq(correlation.getValue()),
                eq("proc-1"), eq("orders:1:exact"), eq("orders"), any());
        assertThat(request.getValue().processDefinitionId()).isEqualTo("orders:1:exact");
        assertThat(request.getValue().processDefinitionKey()).isEqualTo("orders");
        assertThat(request.getValue().tenantId()).isNull();
        assertThat(request.getValue().correlationId()).isEqualTo(correlation.getValue());
    }

    @Test
    void pendingRemoteRootStoresCorrelationWithoutInventingAnEngineInstanceId() {
        EngineGateway engine = mock(EngineGateway.class);
        LinkedProcessRepository processes = mock(LinkedProcessRepository.class);
        when(engine.startProcess(any())).thenReturn(new EngineProcessRef(
                null, "orders:1:exact", "orders", "eng-a:1"));
        EngineDeploymentIdentity identity = new EngineDeploymentIdentity(
                "deployment-1", "orders:1:exact", "orders", 1, null);
        BpmnOrchestration orchestration = new BpmnOrchestration(
                engine, processes, (caseDefinitionId, tenantId) -> identity);

        orchestration.onCaseCreated(caseInstance(Map.of()), definition());

        InOrder ordered = inOrder(processes, engine);
        ordered.verify(processes).insertRoot(any(), eq("eng-a:1"), eq(null),
                eq("orders:1:exact"), eq("orders"), eq(CaseTask.EngineSync.PENDING));
        ordered.verify(engine).startProcess(any());
    }
}
