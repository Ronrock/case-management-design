package org.casemgmt.observation;

import org.casemgmt.domain.CaseInstance;
import org.casemgmt.domain.CasePriority;
import org.casemgmt.domain.CaseState;
import org.casemgmt.domain.CaseTask;
import org.casemgmt.event.CaseEvent;
import org.casemgmt.event.EventPublisher;
import org.casemgmt.projection.ActivityObservation;
import org.casemgmt.projection.CaseProjectionPort;
import org.casemgmt.projection.ProcessCompletionObservation;
import org.casemgmt.projection.ProcessProjectionResult;
import org.casemgmt.projection.ProjectionStatus;
import org.casemgmt.projection.ProjectionEntityIdentity;
import org.casemgmt.projection.ProjectionOwnershipException;
import org.casemgmt.projection.TaskObservation;
import org.casemgmt.repo.AppliedObservationRepository;
import org.casemgmt.repo.CaseRepository;
import org.casemgmt.repo.LinkedProcessRepository;
import org.casemgmt.service.CanonicalPatch;
import org.casemgmt.service.CaseDataMappingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DefaultEngineObservationHandlerTest {

    private static final Instant OCCURRED = Instant.parse("2026-08-28T08:30:00Z");
    private static final Instant RECEIVED = Instant.parse("2026-08-28T08:30:05Z");

    private AppliedObservationRepository claims;
    private CaseRepository cases;
    private LinkedProcessRepository processes;
    private CaseProjectionPort projections;
    private CaseDataMappingService mappings;
    private EventPublisher events;
    private SlaLifecyclePort sla;
    private EngineObservationAuthorityValidator authority;
    private ObservationSecurityTelemetry securityTelemetry;
    private AppliedObservationRepository.Claim claim;
    private DefaultEngineObservationHandler handler;

    @BeforeEach
    void setUp() {
        claims = mock(AppliedObservationRepository.class);
        cases = mock(CaseRepository.class);
        processes = mock(LinkedProcessRepository.class);
        projections = mock(CaseProjectionPort.class);
        mappings = mock(CaseDataMappingService.class);
        events = mock(EventPublisher.class);
        sla = mock(SlaLifecyclePort.class);
        authority = mock(EngineObservationAuthorityValidator.class);
        securityTelemetry = mock(ObservationSecurityTelemetry.class);
        claim = mock(AppliedObservationRepository.Claim.class);
        handler = new DefaultEngineObservationHandler(
                claims, cases, processes, projections, mappings, events, sla,
                authority, securityTelemetry);
    }

    @Test
    void rootProcessCompletionAppliesEveryEffectInCallerTransactionOrder() {
        ProcessObservation observation = new ProcessObservation("obs-process", 1,
                "operaton:embedded", "tenant-a", "case-1", "process-1", "process-1", 11L,
                ProcessObservation.EventType.COMPLETED, OCCURRED, RECEIVED,
                authorityAttributes());
        owningClaim(observation, activeCase("tenant-a", "process-1", 7));
        ProcessCompletionObservation completion = new ProcessCompletionObservation(
                "case-1", "process-1", "claim-process", "completed", at(OCCURRED), at(RECEIVED));
        when(projections.observeFromHandler(completion))
                .thenReturn(new ProcessProjectionResult(true, 8));

        ApplyResult result = handler.apply(observation);

        ArgumentCaptor<CaseEvent> event = ArgumentCaptor.forClass(CaseEvent.class);
        InOrder order = inOrder(claims, cases, processes, authority, projections, sla, events);
        order.verify(cases).lockForObservation("case-1");
        order.verify(claims).claim(observation);
        order.verify(cases).require("case-1");
        order.verify(processes).findByCase("case-1");
        order.verify(authority).validate(observation, activeCase("tenant-a", "process-1", 7));
        order.verify(claims).latestAppliedPosition(observation);
        order.verify(projections).observeFromHandler(completion);
        order.verify(sla).observeAnchor(new SlaLifecyclePort.Anchor(
                "case-1", "process", "COMPLETED", "process-1", OCCURRED));
        order.verify(sla).terminalizeRoot("case-1", SlaLifecyclePort.TerminalState.COMPLETED, OCCURRED);
        order.verify(events).audit(eq("case-1"), eq("tenant-a"), eq("engine"),
                eq("engine.process.completed"), eq("Process"), eq("process-1"), any(),
                argThat(value -> value.toString().contains("APPLIED")
                        && !value.toString().contains("claim-process")));
        order.verify(events).publish(event.capture());
        order.verify(claims).markApplied(claim);

        assertThat(event.getValue().type()).isEqualTo("case.closed");
        assertThat(result).isEqualTo(new ApplyResult("obs-process", ApplyStatus.APPLIED, 8,
                List.of(event.getValue().id())));
        verifyNoInteractions(mappings);
    }

    @Test
    void rootTerminationPublishesOnlyTheWhitelistedCancellationReason() {
        ProcessObservation observation = new ProcessObservation("obs-cancel", 1,
                "operaton:embedded", "tenant-a", "case-1", "process-1", "process-1", 12L,
                ProcessObservation.EventType.TERMINATED, OCCURRED, RECEIVED,
                authorityAttributes("cancellationReason", "customer withdrew",
                        "secret", "must-not-leak", "arbitrary", "must-not-leak-either"));
        owningClaim(observation, activeCase("tenant-a", "process-1", 7));
        when(projections.observeFromHandler(any(ProcessCompletionObservation.class)))
                .thenReturn(new ProcessProjectionResult(true, 8));

        handler.apply(observation);

        ArgumentCaptor<CaseEvent> event = ArgumentCaptor.forClass(CaseEvent.class);
        verify(events).publish(event.capture());
        assertThat(event.getValue().type()).isEqualTo("case.cancelled");
        assertThat(event.getValue().data())
                .containsEntry("reason", "customer withdrew")
                .doesNotContainKey("cancellationReason")
                .doesNotContainKey("secret")
                .doesNotContainKey("arbitrary");
        assertThat(event.getValue().data().toString()).doesNotContain("must-not-leak");
    }

    @Test
    void rootTerminationWithoutAUserReasonMatchesTheServiceEmptyReasonPayload() {
        ProcessObservation observation = new ProcessObservation("obs-cancel-no-reason", 1,
                "operaton:embedded", "tenant-a", "case-1", "process-1", "process-1", 12L,
                ProcessObservation.EventType.TERMINATED, OCCURRED, RECEIVED,
                authorityAttributes());
        owningClaim(observation, activeCase("tenant-a", "process-1", 7));
        when(projections.observeFromHandler(any(ProcessCompletionObservation.class)))
                .thenReturn(new ProcessProjectionResult(true, 8));

        handler.apply(observation);

        ArgumentCaptor<CaseEvent> event = ArgumentCaptor.forClass(CaseEvent.class);
        verify(events).publish(event.capture());
        assertThat(event.getValue().data()).containsEntry("reason", "");
    }

    @Test
    void childTerminationDoesNotExposeCancellationReason() {
        ProcessObservation observation = new ProcessObservation("obs-child-cancel", 1,
                "operaton:embedded", "tenant-a", "case-1", "child-1", "child-1", 12L,
                ProcessObservation.EventType.TERMINATED, OCCURRED, RECEIVED,
                authorityAttributes("cancellationReason", "child detail"));
        owningClaim(observation, activeCase("tenant-a", "process-1", 7));
        when(processes.findByCase("case-1")).thenReturn(List.of(
                rootLink("process-1"),
                new LinkedProcessRepository.LinkedProcessRow("link-child", "case-1", null,
                        "link-child", "child-1", "child-process", "ACTIVE",
                        CaseTask.EngineSync.SYNCED, false)));
        when(projections.observeFromHandler(any(ProcessCompletionObservation.class)))
                .thenReturn(new ProcessProjectionResult(false, 7));

        handler.apply(observation);

        ArgumentCaptor<CaseEvent> event = ArgumentCaptor.forClass(CaseEvent.class);
        verify(events).publish(event.capture());
        assertThat(event.getValue().type()).isEqualTo("case.process.transitioned");
        assertThat(event.getValue().data()).doesNotContainKey("reason");
    }

    @Test
    void completedUserTaskProjectsThenMapsOnlyApprovedOutputBeforeLifecycleEffects() {
        UserTaskObservation observation = new UserTaskObservation("obs-task", 1,
                "operaton:embedded", "tenant-a", "case-1", "process-1", "task-1", 5L,
                UserTaskObservation.EventType.COMPLETED, OCCURRED, RECEIVED, authorityAttributes(
                "activityInstanceId", "activity-1",
                "taskDefinitionKey", "review",
                "name", "Review claim",
                "assignee", "alice",
                "candidateGroups", List.of("reviewers"),
                "formKey", "review-form",
                "priority", 70,
                "dueAt", "2026-08-29T10:00:00Z",
                "variables", Map.of("decision", "approved", "secret", "raw-secret")));
        owningClaim(observation, activeCase("tenant-a", "process-1", 7));
        CanonicalPatch patch = new CanonicalPatch("case-1", "review", 7, List.of(
                new CanonicalPatch.FieldChange("/mappings/0", "decision", "decision",
                        CanonicalPatch.WriteMode.REPLACE, false, null, "approved", false)));
        when(mappings.mapTaskOutput("case-1", "review",
                Map.of("decision", "approved", "secret", "raw-secret"))).thenReturn(patch);
        when(mappings.apply(patch)).thenReturn(CaseDataMappingService.PatchResult.applied(8));

        ApplyResult result = handler.apply(observation);

        ArgumentCaptor<CaseEvent> event = ArgumentCaptor.forClass(CaseEvent.class);
        InOrder order = inOrder(claims, cases, processes, authority, projections, mappings, sla, events);
        order.verify(cases).lockForObservation("case-1");
        order.verify(claims).claim(observation);
        order.verify(cases).require("case-1");
        order.verify(processes).findByCase("case-1");
        order.verify(authority).validate(observation, activeCase("tenant-a", "process-1", 7));
        order.verify(projections).assertEntityOwnership(new ProjectionEntityIdentity(
                "case-1", "process-1", ProjectionEntityIdentity.Kind.USER_TASK,
                "task-1", "activity-1"));
        order.verify(claims).latestAppliedPosition(observation);
        order.verify(projections).observe(new TaskObservation("case-1", "process-1",
                "task-1", "activity-1",
                "review", "Review claim", "complete", "alice", List.of("reviewers"),
                "review-form", 70, OffsetDateTime.parse("2026-08-29T10:00:00Z"),
                at(OCCURRED), at(RECEIVED)));
        order.verify(mappings).mapTaskOutput("case-1", "review",
                Map.of("decision", "approved", "secret", "raw-secret"));
        order.verify(mappings).apply(patch);
        order.verify(sla).observeAnchor(new SlaLifecyclePort.Anchor(
                "case-1", "user-task", "COMPLETED", "task-1", OCCURRED));
        order.verify(events).audit(eq("case-1"), eq("tenant-a"), eq("engine"),
                eq("engine.user-task.completed"), eq("UserTask"), eq("task-1"), any(),
                argThat(value -> value.toString().contains("decision")
                        && !value.toString().contains("raw-secret")));
        order.verify(events).publish(event.capture());
        order.verify(claims).markApplied(claim);

        assertThat(event.getValue().type()).isEqualTo("case.task.completed");
        assertThat(event.getValue().data().toString()).doesNotContain("raw-secret");
        assertThat(result).isEqualTo(new ApplyResult("obs-task", ApplyStatus.APPLIED, 8,
                List.of(event.getValue().id())));
    }

    @Test
    void rootCompletionWithoutAuthoritativeTransitionDoesNotEmitTerminalEffects() {
        ProcessObservation observation = processObservation("obs-already-terminal", 12L, OCCURRED);
        owningClaim(observation, activeCase("tenant-a", "process-1", 8));
        when(projections.observeFromHandler(any(ProcessCompletionObservation.class)))
                .thenReturn(new ProcessProjectionResult(false, 8));

        ApplyResult result = handler.apply(observation);

        ArgumentCaptor<CaseEvent> event = ArgumentCaptor.forClass(CaseEvent.class);
        verify(sla, never()).terminalizeRoot(any(), any(), any());
        verify(events).publish(event.capture());
        assertThat(event.getValue().type()).isEqualTo("case.process.transitioned");
        assertThat(result.caseVersion()).isEqualTo(8);
    }

    @Test
    void nonCompletedUserTaskNeverMapsOutput() {
        UserTaskObservation observation = new UserTaskObservation("obs-claim", 1,
                "operaton:embedded", null, "case-1", "process-1", "task-1", 4L,
                UserTaskObservation.EventType.CLAIMED, OCCURRED, RECEIVED,
                authorityAttributes("taskDefinitionKey", "review", "assignee", "alice",
                        "variables", Map.of("decision", "must-not-map")));
        owningClaim(observation, activeCase(null, "process-1", 3));

        ApplyResult result = handler.apply(observation);

        verify(projections).observe(argThat((TaskObservation projected) ->
                projected.eventName().equals("claim") && projected.assignee().equals("alice")));
        verifyNoInteractions(mappings);
        assertThat(result.status()).isEqualTo(ApplyStatus.APPLIED);
        assertThat(result.caseVersion()).isEqualTo(3);
    }

    @Test
    void activityLifecycleUsesTheStageProjectionAndExactEffectOrder() {
        ActivityLifecycleObservation observation = new ActivityLifecycleObservation("obs-stage", 1,
                "operaton:embedded", "tenant-a", "case-1", "process-1", "stage-instance", null,
                ActivityLifecycleObservation.EventType.COMPLETED, OCCURRED, RECEIVED,
                authorityAttributes("activityId", "assessment", "name", "Assessment"));
        owningClaim(observation, activeCase("tenant-a", "process-1", 7));

        ApplyResult result = handler.apply(observation);

        InOrder order = normalOrder(observation);
        order.verify(projections).observe(new ActivityObservation("case-1", "process-1",
                "stage-instance",
                "assessment", "Assessment", ActivityObservation.Kind.STAGE, null, "end",
                at(OCCURRED), at(RECEIVED)));
        order.verify(sla).observeAnchor(new SlaLifecyclePort.Anchor(
                "case-1", "activity", "COMPLETED", "stage-instance", OCCURRED));
        CaseEvent event = verifyAuditEventMarker(
                order, "engine.activity.completed", "Activity", "stage-instance");
        assertThat(result).isEqualTo(new ApplyResult("obs-stage", ApplyStatus.APPLIED, 7,
                List.of(event.id())));
        verifyNoInteractions(mappings);
    }

    @Test
    void milestoneUsesTheMilestoneProjectionAndExactEffectOrder() {
        MilestoneObservation observation = new MilestoneObservation("obs-milestone", 1,
                "operaton:embedded", "tenant-a", "case-1", "process-1", "milestone-instance", 2L,
                MilestoneObservation.EventType.REACHED, OCCURRED, RECEIVED,
                authorityAttributes("activityId", "accepted", "milestoneId", "accepted",
                        "name", "Claim accepted"));
        owningClaim(observation, activeCase("tenant-a", "process-1", 7));

        ApplyResult result = handler.apply(observation);

        InOrder order = normalOrder(observation);
        order.verify(projections).observe(new ActivityObservation("case-1", "process-1",
                "milestone-instance",
                "accepted", "Claim accepted", ActivityObservation.Kind.MILESTONE, "accepted", "end",
                at(OCCURRED), at(RECEIVED)));
        order.verify(sla).observeAnchor(new SlaLifecyclePort.Anchor(
                "case-1", "milestone", "REACHED", "milestone-instance", OCCURRED));
        CaseEvent event = verifyAuditEventMarker(
                order, "engine.milestone.reached", "Milestone", "milestone-instance");
        assertThat(result).isEqualTo(new ApplyResult("obs-milestone", ApplyStatus.APPLIED, 7,
                List.of(event.id())));
        verifyNoInteractions(mappings);
    }

    @ParameterizedTest
    @EnumSource(value = MilestoneObservation.EventType.class,
            names = {"REOPENED", "CANCELLED"})
    void milestoneNonReachedVariantsProjectTheirExactLifecycle(MilestoneObservation.EventType type) {
        MilestoneObservation observation = new MilestoneObservation("obs-" + type, 1,
                "operaton:embedded", "tenant-a", "case-1", "process-1",
                "milestone-instance", 3L, type, OCCURRED, RECEIVED,
                authorityAttributes("activityId", "accepted", "milestoneId", "accepted", "name", "Accepted"));
        owningClaim(observation, activeCase("tenant-a", "process-1", 7));

        assertThat(handler.apply(observation).status()).isEqualTo(ApplyStatus.APPLIED);

        verify(projections).observe(new ActivityObservation("case-1", "process-1",
                "milestone-instance", "accepted", "Accepted",
                ActivityObservation.Kind.MILESTONE, "accepted",
                type == MilestoneObservation.EventType.REOPENED ? "start" : "delete",
                at(OCCURRED), at(RECEIVED)));
        verify(sla).observeAnchor(new SlaLifecyclePort.Anchor("case-1", "milestone",
                type.name(), "milestone-instance", OCCURRED));
        verifyNoInteractions(mappings);
    }

    @Test
    void duplicateLocksTheCaseBeforeClaimButDoesNotReadOrChangeBusinessState() {
        ProcessObservation observation = processObservation("obs-duplicate", 11L, OCCURRED);
        when(claims.claim(observation)).thenReturn(AppliedObservationRepository.ClaimResult.duplicate());

        ApplyResult result = handler.apply(observation);

        assertThat(result).isEqualTo(new ApplyResult("obs-duplicate", ApplyStatus.DUPLICATE,
                ApplyResult.UNCHANGED_CASE_VERSION, List.of()));
        verify(cases).lockForObservation("case-1");
        verify(cases, never()).require(any());
        verifyNoInteractions(processes, projections, mappings, events, sla,
                authority, securityTelemetry);
        verify(claims, never()).latestAppliedPosition(any());
        verify(claims, never()).markApplied(any());
        verify(claims, never()).markFailed(any(), any());
    }

    @Test
    void tenantAndProcessOwnershipAreValidatedNullSafelyBeforeBusinessEffects() {
        ProcessObservation wrongTenant = processObservation("obs-wrong-tenant", 1L, OCCURRED);
        when(claims.claim(wrongTenant)).thenReturn(owned());
        when(cases.require("case-1")).thenReturn(activeCase("tenant-b", "process-1", 7));

        assertThatThrownBy(() -> handler.apply(wrongTenant))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("tenant");
        verifyNoInteractions(processes, projections, mappings, events, sla);
        verify(securityTelemetry).rejected(new ObservationSecurityTelemetry.Rejection(
                "case-1", "process-1", "process-1",
                ObservationRejectionReason.TENANT_MISMATCH));
        verify(claims, never()).markApplied(any());

        ProcessObservation tenantlessWrongProcess = new ProcessObservation("obs-wrong-process", 1,
                "operaton:embedded", null, "case-1", "other-process", "other-process", 1L,
                ProcessObservation.EventType.STARTED, OCCURRED, RECEIVED, authorityAttributes());
        when(claims.claim(tenantlessWrongProcess)).thenReturn(owned());
        when(cases.require("case-1")).thenReturn(activeCase(null, "process-1", 7));
        when(processes.findByCase("case-1")).thenReturn(List.of(rootLink("process-1")));

        assertThatThrownBy(() -> handler.apply(tenantlessWrongProcess))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("process");
        verify(claims, never()).latestAppliedPosition(tenantlessWrongProcess);
    }

    @Test
    void foreignTaskEntityOwnershipIsRejectedBeforeWatermarkOrBusinessEffects() {
        UserTaskObservation observation = new UserTaskObservation("obs-foreign-task", 1,
                "operaton:embedded", "tenant-a", "case-1", "process-1", "task-owned-by-b", 3L,
                UserTaskObservation.EventType.CREATED, OCCURRED, RECEIVED,
                authorityAttributes("taskDefinitionKey", "review",
                        "activityInstanceId", "activity-owned-by-b"));
        owningClaim(observation, activeCase("tenant-a", "process-1", 7));
        ProjectionEntityIdentity identity = new ProjectionEntityIdentity("case-1", "process-1",
                ProjectionEntityIdentity.Kind.USER_TASK, "task-owned-by-b", "activity-owned-by-b");
        org.mockito.Mockito.doThrow(new ProjectionOwnershipException(
                        ProjectionOwnershipException.Classification.CROSS_OWNER))
                .when(projections).assertEntityOwnership(identity);

        assertThatThrownBy(() -> handler.apply(observation))
                .isInstanceOf(ProjectionOwnershipException.class);

        verify(projections).assertEntityOwnership(identity);
        verify(securityTelemetry).rejected(new ObservationSecurityTelemetry.Rejection(
                "case-1", "process-1", "task-owned-by-b",
                ObservationRejectionReason.ENTITY_OWNERSHIP));
        verify(claims, never()).latestAppliedPosition(observation);
        verify(projections, never()).observe(any(TaskObservation.class));
        verifyNoInteractions(mappings, events, sla);
        verify(claims, never()).markApplied(any());
    }

    @Test
    void projectionRaceCollisionIsTelemeteredButDatabaseOutageIsNotSecurity() {
        UserTaskObservation collision = new UserTaskObservation("obs-collision", 1,
                "operaton:embedded", "tenant-a", "case-1", "process-1", "task-1", 3L,
                UserTaskObservation.EventType.CREATED, OCCURRED, RECEIVED,
                authorityAttributes("taskDefinitionKey", "review", "activityInstanceId", "activity-1"));
        owningClaim(collision, activeCase("tenant-a", "process-1", 7));
        org.mockito.Mockito.doThrow(new ProjectionOwnershipException(
                        ProjectionOwnershipException.Classification.INSERT_COLLISION))
                .when(projections).observe(any(TaskObservation.class));

        assertThatThrownBy(() -> handler.apply(collision))
                .isInstanceOf(ProjectionOwnershipException.class);
        verify(securityTelemetry).rejected(new ObservationSecurityTelemetry.Rejection(
                "case-1", "process-1", "task-1",
                ObservationRejectionReason.PROJECTION_COLLISION));

        reset(securityTelemetry, projections);
        UserTaskObservation outage = new UserTaskObservation("obs-outage", 1,
                "operaton:embedded", "tenant-a", "case-1", "process-1", "task-2", 3L,
                UserTaskObservation.EventType.CREATED, OCCURRED, RECEIVED,
                authorityAttributes("taskDefinitionKey", "review", "activityInstanceId", "activity-2"));
        owningClaim(outage, activeCase("tenant-a", "process-1", 7));
        var unavailable = new org.springframework.dao.DataAccessResourceFailureException(
                "database unavailable");
        org.mockito.Mockito.doThrow(unavailable).when(projections)
                .assertEntityOwnership(any(ProjectionEntityIdentity.class));

        assertThatThrownBy(() -> handler.apply(outage)).isSameAs(unavailable);
        verifyNoInteractions(securityTelemetry);
    }

    @Test
    void lowerRevisionIsIgnoredEvenWhenItsTimestampIsNewerAndItsAuditIsSafe() {
        UserTaskObservation observation = new UserTaskObservation("obs-stale", 1,
                "operaton:remote", "tenant-a", "case-1", "process-1", "task-1", 6L,
                UserTaskObservation.EventType.CLAIMED, OCCURRED.plusSeconds(30), RECEIVED,
                authorityAttributes("assignee", "mallory", "secret", "must-not-leak"));
        owningClaim(observation, activeCase("tenant-a", "process-1", 7));
        var current = new AppliedObservationRepository.AppliedPosition(
                "obs-newer", 7L, OCCURRED, "COMPLETED");
        when(claims.latestAppliedPosition(observation)).thenReturn(Optional.of(current));

        ApplyResult result = handler.apply(observation);

        InOrder order = normalOrder(observation);
        order.verify(events).audit(eq("case-1"), eq("tenant-a"), eq("engine"),
                eq("engine.observation.ignored-stale"), eq("UserTask"), eq("task-1"), any(),
                argThat(value -> value.toString().contains("IGNORED_STALE")
                        && value.toString().contains("obs-newer")
                        && !value.toString().contains("mallory")
                        && !value.toString().contains("must-not-leak")));
        order.verify(claims).markIgnoredStale(claim);

        assertThat(result).isEqualTo(new ApplyResult("obs-stale", ApplyStatus.IGNORED_STALE,
                7, List.of()));
        verify(projections).assertEntityOwnership(new ProjectionEntityIdentity(
                "case-1", "process-1", ProjectionEntityIdentity.Kind.USER_TASK,
                "task-1", "task-1"));
        verify(projections, never()).observe(any(TaskObservation.class));
        verifyNoInteractions(mappings, sla);
        verify(events, never()).publish(any());
    }

    @Test
    void olderTimestampIsIgnoredWhenNoStableRevisionExists() {
        ProcessObservation observation = processObservation("obs-stale-time", null, OCCURRED);
        owningClaim(observation, activeCase("tenant-a", "process-1", 7));
        when(claims.latestAppliedPosition(observation)).thenReturn(Optional.of(
                new AppliedObservationRepository.AppliedPosition(
                        "obs-newer-time", null, OCCURRED.plusSeconds(1), "STARTED")));

        assertThat(handler.apply(observation).status()).isEqualTo(ApplyStatus.IGNORED_STALE);

        verifyNoInteractions(projections, mappings, sla);
        verify(events, never()).publish(any());
        verify(claims).markIgnoredStale(claim);
        verify(claims, never()).markApplied(claim);
    }

    @Test
    void equalRevisionIsIgnoredEvenWhenOccurrenceTimeIsNewer() {
        ProcessObservation observation = processObservation("obs-equal-revision", 7L,
                OCCURRED.plusSeconds(30));
        owningClaim(observation, activeCase("tenant-a", "process-1", 7));
        when(claims.latestAppliedPosition(observation)).thenReturn(Optional.of(
                new AppliedObservationRepository.AppliedPosition(
                        "obs-current", 7L, OCCURRED, "STARTED")));

        assertThat(handler.apply(observation).status()).isEqualTo(ApplyStatus.IGNORED_STALE);

        verify(claims).markIgnoredStale(claim);
        verify(projections, never()).observe(any(ProcessCompletionObservation.class));
        verify(events, never()).publish(any());
    }

    @Test
    void equalOccurrenceTimeAndEventAreIgnoredForUnrevisionedFacts() {
        ProcessObservation observation = processObservation("obs-equal-time", null, OCCURRED);
        owningClaim(observation, activeCase("tenant-a", "process-1", 7));
        when(claims.latestAppliedPosition(observation)).thenReturn(Optional.of(
                new AppliedObservationRepository.AppliedPosition(
                        "obs-current", null, OCCURRED, "COMPLETED")));

        assertThat(handler.apply(observation).status()).isEqualTo(ApplyStatus.IGNORED_STALE);

        verify(claims).markIgnoredStale(claim);
        verify(events, never()).publish(any());
    }

    @Test
    void distinctForwardTaskEventAtSameEngineTimestampIsApplied() {
        UserTaskObservation observation = new UserTaskObservation("obs-claim-same-time", 1,
                "operaton:embedded", "tenant-a", "case-1", "process-1", "task-1", null,
                UserTaskObservation.EventType.CLAIMED, OCCURRED, RECEIVED,
                authorityAttributes("taskDefinitionKey", "review", "assignee", "alice"));
        owningClaim(observation, activeCase("tenant-a", "process-1", 7));
        when(claims.latestAppliedPosition(observation)).thenReturn(Optional.of(
                new AppliedObservationRepository.AppliedPosition(
                        "obs-created", null, OCCURRED, "CREATED")));

        assertThat(handler.apply(observation).status()).isEqualTo(ApplyStatus.APPLIED);

        verify(projections).observe(argThat((TaskObservation projected) ->
                projected.eventName().equals("claim")));
        verify(claims).markApplied(claim);
        verify(claims, never()).markIgnoredStale(claim);
    }

    @Test
    void embeddedActivityCancellationOverridesTheSyntheticEndAtTheSameEngineTimestamp() {
        ActivityLifecycleObservation observation = new ActivityLifecycleObservation(
                "obs-stage-cancelled", 1, "operaton:embedded", "tenant-a", "case-1",
                "process-1", "stage-instance", null,
                ActivityLifecycleObservation.EventType.CANCELLED, OCCURRED, RECEIVED,
                authorityAttributes("activityId", "assessment", "name", "Assessment"));
        owningClaim(observation, activeCase("tenant-a", "process-1", 7));
        when(claims.latestAppliedPosition(observation)).thenReturn(Optional.of(
                new AppliedObservationRepository.AppliedPosition(
                        "obs-stage-ended", null, OCCURRED, "COMPLETED")));

        assertThat(handler.apply(observation).status()).isEqualTo(ApplyStatus.APPLIED);

        verify(projections).observe(argThat((ActivityObservation projected) ->
                projected.eventName().equals("delete")));
        verify(claims).markApplied(claim);
        verify(claims, never()).markIgnoredStale(claim);
    }

    @Test
    void syntheticActivityEndCannotReverseAnObservedCancellation() {
        ActivityLifecycleObservation observation = new ActivityLifecycleObservation(
                "obs-stage-ended", 1, "operaton:embedded", "tenant-a", "case-1",
                "process-1", "stage-instance", null,
                ActivityLifecycleObservation.EventType.COMPLETED, OCCURRED.plusMillis(3), RECEIVED,
                authorityAttributes("activityId", "assessment", "name", "Assessment"));
        owningClaim(observation, activeCase("tenant-a", "process-1", 7));
        when(claims.latestAppliedPosition(observation)).thenReturn(Optional.of(
                new AppliedObservationRepository.AppliedPosition(
                        "obs-stage-cancelled", null, OCCURRED, "CANCELLED")));

        assertThat(handler.apply(observation).status()).isEqualTo(ApplyStatus.IGNORED_STALE);

        verify(projections, never()).observe(any(ActivityObservation.class));
        verify(claims).markIgnoredStale(claim);
        verify(claims, never()).markApplied(claim);
    }

    @Test
    void remoteArrivalOrderCannotBreakAnEqualTimestampTie() {
        UserTaskObservation observation = new UserTaskObservation("obs-remote-claim", 1,
                "operaton:remote", "tenant-a", "case-1", "process-1", "task-1", null,
                UserTaskObservation.EventType.CLAIMED, OCCURRED, RECEIVED,
                authorityAttributes("taskDefinitionKey", "review", "assignee", "alice"));
        owningClaim(observation, activeCase("tenant-a", "process-1", 7));
        when(claims.latestAppliedPosition(observation)).thenReturn(Optional.of(
                new AppliedObservationRepository.AppliedPosition(
                        "obs-created", null, OCCURRED, "CREATED")));

        assertThat(handler.apply(observation).status()).isEqualTo(ApplyStatus.IGNORED_STALE);

        verify(projections, never()).observe(any(TaskObservation.class));
        verify(claims).markIgnoredStale(claim);
    }

    @Test
    void terminalTaskStateRejectsDifferentEventAtSameEngineTimestamp() {
        UserTaskObservation observation = new UserTaskObservation("obs-late-claim", 1,
                "operaton:embedded", "tenant-a", "case-1", "process-1", "task-1", null,
                UserTaskObservation.EventType.CLAIMED, OCCURRED, RECEIVED,
                authorityAttributes("taskDefinitionKey", "review", "assignee", "alice"));
        owningClaim(observation, activeCase("tenant-a", "process-1", 7));
        when(claims.latestAppliedPosition(observation)).thenReturn(Optional.of(
                new AppliedObservationRepository.AppliedPosition(
                        "obs-completed", null, OCCURRED, "COMPLETED")));

        assertThat(handler.apply(observation).status()).isEqualTo(ApplyStatus.IGNORED_STALE);

        verify(projections, never()).observe(any(TaskObservation.class));
        verify(claims).markIgnoredStale(claim);
    }

    @Test
    void mixedOrderingModeRejectionEmitsOnlySafeTelemetryBeforeRollback() {
        ProcessObservation observation = processObservation("obs-mixed-mode", null, OCCURRED);
        owningClaim(observation, activeCase("tenant-a", "process-1", 7));
        when(claims.latestAppliedPosition(observation)).thenThrow(
                new AppliedObservationRepository.ObservationOrderingModeException(
                        "mixed ordering modes for safe identity"));

        assertThatThrownBy(() -> handler.apply(observation))
                .isInstanceOf(AppliedObservationRepository.ObservationOrderingModeException.class);

        verify(securityTelemetry).rejected(new ObservationSecurityTelemetry.Rejection(
                "case-1", "process-1", "process-1",
                ObservationRejectionReason.ORDERING_MODE_MISMATCH));
        verifyNoInteractions(mappings, sla, events);
        verify(claims, never()).markApplied(any());
        verify(claims, never()).markIgnoredStale(any());
    }

    @Test
    void collaboratorFailurePropagatesWithoutWritingAFailedMarkerThatWouldRollBackAnyway() {
        ProcessObservation observation = processObservation("obs-failure", 11L, OCCURRED);
        owningClaim(observation, activeCase("tenant-a", "process-1", 7));
        RuntimeException failure = new IllegalStateException("outbox unavailable");
        org.mockito.Mockito.doThrow(failure).when(events).publish(any());

        assertThatThrownBy(() -> handler.apply(observation)).isSameAs(failure);

        verify(claims, never()).markApplied(any());
        verify(claims, never()).markFailed(any(), any());
    }

    @Test
    void canonicalConflictFailsTheObservationSoTheCallerTransactionCanRetryFresh() {
        UserTaskObservation observation = new UserTaskObservation("obs-conflict", 1,
                "operaton:embedded", "tenant-a", "case-1", "process-1", "task-1", 5L,
                UserTaskObservation.EventType.COMPLETED, OCCURRED, RECEIVED,
                authorityAttributes("taskDefinitionKey", "review",
                        "variables", Map.of("decision", "yes")));
        owningClaim(observation, activeCase("tenant-a", "process-1", 7));
        CanonicalPatch patch = new CanonicalPatch("case-1", "review", 7, List.of());
        when(mappings.mapTaskOutput("case-1", "review", Map.of("decision", "yes")))
                .thenReturn(patch);
        when(mappings.apply(patch)).thenReturn(CaseDataMappingService.PatchResult.conflict(8,
                new CaseDataMappingService.ConflictMetadata(7, 8, List.of())));

        assertThatThrownBy(() -> handler.apply(observation))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("canonical output conflict")
                .hasMessageNotContaining("decision");

        verifyNoInteractions(events, sla);
        verify(claims, never()).markApplied(any());
        verify(claims, never()).markFailed(any(), any());
    }

    @Test
    void applyIsTheSingleRequiredTransactionBoundary() throws Exception {
        Method apply = DefaultEngineObservationHandler.class.getMethod(
                "apply", EngineObservation.class);
        Transactional transactional = apply.getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation()).isEqualTo(Propagation.REQUIRED);
        assertThat(DefaultEngineObservationHandler.class.getAnnotation(Transactional.class)).isNull();
        assertThat(List.of(DefaultEngineObservationHandler.class.getDeclaredMethods()))
                .filteredOn(method -> method.getAnnotation(Transactional.class) != null)
                .containsExactly(apply);
    }

    @Test
    void handlerCanBeClassProxiedWhenTheApplicationUsesCglibTransactions() {
        ProxyFactory proxyFactory = new ProxyFactory(handler);
        proxyFactory.setProxyTargetClass(true);

        assertThatCode(proxyFactory::getProxy).doesNotThrowAnyException();
    }

    private InOrder normalOrder(EngineObservation observation) {
        InOrder order = inOrder(claims, cases, processes, authority, projections, mappings, sla, events);
        order.verify(cases).lockForObservation("case-1");
        order.verify(claims).claim(observation);
        order.verify(cases).require("case-1");
        order.verify(processes).findByCase("case-1");
        order.verify(authority).validate(observation, activeCase("tenant-a", "process-1", 7));
        ProjectionEntityIdentity identity = expectedEntityIdentity(observation);
        if (identity != null) {
            order.verify(projections).assertEntityOwnership(identity);
        }
        order.verify(claims).latestAppliedPosition(observation);
        return order;
    }

    private static ProjectionEntityIdentity expectedEntityIdentity(EngineObservation observation) {
        if (observation instanceof UserTaskObservation task) {
            String activityId = (String) task.attributes().get("activityInstanceId");
            return new ProjectionEntityIdentity(task.caseId(), task.processInstanceId(),
                    ProjectionEntityIdentity.Kind.USER_TASK, task.entityId(),
                    activityId == null ? task.entityId() : activityId);
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

    private CaseEvent verifyAuditEventMarker(InOrder order, String action, String resourceType,
                                             String resourceId) {
        ArgumentCaptor<CaseEvent> event = ArgumentCaptor.forClass(CaseEvent.class);
        order.verify(events).audit(eq("case-1"), eq("tenant-a"), eq("engine"), eq(action),
                eq(resourceType), eq(resourceId), any(), any());
        order.verify(events).publish(event.capture());
        order.verify(claims).markApplied(claim);
        return event.getValue();
    }

    private void owningClaim(EngineObservation observation, CaseInstance caseInstance) {
        when(claims.claim(observation)).thenReturn(owned());
        when(cases.require(observation.caseId())).thenReturn(caseInstance);
        when(processes.findByCase(observation.caseId())).thenReturn(
                List.of(rootLink(caseInstance.rootProcessInstanceId())));
        when(claims.latestAppliedPosition(observation)).thenReturn(Optional.empty());
        if (observation instanceof ProcessObservation process
                && (process.eventType() == ProcessObservation.EventType.COMPLETED
                    || process.eventType() == ProcessObservation.EventType.TERMINATED)) {
            boolean rootTransition = caseInstance.state() == CaseState.ACTIVE
                    && caseInstance.rootProcessInstanceId().equals(process.processInstanceId());
            when(projections.observeFromHandler(any(ProcessCompletionObservation.class)))
                    .thenReturn(new ProcessProjectionResult(rootTransition,
                            caseInstance.version() + (rootTransition ? 1 : 0)));
        }
    }

    private AppliedObservationRepository.ClaimResult owned() {
        return AppliedObservationRepository.ClaimResult.claimed(
                AppliedObservationRepository.ClaimOutcome.CLAIMED, claim);
    }

    private static ProcessObservation processObservation(String observationId, Long revision,
                                                         Instant occurred) {
        return new ProcessObservation(observationId, 1, "operaton:embedded", "tenant-a",
                "case-1", "process-1", "process-1", revision,
                ProcessObservation.EventType.COMPLETED, occurred, RECEIVED,
                authorityAttributes());
    }

    private static LinkedProcessRepository.LinkedProcessRow rootLink(String processInstanceId) {
        return new LinkedProcessRepository.LinkedProcessRow("link-1", "case-1", null, "link-1",
                processInstanceId, "claim-process", "ACTIVE", CaseTask.EngineSync.SYNCED, true);
    }

    private static CaseInstance activeCase(String tenantId, String rootProcessId, long version) {
        OffsetDateTime now = at(OCCURRED.minusSeconds(60));
        return new CaseInstance("case-1", "engine-a", tenantId, "claim:1", "claim", 1,
                "business-1", "Claim", CaseState.ACTIVE, CasePriority.MEDIUM, null, null,
                "starter", "NONE", null, null, Map.of(), version, now, now, null,
                rootProcessId, ProjectionStatus.CURRENT, null, now);
    }

    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static Map<String, Object> authorityAttributes(Object... entries) {
        Map<String, Object> attributes = new java.util.LinkedHashMap<>();
        attributes.put("engineId", "engine-a");
        attributes.put("processDefinitionId", "claim-process:7");
        attributes.put("processDefinitionKey", "claim-process");
        for (int index = 0; index < entries.length; index += 2) {
            attributes.put((String) entries[index], entries[index + 1]);
        }
        return Map.copyOf(attributes);
    }
}
