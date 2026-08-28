package org.casemgmt.observation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Contract for storing an observation before a future remote dispatcher applies it. */
class EngineObservationSerializationContractTest {

    private static final Instant OCCURRED = Instant.parse("2026-08-28T08:30:00.123Z");
    private static final Instant RECEIVED = Instant.parse("2026-08-28T08:30:05.456Z");
    private static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    @ParameterizedTest(name = "{0} survives stored JSON without changing identity or attributes")
    @MethodSource("observations")
    void everyObservationTypeRoundTripsThroughAStoredRepresentation(
            String description, EngineObservation original) throws Exception {
        byte[] stored = JSON.writeValueAsBytes(original);

        EngineObservation restored = JSON.readValue(stored, original.getClass());

        assertThat(restored).isEqualTo(original);
        assertThat(restored.fingerprint()).isEqualTo(original.fingerprint());
    }

    private static Stream<Object[]> observations() {
        Map<String, Object> authority = Map.of(
                "processDefinitionId", "claim:7:deployment-a",
                "processDefinitionKey", "claim");
        return Stream.of(
                fixture("process", new ProcessObservation("process-observation", 1,
                        "operaton:embedded", "engine-a", null, "case-1", "process-1",
                        "process-1", 11L, ProcessObservation.EventType.TERMINATED,
                        OCCURRED, RECEIVED, with(authority,
                                "cancellationReason", "customer withdrew"))),
                fixture("user task", new UserTaskObservation("task-observation", 1,
                        "operaton:embedded", "engine-a", "tenant-a", "case-1", "process-1",
                        "task-1", 12L, UserTaskObservation.EventType.COMPLETED,
                        OCCURRED, RECEIVED, with(authority,
                                "taskDefinitionKey", "review",
                                "candidateGroups", List.of("reviewers", "senior-reviewers"),
                                "priority", 70,
                                "variables", Map.of("approved", true, "amount", 12.50)))),
                fixture("activity", new ActivityLifecycleObservation("activity-observation", 1,
                        "operaton:embedded", "engine-a", "tenant-a", "case-1", "process-1",
                        "activity-instance-1", 13L,
                        ActivityLifecycleObservation.EventType.CANCELLED,
                        OCCURRED, RECEIVED, with(authority,
                                "activityId", "assessment", "name", "Assessment"))),
                fixture("milestone", new MilestoneObservation("milestone-observation", 1,
                        "operaton:embedded", "engine-a", "tenant-a", "case-1", "process-1",
                        "milestone-instance-1", 14L, MilestoneObservation.EventType.REACHED,
                        OCCURRED, RECEIVED, with(authority,
                                "activityId", "accepted", "milestoneId", "accepted"))));
    }

    private static Object[] fixture(String description, EngineObservation observation) {
        return new Object[]{description, observation};
    }

    private static Map<String, Object> with(Map<String, Object> base, Object... entries) {
        var attributes = new java.util.LinkedHashMap<String, Object>(base);
        for (int index = 0; index < entries.length; index += 2) {
            attributes.put((String) entries[index], entries[index + 1]);
        }
        return attributes;
    }
}
