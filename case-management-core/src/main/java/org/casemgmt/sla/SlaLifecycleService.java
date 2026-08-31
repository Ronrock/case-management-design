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
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
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
        ValidatedCaseContract.SlaAnchor observedAnchor = anchorName(anchor);
        if (observedAnchor == null) return; // Process suspend/resume are observations, not SLA anchors.
        for (ValidatedCaseContract.SlaBindingDefinition binding : bound.contract().slaBindings()) {
            if (!matchesTarget(binding, anchor, instance)) {
                continue;
            }
            if (observedAnchor.equals(binding.startAnchor())) {
                createOccurrence(instance, bound, binding, anchor, observedAnchor);
            }
            applyAnchorTransition(instance, bound, binding, observedAnchor, anchor);
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
                                  ValidatedCaseContract.SlaAnchor observedAnchor) {
        if (binding.duration() == null) {
            throw new IllegalStateException("SLA binding '" + binding.id()
                    + "' uses dueDateExpression, which has no registered deterministic evaluator");
        }
        SlaCalendarCatalog.Revision calendarRevision = sla.require(instance.tenantId(),
                binding.calendarId(), binding.calendarRevision());
        Map<String, Object> calendarDefinition = calendarRevision.definition();
        BusinessCalendar calendar = BusinessCalendar.fromJson("SLA calendar '" + binding.calendarId()
                + "' revision " + binding.calendarRevision(), calendarDefinition);
        OffsetDateTime startedAt = OffsetDateTime.ofInstant(anchor.occurredAt(), ZoneOffset.UTC);
        Duration duration = Duration.parse(binding.duration());
        OffsetDateTime dueAt = calendar.addDuration(startedAt, duration);
        OffsetDateTime warnAt = binding.warnings().isEmpty() ? null
                : calendar.addDuration(startedAt, Duration.parse(binding.warnings().getFirst()));
        String occurrenceKey = occurrenceKey(binding, anchor);
        String hash = JsonCodec.sha256(bound.releaseId() + "|" + binding.id());
        String policyId = "cp-" + hash.substring(0, 40);
        String targetId = "ct-" + hash.substring(0, 40);
        sla.ensureContractTarget(policyId, targetId, binding.id(),
                binding.duration(), binding.warnings().isEmpty() ? null : binding.warnings().getFirst(),
                names(binding.breachActions()));
        String occurrenceHash = JsonCodec.sha256(instance.id() + "|" + bound.releaseId() + "|"
                + binding.id() + "|" + occurrenceKey);
        String occurrenceId = "so-" + occurrenceHash.substring(0, 40);
        boolean inserted = sla.insertContractOccurrenceIfAbsent(new SlaRepository.ContractOccurrence(
                occurrenceId, instance.id(), targetId, binding.id(),
                binding.targetVersion(), binding.scope().name(), occurrenceKey, bound.releaseId(),
                bound.sha256(), binding.calendarId(), binding.calendarRevision(),
                calendarRevision.sha256(), name(binding.meetAnchor()),
                name(binding.cancelAnchor()), startedAt, dueAt, warnAt,
                JsonCodec.canonicalJson(calendarDefinition), names(binding.pauseAnchors()),
                names(binding.resumeAnchors()),
                JsonCodec.toJson(Map.of("anchor", observedAnchor.name(), "occurredAt",
                        anchor.occurredAt().toString(), "transition", "STARTED"))));
        if (inserted) {
            Map<String, Object> data = Map.of("slaId", occurrenceId, "targetId", targetId,
                    "targetKey", binding.id(), "dueAt", dueAt.toString(),
                    "calendarRevision", binding.calendarRevision());
            events.publish(new CaseEvent(CaseIds.newId(), events.engineId(), EventTypes.SLA_STARTED,
                    instance.id(), instance.tenantId(), startedAt, data));
            events.audit(instance.id(), instance.tenantId(), "engine", "sla.start", "SlaRecord",
                    occurrenceId, null, data);
        }
    }

    private void applyAnchorTransition(CaseInstance instance,
                                       BoundSlaContractResolver.ResolvedContract bound,
                                       ValidatedCaseContract.SlaBindingDefinition binding,
                                       ValidatedCaseContract.SlaAnchor observedAnchor,
                                       Anchor anchor) {
        OffsetDateTime at = OffsetDateTime.ofInstant(anchor.occurredAt(), ZoneOffset.UTC);
        List<SlaRepository.ContractLifecycleRow> rows =
                binding.scope() == ValidatedCaseContract.SlaScope.OCCURRENCE
                        ? sla.contractLifecycleRows(instance.id(), bound.releaseId(), binding.id(),
                                occurrenceKey(binding, anchor))
                        : sla.contractLifecycleRows(instance.id(), bound.releaseId(), binding.id());
        for (SlaRepository.ContractLifecycleRow row : rows) {
            if (row.pauseAnchors().contains(observedAnchor.name())) {
                sla.pauseContractOccurrence(row, observedAnchor.name(), at)
                        .ifPresent(record -> emitTransition(instance, record, EventTypes.SLA_PAUSED,
                                "sla.pause", observedAnchor.name(), at));
            }
            if (row.resumeAnchors().contains(observedAnchor.name())
                    && "PAUSED".equals(row.record().status())) {
                BusinessCalendar calendar = BusinessCalendar.fromJson("SLA occurrence "
                        + row.record().id() + " calendar snapshot", JsonCodec.toMap(row.calendarDefinition()));
                OffsetDateTime pausedAt = row.record().pausedAt();
                OffsetDateTime dueAt = calendar.addDuration(at,
                        calendar.workingDurationBetween(pausedAt, row.record().dueAt()));
                OffsetDateTime warnAt = row.record().warnAt() == null ? null : calendar.addDuration(at,
                        calendar.workingDurationBetween(pausedAt, row.record().warnAt()));
                long pausedSeconds = Math.max(0, Duration.between(pausedAt, at).toSeconds());
                sla.resumeContractOccurrence(row, observedAnchor.name(), at, dueAt, warnAt,
                        row.record().pausedTotalSeconds() + pausedSeconds)
                        .ifPresent(record -> emitTransition(instance, record, EventTypes.SLA_RESUMED,
                                "sla.resume", observedAnchor.name(), at));
            }
            sla.terminalizeContractOccurrence(row, observedAnchor.name(), at)
                    .ifPresent(record -> emitTransition(instance, record,
                            "MET".equals(record.status()) ? EventTypes.SLA_MET
                                    : EventTypes.SLA_CANCELLED,
                            "MET".equals(record.status()) ? "sla.meet" : "sla.cancel",
                            observedAnchor.name(), at));
        }
    }

    private void emitTransition(CaseInstance instance, SlaRecord record, String eventType,
                                String auditAction, String anchor, OffsetDateTime occurredAt) {
        Map<String, Object> data = Map.of("slaId", record.id(), "targetId", record.targetId(),
                "anchor", anchor, "status", record.status(), "dueAt", String.valueOf(record.dueAt()));
        events.publish(new CaseEvent(CaseIds.newId(), events.engineId(), eventType, instance.id(),
                instance.tenantId(), occurredAt, data));
        events.audit(instance.id(), instance.tenantId(), "engine", auditAction, "SlaRecord",
                record.id(), null, data);
    }

    private static String occurrenceKey(ValidatedCaseContract.SlaBindingDefinition binding,
                                        Anchor anchor) {
        if (binding.occurrenceKey() != null) return binding.occurrenceKey() + ":" + anchor.entityId();
        return binding.scope() == ValidatedCaseContract.SlaScope.CASE ? "CASE" : anchor.entityId();
    }

    private static boolean matchesTarget(
            ValidatedCaseContract.SlaBindingDefinition binding, Anchor anchor,
            CaseInstance instance) {
        if (binding.scope() == ValidatedCaseContract.SlaScope.CASE) {
            return "process".equals(anchor.observationKind())
                    && anchor.slaTargetId() == null
                    && Objects.equals(instance.rootProcessInstanceId(), anchor.entityId());
        }
        return binding.id().equals(anchor.slaTargetId());
    }

    private static ValidatedCaseContract.SlaAnchor anchorName(Anchor anchor) {
        if ("process".equals(anchor.observationKind())) {
            return switch (anchor.eventType()) {
                case "STARTED" -> ValidatedCaseContract.SlaAnchor.CASE_CREATED;
                case "COMPLETED" -> ValidatedCaseContract.SlaAnchor.CASE_CLOSED;
                case "TERMINATED" -> ValidatedCaseContract.SlaAnchor.CASE_CANCELLED;
                default -> null;
            };
        }
        return ValidatedCaseContract.SlaAnchor.valueOf(
                anchor.observationKind().replace('-', '_').toUpperCase(java.util.Locale.ROOT)
                        + "_" + anchor.eventType());
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private static List<String> names(List<? extends Enum<?>> values) {
        return values.stream().map(Enum::name).toList();
    }
}
