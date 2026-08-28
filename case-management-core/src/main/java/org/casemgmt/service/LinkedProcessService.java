package org.casemgmt.service;

import org.casemgmt.domain.CaseIds;
import org.casemgmt.domain.CaseInstance;
import org.casemgmt.domain.CaseTask;
import org.casemgmt.engine.EngineGateway;
import org.casemgmt.engine.EngineException;
import org.casemgmt.engine.EngineProcessRef;
import org.casemgmt.engine.StartProcessByKeyRequest;
import org.casemgmt.event.CaseEvent;
import org.casemgmt.event.EventPublisher;
import org.casemgmt.event.EventTypes;
import org.casemgmt.repo.CaseRepository;
import org.casemgmt.repo.LinkedProcessRepository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ad hoc BPMN processes linked to a case (spec §4.9) — a case can kick off a process that is not
 * itself part of the CMMN plan model (e.g. a "decision letter" process), and this class records
 * the correlation between the two.
 *
 * <p>Remote starts use the linked-process row id as a durable correlation key. That local key is
 * stored in {@code CORRELATION_ID_}; {@code PROC_INST_ID_} remains null until the engine confirms
 * its real identity. Embedded/synchronous starts keep the same correlation but can store the real
 * process-instance id immediately.
 *
 * <p>{@code @Transactional}: {@code start} writes a CM_LINKED_PROCESS row, a CM_EVENT row and a
 * CM_AUDIT_LOG row (and, in remote mode, the gateway's own CM_ENGINE_COMMAND row) and needs all
 * of them to commit or roll back together — only true behind the Spring AOP proxy (Task 15); see
 * {@code CollaborationServicesTransactionalIntegrationTest}.
 */
public class LinkedProcessService {

    private final LinkedProcessRepository processes;
    private final CaseRepository cases;
    private final EngineGateway engine;
    private final EventPublisher publisher;

    public LinkedProcessService(LinkedProcessRepository processes, CaseRepository cases,
                                EngineGateway engine, EventPublisher publisher) {
        this.processes = processes;
        this.cases = cases;
        this.engine = engine;
        this.publisher = publisher;
    }

    @Transactional
    public LinkedProcessRepository.LinkedProcessRow start(String caseId, String planItemId,
                                                          String processDefinitionKey,
                                                          Map<String, Object> variables, Actor actor) {
        CaseInstance c = cases.require(caseId);
        // Mint before the engine call so the asynchronous command carries the durable local
        // correlation of the row that will wait for confirmation.
        String id = CaseIds.newId();
        EngineProcessRef ref = engine.startProcessByKey(
                new StartProcessByKeyRequest(caseId, planItemId, processDefinitionKey, variables, id));

        if (ref == null || !processDefinitionKey.equals(ref.processDefinitionKey())) {
            throw new EngineException("Engine start returned an inconsistent process-definition key");
        }
        String instanceId = ref.processInstanceId();
        CaseTask.EngineSync sync = instanceId == null
                ? CaseTask.EngineSync.PENDING      // remote mode: the dispatcher confirms later
                : CaseTask.EngineSync.SYNCED;
        if (sync == CaseTask.EngineSync.SYNCED && ref.processDefinitionId() == null) {
            throw new EngineException("Engine start returned no process-definition id");
        }
        processes.insert(id, caseId, planItemId, instanceId, ref.processDefinitionId(),
                processDefinitionKey, sync);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("correlationId", id);
        details.put("processDefinitionKey", processDefinitionKey);
        if (instanceId != null) {
            details.put("processInstanceId", instanceId);
        }
        publisher.publish(new CaseEvent(CaseIds.newId(), publisher.engineId(),
                EventTypes.PROCESS_STARTED, caseId, c.tenantId(), OffsetDateTime.now(), details));
        publisher.audit(caseId, c.tenantId(), actor.userId(), "process.start", "LinkedProcess", id,
                null, details);

        return processes.findByCase(caseId).stream()
                .filter(row -> row.id().equals(id)).findFirst().orElseThrow();
    }

    public List<LinkedProcessRepository.LinkedProcessRow> forCase(String caseId) {
        return processes.findByCase(caseId);
    }

    /** Confirms an asynchronous engine start under one transaction boundary. */
    @Transactional
    public void confirmStarted(String caseId, String correlationId,
                               String engineProcessInstanceId, OffsetDateTime confirmedAt) {
        cases.require(caseId);
        processes.confirmStarted(caseId, correlationId, engineProcessInstanceId, confirmedAt);
    }

    /** Confirmation seam for adapters that can supply the exact deployed definition identity. */
    @Transactional
    public void confirmStarted(String caseId, String correlationId,
                               String engineProcessInstanceId, String processDefinitionId,
                               String processDefinitionKey, OffsetDateTime confirmedAt) {
        requireNonBlank(processDefinitionId, "processDefinitionId");
        requireNonBlank(processDefinitionKey, "processDefinitionKey");
        cases.require(caseId);
        processes.confirmStarted(caseId, correlationId, engineProcessInstanceId,
                processDefinitionId, processDefinitionKey, confirmedAt);
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
