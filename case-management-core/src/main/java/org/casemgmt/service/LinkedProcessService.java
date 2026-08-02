package org.casemgmt.service;

import org.casemgmt.domain.CaseIds;
import org.casemgmt.domain.CaseInstance;
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
 * <p><b>Remote-mode null {@code processInstanceId} (Task 12):</b> {@link EngineGateway} may be
 * {@code OutboxEngineGateway}, which never talks to the engine directly — {@code startProcess}
 * enqueues a {@code CM_ENGINE_COMMAND} row in this same transaction and returns an {@link
 * EngineProcessRef} whose {@code processInstanceId()} is {@code null} (the real id is unknown
 * until {@code EngineCommandDispatcher} drains the command later). CM_LINKED_PROCESS.PROC_INST_ID_
 * is NOT NULL, so this method falls back to the locally-minted row id as a placeholder in that
 * case, exactly as embedded mode's own real process instance id would occupy that column. This
 * does not invent a second reconciliation mechanism: it reuses the SAME command outbox Task 13
 * already built (the dispatcher enqueue happened synchronously, inside this transaction, via the
 * gateway call above) rather than adding a parallel queue or callback here.
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
        EngineProcessRef ref = engine.startProcess(
                new StartProcessRequest(caseId, planItemId, processDefinitionKey, variables));

        String id = CaseIds.newId();
        // In remote mode the instance id arrives later; use the locally-minted row id as a
        // placeholder (see class Javadoc) — never a second-guessed value invented here.
        String instanceId = ref.processInstanceId() == null ? id : ref.processInstanceId();
        processes.insert(id, caseId, planItemId, instanceId, processDefinitionKey);

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
