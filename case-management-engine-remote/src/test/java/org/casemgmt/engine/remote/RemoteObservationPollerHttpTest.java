package org.casemgmt.engine.remote;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.casemgmt.domain.CaseInstance;
import org.casemgmt.domain.CasePriority;
import org.casemgmt.domain.CaseState;
import org.casemgmt.observation.ObservationCursor;
import org.casemgmt.observation.ObservationEnvelope;
import org.casemgmt.observation.ObservationStream;
import org.casemgmt.observation.ApplyResult;
import org.casemgmt.observation.ApplyStatus;
import org.casemgmt.observation.EngineObservation;
import org.casemgmt.observation.EngineObservationHandler;
import org.casemgmt.observation.RemoteObservationInboxWorker;
import org.casemgmt.observation.RemoteObservationIngestionService;
import org.casemgmt.observation.ActivityLifecycleObservation;
import org.casemgmt.observation.MilestoneObservation;
import org.casemgmt.observation.ProcessObservation;
import org.casemgmt.observation.UserTaskObservation;
import org.casemgmt.projection.ActivityObservation;
import org.casemgmt.projection.ActiveBpmnCaseRepository;
import org.casemgmt.repo.CaseRepository;
import org.casemgmt.repo.ObservationCheckpointRepository;
import org.casemgmt.repo.ObservationInboxRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Contract tests against the real RestClient request path.  A local pagination helper is not
 * sufficient evidence here: Operaton only accepts one history sort field, so the actual query
 * needs a stable, endpoint-specific identity order and a fixed time window.
 */
class RemoteObservationPollerHttpTest {
    private static final int ROWS = 1_201;
    private static final OffsetDateTime EVENT_AT = OffsetDateTime.parse("2026-08-30T10:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void reconciliationRebuildsOldHistoryThroughTheInboxWithoutSynthesizingAbsentDeletes()
            throws Exception {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://engine.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        List<ObservationEnvelope> persisted = new ArrayList<>();
        ActiveBpmnCaseRepository activeCases = mock(ActiveBpmnCaseRepository.class);
        CaseRepository cases = mock(CaseRepository.class);
        RemoteProcessActivityClassifier classifier = mock(RemoteProcessActivityClassifier.class);
        RemoteObservationIngestionService ingestion = mock(RemoteObservationIngestionService.class);
        RemoteObservationInboxWorker worker = mock(RemoteObservationInboxWorker.class);
        when(activeCases.findAllProcessesForActiveCases()).thenReturn(List.of(
                new ActiveBpmnCaseRepository.ReconciliationProcess("case-1", "tenant-a",
                        "engine-west", "root-old", "definition-1", true),
                new ActiveBpmnCaseRepository.ReconciliationProcess("case-1", "tenant-a",
                        "engine-west", "linked-old", "definition-1", false)));
        when(cases.findById("case-1")).thenReturn(Optional.of(caseInstance()));
        when(classifier.taskMetadata(any(), any())).thenReturn(
                new RemoteProcessActivityClassifier.TaskMetadata(List.of(), null, "review-sla"));
        when(classifier.classify("definition-1", "stage")).thenReturn(Optional.of(
                new RemoteProcessActivityClassifier.Classification(ActivityObservation.Kind.STAGE, null,
                        "assessment-sla")));
        when(classifier.classify("definition-1", "milestone")).thenReturn(Optional.of(
                new RemoteProcessActivityClassifier.Classification(
                        ActivityObservation.Kind.MILESTONE, "case-opened", "case-sla")));
        doAnswer(invocation -> {
            List<ObservationEnvelope> envelopes = invocation.getArgument(2);
            persisted.addAll(envelopes);
            return envelopes.size();
        }).when(ingestion).persist(eq("tenant-a"), any(ObservationStream.class), any());

        List<String> reconciliationTaskPages = new ArrayList<>();
        server.expect(ExpectedCount.manyTimes(), request -> {
            URI uri = request.getURI();
            Map<String, String> query = uri.getRawQuery() == null ? Map.of() : query(uri);
            if (uri.getPath().equals("/history/task")) {
                if (!query.containsKey("processInstanceId")) return;
                assertThat(query).containsKey("processInstanceId")
                        .containsEntry("maxResults", "500")
                        .containsEntry("sortBy", "taskId").containsEntry("sortOrder", "asc");
                reconciliationTaskPages.add(query.get("processInstanceId") + ":"
                        + query.get("firstResult"));
            }
            if (uri.getPath().equals("/history/activity-instance")) {
                if (!query.containsKey("processInstanceId")) return;
                assertThat(query).containsKey("processInstanceId")
                        .containsEntry("firstResult", "0").containsEntry("maxResults", "500")
                        .containsEntry("sortBy", "activityInstanceId")
                        .containsEntry("sortOrder", "asc");
            }
        }).andRespond(request -> {
            URI uri = request.getURI();
            Map<String, String> query = uri.getRawQuery() == null ? Map.of() : query(uri);
            if (query.containsKey("startedAfter") || query.containsKey("finishedAfter")) {
                return withSuccess("[]", MediaType.APPLICATION_JSON).createResponse(request);
            }
            if (uri.getPath().equals("/history/task")) {
                if ("linked-old".equals(query.get("processInstanceId"))) {
                    return withSuccess("[]", MediaType.APPLICATION_JSON).createResponse(request);
                }
                int first = Integer.parseInt(query.get("firstResult"));
                return withSuccess(json(reconciliationTasks(first)), MediaType.APPLICATION_JSON)
                        .createResponse(request);
            }
            if (uri.getPath().equals("/history/activity-instance")) {
                if ("linked-old".equals(query.get("processInstanceId"))) {
                    return withSuccess("[]", MediaType.APPLICATION_JSON).createResponse(request);
                }
                return withSuccess(json(List.of(
                        activity("stage-running", "stage", null),
                        activity("stage-completed", "stage", "2026-08-02T10:00:00.000Z"),
                        activity("milestone-reached", "milestone", "2026-08-03T10:00:00.000Z"),
                        activity("milestone-still-active", "milestone", null))),
                        MediaType.APPLICATION_JSON).createResponse(request);
            }
            if (uri.getPath().equals("/history/process-instance/root-old")) {
                return withSuccess(json(Map.of("id", "root-old", "businessKey", "case-1",
                        "processDefinitionKey", "complaint")), MediaType.APPLICATION_JSON)
                        .createResponse(request);
            }
            if (uri.getPath().equals("/history/process-instance/linked-old")) {
                return withSuccess(json(Map.of("id", "linked-old", "businessKey", "case-1",
                        "processDefinitionKey", "linked", "endTime", "2026-08-04T10:00:00.000Z",
                        "deleteReason", "cancelled")), MediaType.APPLICATION_JSON)
                        .createResponse(request);
            }
            if (uri.getPath().equals("/history/process-instance")) {
                return withSuccess("[]", MediaType.APPLICATION_JSON).createResponse(request);
            }
            throw new AssertionError("unexpected endpoint " + uri);
        });

        RemoteObservationPoller poller = new RemoteObservationPoller(builder.build(), activeCases,
                cases, classifier, ingestion, worker);
        assertThat(poller.pollOnce()).isZero();
        assertThat(persisted).isEmpty();

        int inserted = poller.reconcileAllActive();

        assertThat(inserted).isEqualTo(505);
        assertThat(reconciliationTaskPages).containsExactly("root-old:0", "root-old:500",
                "linked-old:0");
        assertThat(persisted).extracting(envelope -> envelope.observation())
                .anySatisfy(observation -> assertThat(observation)
                        .isInstanceOfSatisfying(UserTaskObservation.class, task -> assertThat(task)
                                .matches(value -> value.entityId().equals("task-open"))
                                .matches(value -> value.eventType()
                                        == UserTaskObservation.EventType.CREATED)))
                .anySatisfy(observation -> assertThat(observation)
                        .isInstanceOfSatisfying(UserTaskObservation.class, task -> assertThat(task)
                                .matches(value -> value.entityId().equals("task-completed"))
                                .matches(value -> value.eventType()
                                        == UserTaskObservation.EventType.COMPLETED)))
                .anySatisfy(observation -> assertThat(observation)
                        .isInstanceOfSatisfying(ActivityLifecycleObservation.class, activity -> assertThat(activity)
                                .matches(value -> value.entityId().equals("stage-running"))))
                .anySatisfy(observation -> assertThat(observation)
                        .isInstanceOfSatisfying(ActivityLifecycleObservation.class, activity -> assertThat(activity)
                                .matches(value -> value.entityId().equals("stage-completed"))))
                .anySatisfy(observation -> assertThat(observation)
                        .isInstanceOfSatisfying(MilestoneObservation.class, milestone -> assertThat(milestone)
                                .matches(value -> value.entityId().equals("milestone-reached"))
                                .matches(value -> value.eventType()
                                        == MilestoneObservation.EventType.REACHED)
                                .matches(value -> "case-sla".equals(
                                        value.attributes().get("slaTargetId")))))
                .anySatisfy(observation -> assertThat(observation)
                        .isInstanceOfSatisfying(ActivityLifecycleObservation.class, activity -> assertThat(activity)
                                .matches(value -> value.entityId().equals("stage-running"))
                                .matches(value -> "assessment-sla".equals(
                                        value.attributes().get("slaTargetId")))))
                .anySatisfy(observation -> assertThat(observation)
                        .isInstanceOfSatisfying(UserTaskObservation.class, task -> assertThat(task)
                                .matches(value -> value.entityId().equals("task-open"))
                                .matches(value -> "review-sla".equals(
                                        value.attributes().get("slaTargetId")))))
                .anySatisfy(observation -> assertThat(observation)
                        .isInstanceOfSatisfying(ProcessObservation.class, process -> assertThat(process)
                                .matches(value -> value.processInstanceId().equals("linked-old"))
                                .matches(value -> value.eventType()
                                        == ProcessObservation.EventType.TERMINATED)))
                .noneSatisfy(observation -> assertThat(observation)
                        .isInstanceOf(UserTaskObservation.class)
                        .matches(task -> task.eventType() == UserTaskObservation.EventType.DELETED))
                .noneSatisfy(observation -> assertThat(observation)
                        .isInstanceOf(MilestoneObservation.class)
                        .matches(milestone -> milestone.eventType()
                                == MilestoneObservation.EventType.REOPENED));
        verify(worker).drainOnce();
        verify(worker).drainUntilIdle();
        verify(ingestion, never()).advanceCompletedWindow(any(), any(), any());
        server.verify();
    }

    @Test
    void reconciliationDrainsEveryDurableFactAndIsReplaySafeAcrossWorkerBatches()
            throws Exception {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://engine.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        InboxFixture fixture = inboxFixture();
        JdbcClient jdbc = fixture.jdbc();
        ObservationInboxRepository inbox = new ObservationInboxRepository(jdbc);
        RemoteObservationIngestionService ingestion = new RemoteObservationIngestionService(inbox,
                new ObservationCheckpointRepository(jdbc));
        Map<String, EngineObservation> effects = new java.util.LinkedHashMap<>();
        EngineObservationHandler handler = observation -> {
            effects.putIfAbsent(observation.fingerprint(), observation);
            return new ApplyResult(observation.observationId(), ApplyStatus.APPLIED, 1, List.of());
        };
        RemoteObservationInboxWorker worker = new RemoteObservationInboxWorker(inbox, handler,
                new DataSourceTransactionManager(fixture.dataSource()));
        ActiveBpmnCaseRepository activeCases = mock(ActiveBpmnCaseRepository.class);
        RemoteProcessActivityClassifier classifier = mock(RemoteProcessActivityClassifier.class);
        when(activeCases.findAllProcessesForActiveCases()).thenReturn(List.of(
                new ActiveBpmnCaseRepository.ReconciliationProcess("case-1", "tenant-a",
                        "engine-west", "root-old", "definition-1", true)));
        when(classifier.taskMetadata(any(), any())).thenReturn(
                new RemoteProcessActivityClassifier.TaskMetadata(List.of(), null, null));
        when(classifier.classify("definition-1", "stage")).thenReturn(Optional.of(
                new RemoteProcessActivityClassifier.Classification(ActivityObservation.Kind.STAGE, null,
                        null)));
        when(classifier.classify("definition-1", "milestone")).thenReturn(Optional.of(
                new RemoteProcessActivityClassifier.Classification(
                        ActivityObservation.Kind.MILESTONE, "case-opened", null)));
        server.expect(ExpectedCount.manyTimes(), request -> {
        }).andRespond(request -> {
            URI uri = request.getURI();
            Map<String, String> query = uri.getRawQuery() == null ? Map.of() : query(uri);
            if (uri.getPath().equals("/history/task")) {
                return withSuccess(json(reconciliationTasks(
                        Integer.parseInt(query.get("firstResult")))), MediaType.APPLICATION_JSON)
                        .createResponse(request);
            }
            if (uri.getPath().equals("/history/activity-instance")) {
                return withSuccess(json(List.of(
                        activity("stage-running", "stage", null),
                        activity("stage-completed", "stage", "2026-08-02T10:00:00.000Z"),
                        activity("milestone-reached", "milestone", "2026-08-03T10:00:00.000Z"),
                        activity("milestone-still-active", "milestone", null))),
                        MediaType.APPLICATION_JSON).createResponse(request);
            }
            if (uri.getPath().equals("/history/process-instance/root-old")) {
                return withSuccess(json(Map.of("id", "root-old", "businessKey", "case-1")),
                        MediaType.APPLICATION_JSON).createResponse(request);
            }
            throw new AssertionError("unexpected endpoint " + uri);
        });

        RemoteObservationPoller poller = new RemoteObservationPoller(builder.build(), activeCases,
                mock(CaseRepository.class), classifier, ingestion, worker);

        assertThat(poller.reconcileAllActive()).isEqualTo(504);
        assertThat(effects).hasSize(504);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM CM_REMOTE_OBS_INBOX WHERE STATUS_ = 'APPLIED'")
                .query(Long.class).single()).isEqualTo(504L);
        assertThat(poller.reconcileAllActive()).isZero();
        assertThat(effects).hasSize(504);
        assertThat(effects.values()).noneMatch(observation -> observation instanceof MilestoneObservation
                && ((MilestoneObservation) observation).eventType()
                == MilestoneObservation.EventType.REOPENED);
        server.verify();
    }

    @Test
    void taskHistoryHttpPagesUseOperatonTaskIdentityAndFixedWindowWithoutLosingEqualTimestamps()
            throws Exception {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://engine.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        List<Integer> taskOffsets = new ArrayList<>();
        List<ObservationEnvelope> persisted = new ArrayList<>();

        server.expect(ExpectedCount.manyTimes(), request -> {
            URI uri = request.getURI();
            if (uri.getPath().equals("/history/task") && query(uri).containsKey("startedAfter")) {
                Map<String, String> query = query(uri);
                // Operaton's historic-task REST API accepts taskId, not the generic "id".
                assertThat(query).containsEntry("sortBy", "taskId")
                        .containsEntry("sortOrder", "asc")
                        .containsEntry("maxResults", "500")
                        .containsKey("startedAfter").containsKey("startedBefore");
                // The RestClient owns URI encoding. Supplying a pre-encoded timestamp makes
                // the wire value "%253A" and Operaton cannot parse the history boundary.
                assertThat(query.get("startedAfter")).doesNotContain("%")
                        .contains("T").endsWith("+0000");
                taskOffsets.add(Integer.parseInt(query.get("firstResult")));
            } else if (uri.getPath().equals("/history/task")) {
                assertThat(query(uri)).containsEntry("sortBy", "taskId")
                        .containsKey("finishedAfter").containsKey("finishedBefore");
            } else if (uri.getPath().equals("/history/activity-instance")
                    && query(uri).containsKey("startedAfter")) {
                assertThat(query(uri)).containsEntry("sortBy", "activityInstanceId")
                        .containsEntry("sortOrder", "asc")
                        .containsKey("startedAfter").containsKey("startedBefore");
            } else if (uri.getPath().equals("/history/activity-instance")) {
                assertThat(query(uri)).containsEntry("sortBy", "activityInstanceId")
                        .containsEntry("sortOrder", "asc")
                        .containsKey("finishedAfter").containsKey("finishedBefore");
            } else if (uri.getPath().equals("/history/process-instance")) {
                assertThat(query(uri)).containsEntry("sortBy", "instanceId")
                        .containsEntry("sortOrder", "asc")
                        .containsKey("finishedAfter").containsKey("finishedBefore");
            }
        }).andRespond(request -> {
            URI uri = request.getURI();
            if (uri.getPath().equals("/history/task") && query(uri).containsKey("startedAfter")) {
                int first = Integer.parseInt(query(uri).get("firstResult"));
                return withSuccess(json(tasks(first)), MediaType.APPLICATION_JSON).createResponse(request);
            }
            return withSuccess("[]", MediaType.APPLICATION_JSON).createResponse(request);
        });

        RemoteObservationPoller poller = poller(builder.build(), persisted);

        poller.pollOnce();

        assertThat(taskOffsets).containsExactly(0, 500, 1_000);
        assertThat(persisted).hasSize(ROWS)
                .extracting(envelope -> ((UserTaskObservation) envelope.observation()).entityId())
                .doesNotHaveDuplicates()
                .contains("task-0000", "task-1200");
        server.verify();
    }

    @Test
    void taskCompletionWhoseStartPredatesOverlapIsReadFromTheFinishedHistoryStream()
            throws Exception {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://engine.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        List<ObservationEnvelope> persisted = new ArrayList<>();
        List<Map<String, String>> taskQueries = new ArrayList<>();
        server.expect(ExpectedCount.max(10), request -> {
            URI uri = request.getURI();
            if (uri.getPath().equals("/history/task")) taskQueries.add(query(uri));
        }).andRespond(request -> {
            Map<String, String> query = query(request.getURI());
            if (request.getURI().getPath().equals("/history/task") && query.containsKey("finishedAfter")) return withSuccess(json(List.of(Map.of(
                    "id", "task-long-running", "processInstanceId", "process-1", "businessKey", "case-1",
                    "startTime", "2026-08-01T10:00:00.000Z", "endTime", "2026-08-30T10:00:00.000Z",
                    "deleteReason", "completed", "processDefinitionId", "definition-1",
                    "taskDefinitionKey", "review", "name", "Review", "priority", 50))),
                    MediaType.APPLICATION_JSON).createResponse(request);
            return withSuccess("[]", MediaType.APPLICATION_JSON).createResponse(request);
        });

        poller(builder.build(), persisted).pollOnce();

        assertThat(persisted).singleElement().satisfies(envelope ->
                assertThat(((UserTaskObservation) envelope.observation()).eventType())
                        .isEqualTo(UserTaskObservation.EventType.COMPLETED));
        assertThat(taskQueries).anySatisfy(query -> assertThat(query)
                .containsKey("finishedAfter").containsKey("finishedBefore")
                .containsEntry("sortBy", "taskId"));
        server.verify();
    }

    @Test
    void taskHistoryClassifiesOpenCompletedAndDeletedRowsWithoutTreatingCompletedAsDeleted()
            throws Exception {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://engine.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        List<ObservationEnvelope> persisted = new ArrayList<>();
        server.expect(ExpectedCount.manyTimes(), request -> {
        }).andRespond(request -> {
            URI uri = request.getURI();
            Map<String, String> query = query(uri);
            if (!uri.getPath().equals("/history/task")) {
                return withSuccess("[]", MediaType.APPLICATION_JSON).createResponse(request);
            }
            if (query.containsKey("startedAfter")) {
                return withSuccess(json(List.of(task("task-open", "2026-08-30T10:00:00.000Z"))),
                        MediaType.APPLICATION_JSON).createResponse(request);
            }
            return withSuccess(json(List.of(
                    terminalTask("task-completed", "completed"),
                    terminalTask("task-deleted", "cancelled"))), MediaType.APPLICATION_JSON)
                    .createResponse(request);
        });

        poller(builder.build(), persisted).pollOnce();

        assertThat(persisted).extracting(envelope -> {
            UserTaskObservation task = (UserTaskObservation) envelope.observation();
            return Map.entry(task.entityId(), task.eventType());
        }).containsExactlyInAnyOrder(
                Map.entry("task-open", UserTaskObservation.EventType.CREATED),
                Map.entry("task-completed", UserTaskObservation.EventType.COMPLETED),
                Map.entry("task-deleted", UserTaskObservation.EventType.DELETED));
        server.verify();
    }

    @Test
    void failedLaterPageDoesNotMoveTheWindowCheckpointPastAnEarlierTimestampOnRetry()
            throws Exception {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://engine.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        List<String> taskWindowStarts = new ArrayList<>();
        AtomicBoolean failEarlierSecondPage = new AtomicBoolean(true);
        Set<String> durableTaskIds = new LinkedHashSet<>();
        ObservationCursor initial = new ObservationCursor(
                OffsetDateTime.parse("2026-08-30T09:59:00Z").toInstant(), "before-window");
        AtomicReference<ObservationCursor> checkpoint = new AtomicReference<>(initial);
        RemoteObservationIngestionService ingestion = mock(RemoteObservationIngestionService.class);
        when(ingestion.oldestCursor(any())).thenAnswer(invocation ->
                invocation.getArgument(0) == ObservationStream.TASKS
                        ? Optional.of(checkpoint.get()) : Optional.empty());
        doAnswer(invocation -> {
            ObservationStream stream = invocation.getArgument(1);
            List<ObservationEnvelope> page = invocation.getArgument(2);
            if (stream == ObservationStream.TASKS && page.stream().anyMatch(envelope ->
                    ((UserTaskObservation) envelope.observation()).entityId().equals("task-9999"))
                    && failEarlierSecondPage.getAndSet(false)) {
                throw new IllegalStateException("injected durable inbox failure");
            }
            if (stream == ObservationStream.TASKS) {
                page.forEach(envelope -> durableTaskIds.add(
                        ((UserTaskObservation) envelope.observation()).entityId()));
            }
            return page.size();
        }).when(ingestion).persistPage(eq("tenant-a"), any(ObservationStream.class), any());
        doAnswer(invocation -> {
            checkpoint.set(invocation.getArgument(2));
            return null;
        }).when(ingestion).advanceCompletedWindow(eq("tenant-a"), eq(ObservationStream.TASKS), any());
        server.expect(ExpectedCount.manyTimes(), request -> {
            URI uri = request.getURI();
            if (uri.getPath().equals("/history/task") && query(uri).containsKey("startedAfter")) {
                taskWindowStarts.add(query(uri).get("startedAfter"));
                assertThat(query(uri)).containsEntry("sortBy", "taskId")
                        .containsEntry("sortOrder", "asc");
            }
        }).andRespond(request -> {
            URI uri = request.getURI();
            if (uri.getPath().equals("/history/task") && query(uri).containsKey("startedAfter")) {
                int first = Integer.parseInt(query(uri).get("firstResult"));
                return withSuccess(json(outOfOrderTaskPage(first)), MediaType.APPLICATION_JSON)
                        .createResponse(request);
            }
            return withSuccess("[]", MediaType.APPLICATION_JSON).createResponse(request);
        });

        RemoteObservationPoller poller = poller(builder.build(), ingestion);

        assertThatThrownBy(poller::pollOnce)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("injected durable inbox failure");
        assertThat(checkpoint.get()).isEqualTo(initial);

        poller.pollOnce();

        assertThat(taskWindowStarts).hasSize(4);
        assertThat(taskWindowStarts).allSatisfy(start -> assertThat(start)
                .isEqualTo("2026-08-30T09:57:00.000+0000"));
        assertThat(durableTaskIds).hasSize(501).contains("task-0000", "task-0499", "task-9999");
        assertThat(checkpoint.get().timestamp()).isEqualTo(
                OffsetDateTime.parse("2026-08-30T10:10:00Z").toInstant());
        server.verify();
    }

    private static RemoteObservationPoller poller(RestClient client, List<ObservationEnvelope> persisted) {
        ActiveBpmnCaseRepository activeCases = mock(ActiveBpmnCaseRepository.class);
        CaseRepository cases = mock(CaseRepository.class);
        RemoteProcessActivityClassifier classifier = mock(RemoteProcessActivityClassifier.class);
        RemoteObservationIngestionService ingestion = mock(RemoteObservationIngestionService.class);
        RemoteObservationInboxWorker worker = mock(RemoteObservationInboxWorker.class);
        when(cases.findById("case-1")).thenReturn(Optional.of(caseInstance()));
        when(classifier.taskMetadata(any(), any())).thenReturn(
                new RemoteProcessActivityClassifier.TaskMetadata(List.of(), null, null));
        when(ingestion.oldestCursor(any())).thenReturn(Optional.empty());
        doAnswer(invocation -> {
            List<ObservationEnvelope> page = invocation.getArgument(2);
            persisted.addAll(page);
            return page.size();
        }).when(ingestion).persistPage(eq("tenant-a"), any(ObservationStream.class), any());
        return new RemoteObservationPoller(client, activeCases, cases, classifier, ingestion, worker);
    }

    private static RemoteObservationPoller poller(RestClient client,
                                                   RemoteObservationIngestionService ingestion) {
        ActiveBpmnCaseRepository activeCases = mock(ActiveBpmnCaseRepository.class);
        CaseRepository cases = mock(CaseRepository.class);
        RemoteProcessActivityClassifier classifier = mock(RemoteProcessActivityClassifier.class);
        RemoteObservationInboxWorker worker = mock(RemoteObservationInboxWorker.class);
        when(cases.findById("case-1")).thenReturn(Optional.of(caseInstance()));
        when(classifier.taskMetadata(any(), any())).thenReturn(
                new RemoteProcessActivityClassifier.TaskMetadata(List.of(), null, null));
        return new RemoteObservationPoller(client, activeCases, cases, classifier, ingestion, worker);
    }

    private static List<Map<String, Object>> outOfOrderTaskPage(int first) {
        if (first == 0) {
            List<Map<String, Object>> page = new ArrayList<>();
            for (int i = 0; i < 500; i++) {
                page.add(task("task-%04d".formatted(i), "2026-08-30T10:10:00.000Z"));
            }
            return page;
        }
        if (first == 500) return List.of(task("task-9999", "2026-08-30T10:00:00.000Z"));
        return List.of();
    }

    private static Map<String, Object> task(String id, String startTime) {
        return Map.of("id", id, "processInstanceId", "process-1", "businessKey", "case-1",
                "startTime", startTime, "processDefinitionId", "definition-1",
                "taskDefinitionKey", "review", "name", "Review", "priority", 50);
    }

    private static Map<String, Object> terminalTask(String id, String deleteReason) {
        return Map.of("id", id, "processInstanceId", "process-1", "businessKey", "case-1",
                "startTime", "2026-08-01T10:00:00.000Z", "endTime", "2026-08-30T10:00:00.000Z",
                "deleteReason", deleteReason, "processDefinitionId", "definition-1",
                "taskDefinitionKey", "review", "name", "Review", "priority", 50);
    }

    private static Map<String, Object> activity(String instanceId, String activityId,
                                                 String endTime) {
        Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("id", instanceId);
        row.put("activityInstanceId", instanceId);
        row.put("activityId", activityId);
        row.put("activityName", activityId);
        row.put("processInstanceId", "root-old");
        row.put("businessKey", "case-1");
        row.put("startTime", "2026-08-01T10:00:00.000Z");
        if (endTime != null) row.put("endTime", endTime);
        return row;
    }

    private static InboxFixture inboxFixture() {
        DataSource dataSource = new SimpleDriverDataSource(new org.h2.Driver(),
                "jdbc:h2:mem:remote-reconciliation-" + UUID.randomUUID()
                        + ";MODE=Oracle;DB_CLOSE_DELAY=-1");
        JdbcClient jdbc = JdbcClient.create(dataSource);
        jdbc.sql("""
                CREATE TABLE CM_REMOTE_OBS_INBOX (
                    FINGERPRINT_ VARCHAR(64) PRIMARY KEY,
                    TENANT_ID_ VARCHAR(128) NOT NULL,
                    STREAM_ VARCHAR(64) NOT NULL,
                    PAYLOAD_ CLOB NOT NULL,
                    STATUS_ VARCHAR(20) NOT NULL,
                    ATTEMPTS_ INTEGER NOT NULL,
                    CREATED_AT_ TIMESTAMP WITH TIME ZONE NOT NULL,
                    LEASE_TOKEN_ VARCHAR(64),
                    LEASED_AT_ TIMESTAMP WITH TIME ZONE,
                    FAILURE_DETAIL_ CLOB,
                    APPLIED_AT_ TIMESTAMP WITH TIME ZONE)
                """).update();
        return new InboxFixture(dataSource, jdbc);
    }

    private static List<Map<String, Object>> tasks(int first) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = first; i < Math.min(first + 500, ROWS); i++) {
            rows.add(Map.of("id", "task-%04d".formatted(i), "processInstanceId", "process-1",
                    "businessKey", "case-1", "startTime", "2026-08-30T10:00:00.000Z",
                    "processDefinitionId", "definition-1", "taskDefinitionKey", "review",
                    "name", "Review", "priority", 50));
        }
        return rows;
    }

    private static List<Map<String, Object>> reconciliationTasks(int first) {
        if (first == 500) return List.of(reconciliationTask("task-500", null));
        List<Map<String, Object>> page = new ArrayList<>();
        page.add(reconciliationTask("task-open", null));
        page.add(reconciliationTask("task-completed", "completed"));
        for (int i = 0; i < 498; i++) page.add(reconciliationTask("task-%03d".formatted(i), null));
        return page;
    }

    private static Map<String, Object> reconciliationTask(String id, String deleteReason) {
        Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("id", id);
        row.put("processInstanceId", "root-old");
        row.put("businessKey", "case-1");
        row.put("startTime", "2026-08-01T10:00:00.000Z");
        row.put("processDefinitionId", "definition-1");
        row.put("taskDefinitionKey", "review");
        row.put("name", "Review");
        row.put("priority", 50);
        if (deleteReason != null) {
            row.put("endTime", "2026-08-02T10:00:00.000Z");
            row.put("deleteReason", deleteReason);
        }
        return row;
    }

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException(error);
        }
    }

    private static Map<String, String> query(URI uri) {
        return java.util.Arrays.stream(uri.getRawQuery().split("&"))
                .map(part -> part.split("=", 2))
                .collect(java.util.stream.Collectors.toMap(parts -> decode(parts[0]),
                        parts -> parts.length == 2 ? decode(parts[1]) : ""));
    }

    private static String decode(String value) {
        return java.net.URLDecoder.decode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static CaseInstance caseInstance() {
        return new CaseInstance("case-1", "engine-west", "tenant-a", "def-1", "complaint", 1,
                "case-1", "Complaint", CaseState.ACTIVE, CasePriority.MEDIUM, null, null, "user-1",
                "NONE", null, null, Map.of(), 1, EVENT_AT, EVENT_AT, null);
    }

    private record InboxFixture(DataSource dataSource, JdbcClient jdbc) { }
}
