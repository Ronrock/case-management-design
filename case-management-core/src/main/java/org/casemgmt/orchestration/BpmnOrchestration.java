package org.casemgmt.orchestration;

import org.casemgmt.domain.CaseDefinition;
import org.casemgmt.domain.CaseIds;
import org.casemgmt.domain.CaseInstance;
import org.casemgmt.domain.CaseTask;
import org.casemgmt.domain.PlanItem;
import org.casemgmt.domain.PlanItemDefinition;
import org.casemgmt.engine.EngineGateway;
import org.casemgmt.engine.EngineProcessRef;
import org.casemgmt.engine.StartProcessRequest;
import org.casemgmt.repo.LinkedProcessRepository;
import org.casemgmt.rules.CaseSnapshot;
import org.casemgmt.rules.Transition;

import java.util.List;

/** Root-process lifecycle for BPMN-backed cases; work is supplied by engine observations. */
public final class BpmnOrchestration implements CaseOrchestration {

    private final EngineGateway engine;
    private final LinkedProcessRepository processes;
    private final EngineDeploymentIdentityResolver identities;

    public BpmnOrchestration(EngineGateway engine, LinkedProcessRepository processes,
                             EngineDeploymentIdentityResolver identities) {
        this.engine = engine;
        this.processes = processes;
        this.identities = identities;
    }

    @Override
    public OrchestrationMode mode() {
        return OrchestrationMode.BPMN;
    }

    @Override
    public List<PlanItem> initialItems(String caseId, CaseDefinition definition) {
        return List.of();
    }

    @Override
    public void onCaseCreated(CaseInstance caseInstance, CaseDefinition definition) {
        String projectionId = CaseIds.newId();
        EngineDeploymentIdentity identity = identities.requireActive(
                definition.id(), caseInstance.tenantId());
        EngineProcessRef process = engine.startProcess(new StartProcessRequest(
                caseInstance.id(), null, identity.processDefinitionId(),
                identity.processDefinitionKey(), identity.tenantId(), caseInstance.variables(),
                projectionId));
        CaseTask.EngineSync sync = process.processInstanceId() == null
                ? CaseTask.EngineSync.PENDING : CaseTask.EngineSync.SYNCED;
        processes.insertRoot(projectionId, caseInstance.id(), process.processInstanceId(),
                identity.processDefinitionKey(), sync);
    }

    @Override
    public void onCaseCancelled(CaseInstance caseInstance, String reason) {
        processes.findByCase(caseInstance.id()).stream()
                .filter(process -> process.processDefinitionKey().equals(caseInstance.caseDefKey()))
                .filter(process -> "ACTIVE".equals(process.state()))
                .forEach(process -> {
                    engine.cancelProcess(process.processInstanceId(), reason);
                    processes.markState(process.processInstanceId(), "TERMINATED");
                });
    }

    @Override
    public List<Transition> evaluate(CaseSnapshot snapshot) {
        return List.of();
    }

    @Override
    public List<PlanItemDefinition> repeatable(CaseSnapshot snapshot) {
        return List.of();
    }

    @Override
    public PlanItem repeat(PlanItem previous, PlanItemDefinition definition) {
        throw new UnsupportedOperationException("BPMN repetition is projected from engine activity instances");
    }
}
