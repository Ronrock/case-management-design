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
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
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
            if (uri.getPath().equals("/history/task")) {
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
            } else if (uri.getPath().equals("/history/activity-instance")) {
                assertThat(query(uri)).containsEntry("sortBy", "activityInstanceId")
                        .containsEntry("sortOrder", "asc")
                        .containsKey("startedAfter").containsKey("startedBefore");
            } else if (uri.getPath().equals("/history/process-instance")) {
                assertThat(query(uri)).containsEntry("sortBy", "instanceId")
                        .containsEntry("sortOrder", "asc")
                        .containsKey("finishedAfter").containsKey("finishedBefore");
            }
        }).andRespond(request -> {
            URI uri = request.getURI();
            if (uri.getPath().equals("/history/task")) {
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
        }).when(ingestion).persistPage(eq("tenant-a"), eq(ObservationStream.TASKS),
                any(), any(ObservationCursor.class));
        return new RemoteObservationPoller(client, activeCases, cases, classifier, ingestion, worker);
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
