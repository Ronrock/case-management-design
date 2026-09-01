package org.casemgmt.service;

import org.casemgmt.domain.CaseIds;
import org.casemgmt.domain.CaseInstance;
import org.casemgmt.domain.CaseTask;
import org.casemgmt.engine.EngineGateway;
import org.casemgmt.engine.EngineException;
import org.casemgmt.engine.EngineProcessRef;
import org.casemgmt.engine.StartProcessByKeyRequest;
import org.casemgmt.engine.StartProcessRequest;
import org.casemgmt.orchestration.EngineDeploymentIdentity;
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
 * Ad hoc BPMN processes linked to a case (spec §4.9), such as a decision-letter subprocess.
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
        // Persist authority before a synchronous embedded start. The engine bridge can then
        // atomically bind the actual process id/exact definition during its first callback.
        processes.insert(id, caseId, planItemId, null, null,
                processDefinitionKey, CaseTask.EngineSync.PENDING);
        EngineProcessRef ref = engine.startProcessByKey(
                new StartProcessByKeyRequest(caseId, planItemId, processDefinitionKey, variables,
                        id, c.tenantId()));

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
        if (sync == CaseTask.EngineSync.SYNCED) {
            processes.confirmStarted(caseId, id, instanceId, ref.processDefinitionId(),
                    processDefinitionKey, OffsetDateTime.now());
        }

        if (!engine.emitsSynchronousLifecycleObservations()) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("correlationId", id);
            details.put("processDefinitionKey", processDefinitionKey);
            if (instanceId != null) {
                details.put("processInstanceId", instanceId);
            }
            publisher.publish(new CaseEvent(CaseIds.newId(), publisher.engineId(),
                    EventTypes.PROCESS_STARTED, caseId, c.tenantId(), OffsetDateTime.now(), details));
            publisher.audit(caseId, c.tenantId(), actor.userId(), "process.start",
                    "LinkedProcess", id, null, details);
        }

        return processes.findByCase(caseId).stream()
                .filter(row -> row.id().equals(id)).findFirst().orElseThrow();
    }

    public List<LinkedProcessRepository.LinkedProcessRow> forCase(String caseId) {
        return processes.findByCase(caseId);
    }

    /**
     * Persists the authority for a command-backed exact process start before bytes can leave the
     * service. The correlation is the immutable command target, so a lost-response replay finds
     * this same row and an incoming Operaton observation can confirm it through the usual path.
     */
    @Transactional
    public LinkedProcessRepository.LinkedProcessRow registerPendingExact(
            String caseId, String correlationId, EngineDeploymentIdentity identity) {
        requireNonBlank(correlationId, "correlationId");
        if (identity == null || identity.processDefinitionId() == null
                || identity.processDefinitionId().isBlank()) {
            throw new IllegalArgumentException("Exact linked-process start requires process definition identity");
        }
        cases.require(caseId);
        return processes.findByCorrelation(caseId, correlationId).orElseGet(() -> {
            processes.insert(correlationId, caseId, null, null, identity.processDefinitionId(),
                    identity.processDefinitionKey(), CaseTask.EngineSync.PENDING);
            return processes.findByCorrelation(caseId, correlationId).orElseThrow();
        });
    }

    /**
     * Starts a linked process from an immutable deployment identity. This is intentionally
     * separate from {@link #start}: discretionary actions must never let the engine resolve a
     * descriptive key to whichever deployment happens to be newest.
     */
    @Transactional
    public LinkedProcessRepository.LinkedProcessRow startExact(String caseId, String planItemId,
                                                                EngineDeploymentIdentity identity,
                                                                Map<String, Object> variables,
                                                                Actor actor) {
        if (identity == null || identity.processDefinitionId() == null
                || identity.processDefinitionId().isBlank()) {
            throw new IllegalArgumentException("Exact linked-process start requires process definition identity");
        }
        CaseInstance c = cases.require(caseId);
        String id = CaseIds.newId();
        processes.insert(id, caseId, planItemId, null, identity.processDefinitionId(),
                identity.processDefinitionKey(), CaseTask.EngineSync.PENDING);
        EngineProcessRef ref = engine.startProcess(new StartProcessRequest(caseId, planItemId,
                identity.processDefinitionId(), identity.processDefinitionKey(), identity.tenantId(),
                variables, id));
        if (ref == null || !identity.processDefinitionId().equals(ref.processDefinitionId())
                || !identity.processDefinitionKey().equals(ref.processDefinitionKey())) {
            throw new EngineException("Engine start returned an inconsistent exact process definition");
        }
        CaseTask.EngineSync sync = ref.processInstanceId() == null
                ? CaseTask.EngineSync.PENDING : CaseTask.EngineSync.SYNCED;
        if (sync == CaseTask.EngineSync.SYNCED) {
            processes.confirmStarted(caseId, id, ref.processInstanceId(), identity.processDefinitionId(),
                    identity.processDefinitionKey(), OffsetDateTime.now());
        }
        if (!engine.emitsSynchronousLifecycleObservations()) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("correlationId", id);
            details.put("processDefinitionId", identity.processDefinitionId());
            details.put("processDefinitionKey", identity.processDefinitionKey());
            publisher.publish(new CaseEvent(CaseIds.newId(), publisher.engineId(),
                    EventTypes.PROCESS_STARTED, caseId, c.tenantId(), OffsetDateTime.now(), details));
            publisher.audit(caseId, c.tenantId(), actor.userId(), "process.start", "LinkedProcess",
                    id, null, details);
        }
        return processes.findByCase(caseId).stream().filter(row -> row.id().equals(id))
                .findFirst().orElseThrow();
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
