package org.casemgmt.sla;

import org.casemgmt.domain.CaseIds;
import org.casemgmt.domain.CaseInstance;
import org.casemgmt.event.CaseEvent;
import org.casemgmt.event.EventPublisher;
import org.casemgmt.event.EventTypes;
import org.casemgmt.observation.SlaLifecyclePort;
import org.casemgmt.repo.CaseRepository;
import org.casemgmt.repo.SlaRepository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Objects;

/**
 * The authoritative SLA effect of accepted engine lifecycle observations.
 *
 * <p>This class deliberately owns SLA truth only.  It does not send a BPMN message or write a
 * case/task projection: a root-process observation has already been validated and projected by
 * {@code DefaultEngineObservationHandler}, which calls this port in the same transaction.  Any
 * optional BPMN reaction must later be represented as an idempotent engine command, never as an
 * SLA transport side effect.
 */
public final class SlaLifecycleService implements SlaLifecyclePort {

    private final SlaRepository sla;
    private final CaseRepository cases;
    private final EventPublisher events;

    public SlaLifecycleService(SlaRepository sla, CaseRepository cases, EventPublisher events) {
        this.sla = Objects.requireNonNull(sla, "sla");
        this.cases = Objects.requireNonNull(cases, "cases");
        this.events = Objects.requireNonNull(events, "events");
    }

    @Override
    public void observeAnchor(Anchor anchor) {
        // Contract-to-occurrence activation is intentionally a later, explicit SLA concern.
        // An engine observation is not allowed to infer a new SLA target from arbitrary BPMN
        // variables.  Root terminalisation below only closes records already made authoritative.
        Objects.requireNonNull(anchor, "anchor");
    }

    @Override
    public void terminalizeRoot(String caseId, TerminalState state, Instant occurredAt) {
        Objects.requireNonNull(caseId, "caseId");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(occurredAt, "occurredAt");

        CaseInstance caseInstance = cases.require(caseId);
        String terminalStatus = state == TerminalState.COMPLETED ? "MET" : "CANCELLED";
        OffsetDateTime terminalAt = OffsetDateTime.ofInstant(occurredAt, ZoneOffset.UTC);

        // The repository conditionally changes only RUNNING/PAUSED rows.  It therefore gives
        // root replay and sweeper/root races a single durable winner; audit and event evidence is
        // emitted solely for rows this invocation actually terminalised.
        for (SlaRecord record : sla.terminalizeNonterminalForCase(caseId, terminalStatus, terminalAt)) {
            String eventType = "MET".equals(terminalStatus)
                    ? EventTypes.SLA_MET : EventTypes.SLA_CANCELLED;
            Map<String, Object> data = Map.of(
                    "slaId", record.id(),
                    "targetId", record.targetId(),
                    "terminalStatus", terminalStatus,
                    "terminalAt", terminalAt.toString());
            events.publish(new CaseEvent(CaseIds.newId(), events.engineId(), eventType,
                    caseId, caseInstance.tenantId(), terminalAt, data));
            events.audit(caseId, caseInstance.tenantId(), "engine", "sla.terminalize-root",
                    "SlaRecord", record.id(), Map.of("status", "RUNNING_OR_PAUSED"), data);
        }
    }
}
