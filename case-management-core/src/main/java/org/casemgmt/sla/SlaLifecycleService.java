package org.casemgmt.sla;

import org.casemgmt.domain.CaseIds;
import org.casemgmt.domain.CaseInstance;
import org.casemgmt.event.CaseEvent;
import org.casemgmt.event.EventPublisher;
import org.casemgmt.event.EventTypes;
import org.casemgmt.observation.SlaLifecyclePort;
import org.casemgmt.repo.CaseRepository;
import org.casemgmt.repo.CaseDefinitionReleaseRepository;
import org.casemgmt.repo.CaseDefinitionVersionBindingRepository;
import org.casemgmt.repo.JsonCodec;
import org.casemgmt.repo.SlaRepository;
import org.casemgmt.release.CaseContractValidator;
import org.casemgmt.release.ValidatedCaseContract;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.Duration;
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
    private final BoundSlaContractResolver contracts;

    public SlaLifecycleService(SlaRepository sla, CaseRepository cases, EventPublisher events) {
        this(sla, cases, events, null);
    }

    public SlaLifecycleService(SlaRepository sla, CaseRepository cases, EventPublisher events,
                               CaseDefinitionVersionBindingRepository bindings,
                               CaseDefinitionReleaseRepository releases,
                               CaseContractValidator validator) {
        this(sla, cases, events, new BoundSlaContractResolver(bindings, releases, validator));
    }

    private SlaLifecycleService(SlaRepository sla, CaseRepository cases, EventPublisher events,
                                BoundSlaContractResolver contracts) {
        this.sla = Objects.requireNonNull(sla, "sla");
        this.cases = Objects.requireNonNull(cases, "cases");
        this.events = Objects.requireNonNull(events, "events");
        this.contracts = contracts;
    }

    @Override
    public void observeAnchor(Anchor anchor) {
        Objects.requireNonNull(anchor, "anchor");
        if (contracts == null) return; // Compatibility for callers that have not enabled releases.
        CaseInstance instance = cases.require(anchor.caseId());
        BoundSlaContractResolver.ResolvedContract bound = contracts.resolve(instance);
        String observedAnchor = anchorName(anchor);
        for (ValidatedCaseContract.SlaBindingDefinition binding : bound.contract().slaBindings()) {
            if (!observedAnchor.equals(binding.startAnchor())) continue;
            createOccurrence(instance, bound, binding, anchor, observedAnchor);
        }
    }

    @Override
    public void terminalizeRoot(String caseId, TerminalState state, Instant occurredAt) {
        Objects.requireNonNull(caseId, "caseId");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(occurredAt, "occurredAt");

        CaseInstance caseInstance = cases.require(caseId);
        OffsetDateTime terminalAt = OffsetDateTime.ofInstant(occurredAt, ZoneOffset.UTC);
        String rootAnchor = state == TerminalState.COMPLETED ? "CASE_CLOSED" : "CASE_CANCELLED";
        // Contract snapshots, not the mutable current policy table or a case-state shortcut,
        // decide whether this root observation means MET or CANCELLED for each occurrence.
        var terminalized = contracts == null
                ? sla.terminalizeNonterminalForCase(caseId,
                        state == TerminalState.COMPLETED ? "MET" : "CANCELLED", terminalAt)
                : sla.terminalizeContractOccurrencesForRoot(caseId, rootAnchor, terminalAt);
        for (SlaRecord record : terminalized) {
            String terminalStatus = record.status();
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

    private void createOccurrence(CaseInstance instance, BoundSlaContractResolver.ResolvedContract bound,
                                  ValidatedCaseContract.SlaBindingDefinition binding, Anchor anchor,
                                  String observedAnchor) {
        if (binding.duration() == null) {
            throw new IllegalStateException("SLA binding '" + binding.id()
                    + "' uses dueDateExpression, which has no registered deterministic evaluator");
        }
        if (!sla.calendarExists(binding.calendarId())) {
            throw new IllegalStateException("SLA binding '" + binding.id() + "' references missing calendar '"
                    + binding.calendarId() + "'");
        }
        OffsetDateTime startedAt = OffsetDateTime.ofInstant(anchor.occurredAt(), ZoneOffset.UTC);
        Duration duration = Duration.parse(binding.duration());
        OffsetDateTime dueAt = startedAt.plus(duration);
        OffsetDateTime warnAt = binding.warnings().isEmpty() ? null
                : startedAt.plus(Duration.parse(binding.warnings().getFirst()));
        String occurrenceKey = occurrenceKey(binding, anchor);
        String hash = JsonCodec.sha256(bound.releaseId() + "|" + binding.id());
        String policyId = "cp-" + hash.substring(0, 40);
        String targetId = "ct-" + hash.substring(0, 40);
        sla.ensureContractTarget(policyId, targetId, binding.id(), binding.calendarId(),
                binding.duration(), binding.warnings().isEmpty() ? null : binding.warnings().getFirst(),
                binding.breachActions());
        String occurrenceHash = JsonCodec.sha256(instance.id() + "|" + bound.releaseId() + "|"
                + binding.id() + "|" + occurrenceKey);
        sla.insertContractOccurrenceIfAbsent(new SlaRepository.ContractOccurrence(
                "so-" + occurrenceHash.substring(0, 40), instance.id(), targetId, binding.id(),
                binding.targetVersion(), binding.scope().name(), occurrenceKey, bound.releaseId(),
                bound.sha256(), binding.calendarId(), binding.calendarRevision(), binding.meetAnchor(),
                binding.cancelAnchor(), startedAt, dueAt, warnAt,
                JsonCodec.toJson(Map.of("anchor", observedAnchor, "occurredAt",
                        anchor.occurredAt().toString(), "transition", "STARTED"))));
    }

    private static String occurrenceKey(ValidatedCaseContract.SlaBindingDefinition binding,
                                        Anchor anchor) {
        if (binding.occurrenceKey() != null) return binding.occurrenceKey() + ":" + anchor.entityId();
        return binding.scope() == ValidatedCaseContract.SlaScope.CASE ? "CASE" : anchor.entityId();
    }

    private static String anchorName(Anchor anchor) {
        if ("process".equals(anchor.observationKind())) {
            return switch (anchor.eventType()) {
                case "STARTED" -> "CASE_CREATED";
                case "COMPLETED" -> "CASE_CLOSED";
                case "TERMINATED" -> "CASE_CANCELLED";
                default -> "PROCESS_" + anchor.eventType();
            };
        }
        return anchor.observationKind().replace('-', '_').toUpperCase(java.util.Locale.ROOT)
                + "_" + anchor.eventType();
    }
}
