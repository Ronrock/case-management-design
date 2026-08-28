package org.casemgmt.observation;

import org.casemgmt.domain.CaseIds;
import org.casemgmt.domain.CaseInstance;
import org.casemgmt.event.CaseEvent;
import org.casemgmt.event.EventPublisher;
import org.casemgmt.event.EventTypes;
import org.casemgmt.projection.ActivityObservation;
import org.casemgmt.projection.CaseProjectionPort;
import org.casemgmt.projection.ProcessCompletionObservation;
import org.casemgmt.projection.ProjectionEntityIdentity;
import org.casemgmt.projection.ProjectionOwnershipException;
import org.casemgmt.projection.ProcessProjectionResult;
import org.casemgmt.projection.TaskObservation;
import org.casemgmt.repo.AppliedObservationRepository;
import org.casemgmt.repo.LinkedProcessRepository;
import org.casemgmt.service.CanonicalPatch;
import org.casemgmt.service.CaseDataMappingService;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The single transaction-owning core service for accepted engine lifecycle facts.
 *
 * <p>Every dependency participates in this caller transaction. Failures deliberately propagate:
 * the claim insert and all earlier effects roll back, so replay obtains a fresh claim. Persisting
 * {@code FAILED} here would roll back with the same transaction and is therefore intentionally
 * not attempted. No operation in this class uses {@code REQUIRES_NEW}.
 */
public class DefaultEngineObservationHandler implements EngineObservationHandler {

    private final AppliedObservationRepository claims;
    private final org.casemgmt.repo.CaseRepository cases;
    private final LinkedProcessRepository processes;
    private final CaseProjectionPort projections;
    private final CaseDataMappingService mappings;
    private final EventPublisher events;
    private final SlaLifecyclePort sla;
    private final EngineObservationAuthorityValidator authority;
    private final ObservationSecurityTelemetry securityTelemetry;

    public DefaultEngineObservationHandler(
            AppliedObservationRepository claims,
            org.casemgmt.repo.CaseRepository cases,
            LinkedProcessRepository processes,
            CaseProjectionPort projections,
            CaseDataMappingService mappings,
            EventPublisher events,
            SlaLifecyclePort sla,
            EngineObservationAuthorityValidator authority,
            ObservationSecurityTelemetry securityTelemetry) {
        this.claims = Objects.requireNonNull(claims, "claims");
        this.cases = Objects.requireNonNull(cases, "cases");
        this.processes = Objects.requireNonNull(processes, "processes");
        this.projections = Objects.requireNonNull(projections, "projections");
        this.mappings = Objects.requireNonNull(mappings, "mappings");
        this.events = Objects.requireNonNull(events, "events");
        this.sla = Objects.requireNonNull(sla, "sla");
        this.authority = Objects.requireNonNull(authority, "authority");
        this.securityTelemetry = Objects.requireNonNull(securityTelemetry, "securityTelemetry");
    }

    @Override
    @Transactional
    public ApplyResult apply(EngineObservation observation) {
        Objects.requireNonNull(observation, "observation");
        cases.lockForObservation(observation.caseId());
        AppliedObservationRepository.ClaimResult claimed = claims.claim(observation);
        if (!claimed.ownsClaim()) {
            return new ApplyResult(observation.observationId(), ApplyStatus.DUPLICATE,
                    ApplyResult.UNCHANGED_CASE_VERSION, List.of());
        }
        AppliedObservationRepository.Claim ownership = claimed.claim().orElseThrow();

        CaseInstance caseInstance = cases.require(observation.caseId());
        java.util.Optional<AppliedObservationRepository.AppliedPosition> current;
        try {
            validateOwnership(observation, caseInstance);
            authority.validate(observation, caseInstance);
            ProjectionEntityIdentity entityIdentity = entityIdentity(observation);
            if (entityIdentity != null) {
                projections.assertEntityOwnership(entityIdentity);
            }
            current = claims.latestAppliedPosition(observation);
        } catch (ObservationAuthorityException rejected) {
            recordSecurityRejection(observation, rejected.reason());
            throw rejected;
        } catch (AppliedObservationRepository.ObservationOrderingModeException rejected) {
            recordSecurityRejection(observation,
                    ObservationRejectionReason.ORDERING_MODE_MISMATCH);
            throw rejected;
        } catch (AppliedObservationRepository.LegacyObservationHistoryException rejected) {
            recordSecurityRejection(observation,
                    ObservationRejectionReason.RECONCILIATION_REQUIRED);
            throw rejected;
        } catch (ProjectionOwnershipException rejected) {
            recordProjectionRejection(observation, rejected);
            throw rejected;
        }
        if (current.filter(position -> stale(observation, position)).isPresent()) {
            recordStale(observation, caseInstance, current.orElseThrow());
            claims.markIgnoredStale(ownership);
            return new ApplyResult(observation.observationId(), ApplyStatus.IGNORED_STALE,
                    caseInstance.version(), List.of());
        }

        ProjectionOutcome projection;
        try {
            projection = project(observation, caseInstance);
        } catch (ProjectionOwnershipException rejected) {
            recordProjectionRejection(observation, rejected);
            throw rejected;
        }
        long caseVersion = projection.caseVersion();
        List<CanonicalPatch.AuditChange> canonicalChanges = List.of();
        if (observation instanceof UserTaskObservation task
                && task.eventType() == UserTaskObservation.EventType.COMPLETED) {
            String taskDefinitionKey = requiredAttribute(task, "taskDefinitionKey");
            Map<String, Object> variables = objectAttribute(task, "variables");
            CanonicalPatch patch = mappings.mapTaskOutput(
                    task.caseId(), taskDefinitionKey, variables);
            CaseDataMappingService.PatchResult result = mappings.apply(patch);
            if (result.status() == CaseDataMappingService.PatchStatus.CONFLICT) {
                throw new IllegalStateException("canonical output conflict for case "
                        + task.caseId() + " at expected version "
                        + patch.expectedCaseVersion());
            }
            caseVersion = result.caseVersion();
            canonicalChanges = patch.auditSummary();
        }

        sla.observeAnchor(anchor(observation));
        if (projection.rootTerminalState() != null) {
            sla.terminalizeRoot(observation.caseId(), projection.rootTerminalState(),
                    observation.engineOccurredAt());
        }

        recordApplied(observation, caseInstance, canonicalChanges);
        CaseEvent event = event(observation, caseInstance, projection.rootTerminalState());
        events.publish(event);
        claims.markApplied(ownership);
        return new ApplyResult(observation.observationId(), ApplyStatus.APPLIED, caseVersion,
                List.of(event.id()));
    }

    private void validateOwnership(EngineObservation observation, CaseInstance caseInstance) {
        if (!Objects.equals(observation.tenantId(), caseInstance.tenantId())) {
            throw new ObservationAuthorityException(
                    ObservationRejectionReason.TENANT_MISMATCH, observation.caseId());
        }
        boolean root = Objects.equals(caseInstance.rootProcessInstanceId(),
                observation.processInstanceId());
        boolean linked = processes.findByCase(caseInstance.id()).stream()
                .anyMatch(process -> Objects.equals(process.processInstanceId(),
                        observation.processInstanceId()));
        if (!root && !linked) {
            throw new ObservationAuthorityException(
                    ObservationRejectionReason.PROCESS_NOT_LINKED, observation.caseId());
        }
    }

    private void recordSecurityRejection(EngineObservation observation,
                                         ObservationRejectionReason reason) {
        securityTelemetry.rejected(new ObservationSecurityTelemetry.Rejection(
                observation.caseId(), observation.processInstanceId(),
                observation.entityId(), reason));
    }

    private void recordProjectionRejection(EngineObservation observation,
                                           ProjectionOwnershipException rejected) {
        recordSecurityRejection(observation,
                rejected.classification() == ProjectionOwnershipException.Classification.INSERT_COLLISION
                        ? ObservationRejectionReason.PROJECTION_COLLISION
                        : ObservationRejectionReason.ENTITY_OWNERSHIP);
    }

    private ProjectionOutcome project(EngineObservation observation, CaseInstance caseInstance) {
        if (observation instanceof ProcessObservation process) {
            return projectProcess(process, caseInstance);
        }
        if (observation instanceof UserTaskObservation task) {
            projections.observe(taskProjection(task));
            return new ProjectionOutcome(caseInstance.version(), null);
        }
        if (observation instanceof ActivityLifecycleObservation activity) {
            projections.observe(activityProjection(activity));
            return new ProjectionOutcome(caseInstance.version(), null);
        }
        MilestoneObservation milestone = (MilestoneObservation) observation;
        projections.observe(milestoneProjection(milestone));
        return new ProjectionOutcome(caseInstance.version(), null);
    }

    private ProjectionOutcome projectProcess(ProcessObservation process,
                                             CaseInstance caseInstance) {
        if (process.eventType() != ProcessObservation.EventType.COMPLETED
                && process.eventType() != ProcessObservation.EventType.TERMINATED) {
            // Existing lower-level projection support has no process-start row mutation. The
            // linked row is already ACTIVE when the process is correlated; the accepted anchor,
            // audit and event are still applied atomically.
            return new ProjectionOutcome(caseInstance.version(), null);
        }
        boolean cancelled = process.eventType() == ProcessObservation.EventType.TERMINATED;
        ProcessProjectionResult result = projections.observeFromHandler(new ProcessCompletionObservation(process.caseId(),
                process.processInstanceId(), optionalString(process, "processDefinitionKey"),
                cancelled ? "cancelled" : "completed", at(process.engineOccurredAt()),
                at(process.receivedAt())));
        if (!Objects.equals(caseInstance.rootProcessInstanceId(), process.processInstanceId())) {
            return new ProjectionOutcome(result.caseVersion(), null);
        }
        if (!result.rootTransitioned()) {
            return new ProjectionOutcome(result.caseVersion(), null);
        }
        SlaLifecyclePort.TerminalState terminal = cancelled
                ? SlaLifecyclePort.TerminalState.CANCELLED
                : SlaLifecyclePort.TerminalState.COMPLETED;
        return new ProjectionOutcome(result.caseVersion(), terminal);
    }

    private static TaskObservation taskProjection(UserTaskObservation task) {
        String taskDefinitionKey = optionalString(task, "taskDefinitionKey");
        String activityInstanceId = optionalString(task, "activityInstanceId");
        return new TaskObservation(task.caseId(), task.processInstanceId(), task.entityId(),
                activityInstanceId == null ? task.entityId() : activityInstanceId,
                taskDefinitionKey, optionalString(task, "name"), taskEvent(task.eventType()),
                optionalString(task, "assignee"), stringList(task, "candidateGroups"),
                optionalString(task, "formKey"), integer(task, "priority"),
                offsetDateTime(task, "dueAt"), at(task.engineOccurredAt()), at(task.receivedAt()));
    }

    private static ActivityObservation activityProjection(
            ActivityLifecycleObservation activity) {
        return new ActivityObservation(activity.caseId(), activity.processInstanceId(),
                activity.entityId(),
                optionalString(activity, "activityId"), optionalString(activity, "name"),
                ActivityObservation.Kind.STAGE, null, activityEvent(activity.eventType()),
                at(activity.engineOccurredAt()), at(activity.receivedAt()));
    }

    private static ActivityObservation milestoneProjection(MilestoneObservation milestone) {
        return new ActivityObservation(milestone.caseId(), milestone.processInstanceId(),
                milestone.entityId(),
                optionalString(milestone, "activityId"), optionalString(milestone, "name"),
                ActivityObservation.Kind.MILESTONE, optionalString(milestone, "milestoneId"),
                milestoneEvent(milestone.eventType()), at(milestone.engineOccurredAt()),
                at(milestone.receivedAt()));
    }

    private static String taskEvent(UserTaskObservation.EventType event) {
        return switch (event) {
            case CREATED -> "create";
            case CLAIMED -> "claim";
            case UNCLAIMED -> "unclaim";
            case ASSIGNED -> "assignment";
            case COMPLETED -> "complete";
            case DELETED -> "delete";
        };
    }

    private static ProjectionEntityIdentity entityIdentity(EngineObservation observation) {
        if (observation instanceof UserTaskObservation task) {
            String activityInstanceId = optionalString(task, "activityInstanceId");
            return new ProjectionEntityIdentity(task.caseId(), task.processInstanceId(),
                    ProjectionEntityIdentity.Kind.USER_TASK, task.entityId(),
                    activityInstanceId == null ? task.entityId() : activityInstanceId);
        }
        if (observation instanceof ActivityLifecycleObservation activity) {
            return new ProjectionEntityIdentity(activity.caseId(), activity.processInstanceId(),
                    ProjectionEntityIdentity.Kind.ACTIVITY, activity.entityId(), null);
        }
        if (observation instanceof MilestoneObservation milestone) {
            return new ProjectionEntityIdentity(milestone.caseId(), milestone.processInstanceId(),
                    ProjectionEntityIdentity.Kind.MILESTONE, milestone.entityId(), null);
        }
        return null;
    }

    private static String activityEvent(ActivityLifecycleObservation.EventType event) {
        return switch (event) {
            case STARTED -> "start";
            case COMPLETED -> "end";
            case CANCELLED -> "delete";
        };
    }

    private static String milestoneEvent(MilestoneObservation.EventType event) {
        return switch (event) {
            case REACHED -> "end";
            case REOPENED -> "start";
            case CANCELLED -> "delete";
        };
    }

    private static boolean stale(EngineObservation incoming,
                                 AppliedObservationRepository.AppliedPosition current) {
        if (incoming.entityRevision() != null && current.entityRevision() != null) {
            return incoming.entityRevision() <= current.entityRevision();
        }
        return !incoming.engineOccurredAt().isAfter(current.engineOccurredAt());
    }

    private void recordApplied(EngineObservation observation, CaseInstance caseInstance,
                               List<CanonicalPatch.AuditChange> canonicalChanges) {
        Map<String, Object> after = safeMetadata(observation, ApplyStatus.APPLIED);
        if (!canonicalChanges.isEmpty()) {
            after.put("canonicalChanges", canonicalChanges);
        }
        events.audit(observation.caseId(), caseInstance.tenantId(), "engine",
                action(observation), resourceType(observation), observation.entityId(),
                Map.of("status", "RECEIVED"), after);
    }

    private void recordStale(EngineObservation observation, CaseInstance caseInstance,
                             AppliedObservationRepository.AppliedPosition current) {
        Map<String, Object> after = safeMetadata(observation, ApplyStatus.IGNORED_STALE);
        after.put("currentObservationId", current.observationId());
        if (current.entityRevision() != null) {
            after.put("currentEntityRevision", current.entityRevision());
        }
        after.put("currentEngineOccurredAt", current.engineOccurredAt().toString());
        after.put("currentEventType", current.eventType());
        events.audit(observation.caseId(), caseInstance.tenantId(), "engine",
                "engine.observation.ignored-stale", resourceType(observation),
                observation.entityId(), Map.of("status", "RECEIVED"), after);
    }

    private static Map<String, Object> safeMetadata(EngineObservation observation,
                                                     ApplyStatus status) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", status.name());
        data.put("observationId", observation.observationId());
        data.put("source", observation.source());
        data.put("processInstanceId", observation.processInstanceId());
        data.put("entityId", observation.entityId());
        if (observation.entityRevision() != null) {
            data.put("entityRevision", observation.entityRevision());
        }
        data.put("eventType", observation.eventType().name());
        data.put("engineOccurredAt", observation.engineOccurredAt().toString());
        return data;
    }

    private static CaseEvent event(EngineObservation observation, CaseInstance caseInstance,
                                   SlaLifecyclePort.TerminalState rootTerminal) {
        String type = eventType(observation, rootTerminal);
        return new CaseEvent(CaseIds.newId(), observation.source(), type,
                observation.caseId(), caseInstance.tenantId(), at(observation.engineOccurredAt()),
                Map.copyOf(safeMetadata(observation, ApplyStatus.APPLIED)));
    }

    private static String eventType(EngineObservation observation,
                                    SlaLifecyclePort.TerminalState rootTerminal) {
        if (rootTerminal == SlaLifecyclePort.TerminalState.COMPLETED) {
            return EventTypes.CASE_CLOSED;
        }
        if (rootTerminal == SlaLifecyclePort.TerminalState.CANCELLED) {
            return EventTypes.CASE_CANCELLED;
        }
        if (observation instanceof UserTaskObservation task) {
            return switch (task.eventType()) {
                case CREATED -> EventTypes.TASK_CREATED;
                case CLAIMED -> EventTypes.TASK_CLAIMED;
                case COMPLETED -> EventTypes.TASK_COMPLETED;
                default -> "case.task.transitioned";
            };
        }
        if (observation instanceof MilestoneObservation milestone
                && milestone.eventType() == MilestoneObservation.EventType.REACHED) {
            return EventTypes.MILESTONE_ACHIEVED;
        }
        if (observation instanceof ProcessObservation process
                && process.eventType() == ProcessObservation.EventType.STARTED) {
            return EventTypes.PROCESS_STARTED;
        }
        if (observation instanceof ProcessObservation) {
            return "case.process.transitioned";
        }
        return EventTypes.PLAN_ITEM_TRANSITIONED;
    }

    private static String action(EngineObservation observation) {
        return "engine." + kind(observation) + "."
                + observation.eventType().name().toLowerCase(java.util.Locale.ROOT);
    }

    private static String resourceType(EngineObservation observation) {
        if (observation instanceof ProcessObservation) return "Process";
        if (observation instanceof UserTaskObservation) return "UserTask";
        if (observation instanceof ActivityLifecycleObservation) return "Activity";
        return "Milestone";
    }

    private static SlaLifecyclePort.Anchor anchor(EngineObservation observation) {
        return new SlaLifecyclePort.Anchor(observation.caseId(), kind(observation),
                observation.eventType().name(), observation.entityId(),
                observation.engineOccurredAt());
    }

    private static String kind(EngineObservation observation) {
        if (observation instanceof ProcessObservation) return "process";
        if (observation instanceof UserTaskObservation) return "user-task";
        if (observation instanceof ActivityLifecycleObservation) return "activity";
        return "milestone";
    }

    private static String requiredAttribute(EngineObservation observation, String name) {
        String value = optionalString(observation, name);
        if (value == null) {
            throw new IllegalArgumentException("Observation attribute '" + name
                    + "' must be a nonblank string");
        }
        return value;
    }

    private static String optionalString(EngineObservation observation, String name) {
        Object value = observation.attributes().get(name);
        if (value == null) return null;
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("Observation attribute '" + name
                    + "' must be a nonblank string");
        }
        return text;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> objectAttribute(EngineObservation observation, String name) {
        Object value = observation.attributes().get(name);
        if (value == null) return Map.of();
        if (!(value instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("Observation attribute '" + name
                    + "' must be an object");
        }
        return (Map<String, Object>) value;
    }

    private static List<String> stringList(EngineObservation observation, String name) {
        Object value = observation.attributes().get(name);
        if (value == null) return List.of();
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException("Observation attribute '" + name
                    + "' must be an array of strings");
        }
        List<String> strings = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof String text)) {
                throw new IllegalArgumentException("Observation attribute '" + name
                        + "' must be an array of strings");
            }
            strings.add(text);
        }
        return List.copyOf(strings);
    }

    private static int integer(EngineObservation observation, String name) {
        Object value = observation.attributes().get(name);
        if (value == null) return 0;
        if (!(value instanceof BigDecimal decimal)) {
            throw new IllegalArgumentException("Observation attribute '" + name
                    + "' must be an integer");
        }
        try {
            return decimal.intValueExact();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Observation attribute '" + name
                    + "' must be an integer", exception);
        }
    }

    private static OffsetDateTime offsetDateTime(EngineObservation observation, String name) {
        String value = optionalString(observation, name);
        if (value == null) return null;
        try {
            return OffsetDateTime.parse(value);
        } catch (java.time.format.DateTimeParseException exception) {
            throw new IllegalArgumentException("Observation attribute '" + name
                    + "' must be an ISO offset date-time", exception);
        }
    }

    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private record ProjectionOutcome(long caseVersion,
                                     SlaLifecyclePort.TerminalState rootTerminalState) { }
}
