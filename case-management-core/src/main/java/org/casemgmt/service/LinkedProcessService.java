package org.casemgmt.service;

import org.casemgmt.domain.CaseIds;
import org.casemgmt.domain.CaseInstance;
import org.casemgmt.domain.CaseTask;
import org.casemgmt.engine.EngineGateway;
import org.casemgmt.engine.EngineProcessRef;
import org.casemgmt.engine.StartProcessRequest;
import org.casemgmt.event.CaseEvent;
import org.casemgmt.event.EventPublisher;
import org.casemgmt.event.EventTypes;
import org.casemgmt.repo.CaseRepository;
import org.casemgmt.repo.LinkedProcessRepository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Ad hoc BPMN processes linked to a case (spec §4.9) — a case can kick off a process that is not
 * itself part of the CMMN plan model (e.g. a "decision letter" process), and this class records
 * the correlation between the two.
 *
 * <p><b>Remote-mode null {@code processInstanceId} (Task 12) and how it gets reconciled (Task 18
 * review round 2):</b> {@link EngineGateway} may be {@code OutboxEngineGateway}, which never talks
 * to the engine directly — {@code startProcess} enqueues a {@code CM_ENGINE_COMMAND} row in this
 * same transaction and returns an {@link EngineProcessRef} whose {@code processInstanceId()} is
 * {@code null} (the real id is unknown until {@code EngineCommandDispatcher} drains the command
 * later). CM_LINKED_PROCESS.PROC_INST_ID_ is NOT NULL, so this method falls back to the
 * locally-minted row id ({@code id}, minted BEFORE the engine call specifically so it can double
 * as the correlation key below) as a placeholder in that case, exactly as embedded mode's own real
 * process instance id would occupy that column.
 *
 * <p>The first review round shipped this placeholder with no way back: {@code
 * EngineCommandDispatcher}'s {@code START_PROCESS} case reported the engine's confirmation keyed
 * by {@code planItemId}, which an ad hoc linked process (like every call in this task's own tests)
 * legitimately passes as {@code null} — there was structurally nothing to correlate on, so the
 * placeholder could never be replaced and a completed remote process would look {@code ACTIVE}
 * forever. This is now fixed by passing {@code id} through {@link StartProcessRequest#correlationId()}
 * into the enqueued command's payload; {@code EngineCommandDispatcher} reports the engine's
 * confirmation back against THAT (not {@code planItemId}), and {@link LinkedProcessRepository
 * #markSync} writes the real id over the placeholder once it arrives. This still reuses the SAME
 * command outbox Task 13 already built — no second reconciliation mechanism.
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
        // Minted BEFORE the engine call, not after: it doubles as the correlation key passed
        // through StartProcessRequest#correlationId() (see class Javadoc), so the outbox gateway
        // can carry it into the enqueued command and the dispatcher can report the engine's
        // eventual confirmation back against this exact row.
        String id = CaseIds.newId();
        EngineProcessRef ref = engine.startProcess(
                new StartProcessRequest(caseId, planItemId, processDefinitionKey, variables, id));

        // In remote mode the instance id arrives later; use the locally-minted row id as a
        // placeholder (see class Javadoc) — never a second-guessed value invented here.
        String instanceId = ref.processInstanceId() == null ? id : ref.processInstanceId();
        CaseTask.EngineSync sync = ref.processInstanceId() == null
                ? CaseTask.EngineSync.PENDING      // remote mode: the dispatcher confirms later
                : CaseTask.EngineSync.SYNCED;
        processes.insert(id, caseId, planItemId, instanceId, processDefinitionKey, sync);

        publisher.publish(new CaseEvent(CaseIds.newId(), publisher.engineId(),
                EventTypes.PROCESS_STARTED, caseId, c.tenantId(), OffsetDateTime.now(),
                Map.of("processInstanceId", instanceId, "processDefinitionKey", processDefinitionKey)));
        publisher.audit(caseId, c.tenantId(), actor.userId(), "process.start", "LinkedProcess", id,
                null, Map.of("processDefinitionKey", processDefinitionKey, "processInstanceId", instanceId));

        return processes.findByCase(caseId).stream()
                .filter(row -> row.id().equals(id)).findFirst().orElseThrow();
    }

    public List<LinkedProcessRepository.LinkedProcessRow> forCase(String caseId) {
        return processes.findByCase(caseId);
    }
}
