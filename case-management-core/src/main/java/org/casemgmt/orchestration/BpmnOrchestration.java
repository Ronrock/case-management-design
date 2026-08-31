package org.casemgmt.orchestration;

import org.casemgmt.domain.CaseDefinition;
import org.casemgmt.domain.CaseIds;
import org.casemgmt.domain.CaseInstance;
import org.casemgmt.domain.CaseTask;
import org.casemgmt.engine.EngineGateway;
import org.casemgmt.engine.EngineProcessRef;
import org.casemgmt.engine.StartProcessRequest;
import org.casemgmt.repo.LinkedProcessRepository;
import java.time.OffsetDateTime;

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
    public void onCaseCreated(CaseInstance caseInstance, CaseDefinition definition) {
        String projectionId = CaseIds.newId();
        EngineDeploymentIdentity identity = identities.requireActive(
                definition.id(), caseInstance.tenantId());
        // The link is the authority used by synchronous embedded callbacks. Persist it before
        // entering the engine so process-start/task-create events can prove ownership while the
        // start command is still on the stack. The enclosing CaseService transaction rolls this
        // pending authority back if the engine start or any lifecycle effect fails.
        processes.insertRoot(projectionId, caseInstance.id(), null,
                identity.processDefinitionId(), identity.processDefinitionKey(),
                CaseTask.EngineSync.PENDING);
        EngineProcessRef process = engine.startProcess(new StartProcessRequest(
                caseInstance.id(), null, identity.processDefinitionId(),
                identity.processDefinitionKey(), identity.tenantId(), caseInstance.variables(),
                projectionId));
        if (process == null
                || !identity.processDefinitionId().equals(process.processDefinitionId())
                || !identity.processDefinitionKey().equals(process.processDefinitionKey())) {
            throw new IllegalStateException(
                    "Engine start returned an inconsistent process-definition identity");
        }
        CaseTask.EngineSync sync = process.processInstanceId() == null
                ? CaseTask.EngineSync.PENDING : CaseTask.EngineSync.SYNCED;
        if (sync == CaseTask.EngineSync.SYNCED) {
            processes.confirmStarted(caseInstance.id(), projectionId,
                    process.processInstanceId(), identity.processDefinitionId(),
                    identity.processDefinitionKey(), OffsetDateTime.now());
        }
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

}
