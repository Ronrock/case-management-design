package org.casemgmt.engine.remote;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.casemgmt.domain.CaseInstance;
import org.casemgmt.domain.CasePriority;
import org.casemgmt.domain.CaseState;
import org.casemgmt.observation.ObservationCursor;
import org.casemgmt.observation.ObservationEnvelope;
import org.casemgmt.observation.ObservationStream;
import org.casemgmt.observation.RemoteObservationInboxWorker;
import org.casemgmt.observation.RemoteObservationIngestionService;
import org.casemgmt.observation.UserTaskObservation;
import org.casemgmt.projection.ActiveBpmnCaseRepository;
import org.casemgmt.repo.CaseRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
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
                new RemoteProcessActivityClassifier.TaskMetadata(List.of(), null));
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
                new RemoteProcessActivityClassifier.TaskMetadata(List.of(), null));
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
}
