package org.casemgmt.observation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ObservationFingerprintTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-08-27T10:15:30Z");
    private static final Instant RECEIVED_AT = Instant.parse("2026-08-27T10:15:35Z");

    @Test
    void fingerprintsProcessObservationsFromTheirStableIdentity() {
        var observation = new ProcessObservation(
                "obs-process-100", 1, "operaton:embedded", "tenant-a", "case-100", "process-100", "process-100", 7L,
                ProcessObservation.EventType.STARTED, OCCURRED_AT, RECEIVED_AT,
                Map.of("definitionKey", "intake", "businessKey", "case-100"));

        assertEquals("ac0b016d4c3760ab1b4325f3390e5449407a45b92cd60607ee8e9e4a8ce80fe3",
                ObservationFingerprint.of(observation).value());
    }

    @Test
    void fingerprintsUserTaskObservationsFromTheirStableIdentity() {
        var observation = new UserTaskObservation(
                "obs-task-101", 1, "operaton:remote", "tenant-a", "case-100", "process-100", "task-101", 3L,
                UserTaskObservation.EventType.COMPLETED, OCCURRED_AT, RECEIVED_AT,
                Map.of("activityId", "review", "assignee", "worker-7"));

        assertEquals("e429ad040b71063f1e7c5449c2c4f4ec6c4723c0eead1ec311dc432b4190663d",
                ObservationFingerprint.of(observation).value());
    }

    @Test
    void fingerprintsActivityLifecycleObservationsFromTheirStableIdentity() {
        var observation = new ActivityLifecycleObservation(
                "obs-activity-201", 1, "operaton:embedded", "tenant-b", "case-200", "process-200", "activity-201", 11L,
                ActivityLifecycleObservation.EventType.COMPLETED, OCCURRED_AT, RECEIVED_AT,
                Map.of("activityId", "verify-documents", "activityType", "serviceTask"));

        assertEquals("a54ec19b66fd95f5476499fa5a771eabc2064f964d22765e58a97930168db036",
                ObservationFingerprint.of(observation).value());
    }

    @Test
    void fingerprintsMilestoneObservationsFromTheirStableIdentity() {
        var observation = new MilestoneObservation(
                "obs-milestone-202", 1, "operaton:remote", "tenant-b", "case-200", "process-200", "milestone-202", 12L,
                MilestoneObservation.EventType.REACHED, OCCURRED_AT, RECEIVED_AT,
                Map.of("milestoneId", "documents-verified", "activityId", "verify-documents"));

        assertEquals("c30f5d697a72307496c44eb389a87a9f45f4bb02021463fac9a2195aa31a2689",
                ObservationFingerprint.of(observation).value());
    }

    @Test
    void ignoresReceiptTimeAndAttributeInsertionOrderButDistinguishesIdentity() {
        var attributesInOneOrder = new LinkedHashMap<String, Object>();
        attributesInOneOrder.put("activityId", "review");
        attributesInOneOrder.put("assignee", "worker-7");
        var attributesInAnotherOrder = new LinkedHashMap<String, Object>();
        attributesInAnotherOrder.put("assignee", "worker-7");
        attributesInAnotherOrder.put("activityId", "review");

        var first = new UserTaskObservation(
                "obs-task-first", 1, "operaton:remote", "tenant-a", "case-100", "process-100", "task-101", 3L,
                UserTaskObservation.EventType.COMPLETED, OCCURRED_AT, RECEIVED_AT, attributesInOneOrder);
        var sameObservationReceivedLater = new UserTaskObservation(
                "obs-task-redelivery", 1, "operaton:remote", "tenant-a", "case-100", "process-100", "task-101", 3L,
                UserTaskObservation.EventType.COMPLETED, OCCURRED_AT, RECEIVED_AT.plusSeconds(60),
                attributesInAnotherOrder);
        var changedRevision = new UserTaskObservation(
                "obs-task-revision", 1, "operaton:remote", "tenant-a", "case-100", "process-100", "task-101", 4L,
                UserTaskObservation.EventType.COMPLETED, OCCURRED_AT, RECEIVED_AT, attributesInOneOrder);

        assertEquals(ObservationFingerprint.of(first), ObservationFingerprint.of(sameObservationReceivedLater));
        assertNotEquals(ObservationFingerprint.of(first), ObservationFingerprint.of(changedRevision));
    }

    @Test
    void defensivelyCopiesJsonFriendlyAttributes() {
        var labels = new ArrayList<>(List.of("urgent"));
        var nested = new LinkedHashMap<String, Object>();
        nested.put("labels", labels);
        var attributes = new LinkedHashMap<String, Object>();
        attributes.put("metadata", nested);
        var observation = new ProcessObservation(
                "obs-process-attributes", 1, "operaton:embedded", "tenant-a", "case-100", "process-100", "process-100", 7L,
                ProcessObservation.EventType.STARTED, OCCURRED_AT, RECEIVED_AT, attributes);

        labels.add("mutated");
        attributes.put("other", "mutated");

        assertEquals(Map.of("metadata", Map.of("labels", List.of("urgent"))), observation.attributes());
    }

    @Test
    void excludesEnvelopeObservationIdFromTheEngineFactFingerprint() {
        var first = new UserTaskObservation(
                "obs-task-original", 1, "operaton:remote", "tenant-a", "case-100", "process-100", "task-101", 3L,
                UserTaskObservation.EventType.COMPLETED, OCCURRED_AT, RECEIVED_AT,
                Map.of("activityId", "review"));
        var redelivery = new UserTaskObservation(
                "obs-task-redelivery", 1, "operaton:remote", "tenant-a", "case-100", "process-100", "task-101", 3L,
                UserTaskObservation.EventType.COMPLETED, OCCURRED_AT, RECEIVED_AT,
                Map.of("activityId", "review"));

        assertEquals(first.fingerprint(), redelivery.fingerprint());
        assertEquals(ObservationFingerprint.of(first).value(), first.fingerprint());
    }

    @Test
    void rejectsBlankEnvelopeObservationIdsAndTenantIds() {
        assertThrows(IllegalArgumentException.class, () -> new ProcessObservation(
                " ", 1, "operaton:embedded", "tenant-a", "case-100", "process-100", "process-100", 7L,
                ProcessObservation.EventType.STARTED, OCCURRED_AT, RECEIVED_AT, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new ProcessObservation(
                "obs-process-tenant", 1, "operaton:embedded", null, "case-100", "process-100", "process-100", 7L,
                ProcessObservation.EventType.STARTED, OCCURRED_AT, RECEIVED_AT, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new ProcessObservation(
                "obs-process-blank-tenant", 1, "operaton:embedded", " ", "case-100", "process-100", "process-100", 7L,
                ProcessObservation.EventType.STARTED, OCCURRED_AT, RECEIVED_AT, Map.of()));
    }

    @Test
    void normalizesMutableNumbersAndRejectsUnsupportedOrNonFiniteNumbers() {
        var mutableNumber = new AtomicLong(7);
        var observation = new ProcessObservation(
                "obs-process-number", 1, "operaton:embedded", "tenant-a", "case-100", "process-100", "process-100", 7L,
                ProcessObservation.EventType.STARTED, OCCURRED_AT, RECEIVED_AT, Map.of("count", mutableNumber));
        var fingerprint = observation.fingerprint();

        mutableNumber.set(8);

        assertEquals(Map.of("count", new BigDecimal("7")), observation.attributes());
        assertEquals(fingerprint, observation.fingerprint());
        assertThrows(IllegalArgumentException.class, () -> new ProcessObservation(
                "obs-process-unsupported-number", 1, "operaton:embedded", "tenant-a", "case-100", "process-100", "process-100", 7L,
                ProcessObservation.EventType.STARTED, OCCURRED_AT, RECEIVED_AT, Map.of("count", new UnsupportedNumber())));
        assertThrows(IllegalArgumentException.class, () -> new ProcessObservation(
                "obs-process-non-finite", 1, "operaton:embedded", "tenant-a", "case-100", "process-100", "process-100", 7L,
                ProcessObservation.EventType.STARTED, OCCURRED_AT, RECEIVED_AT, Map.of("count", Double.NaN)));
    }

    @Test
    void fingerprintsAttributesContainingNullMapValues() {
        var attributes = new LinkedHashMap<String, Object>();
        attributes.put("deletedAt", null);
        attributes.put("activityId", "review");
        var observation = new UserTaskObservation(
                "obs-task-null-attribute", 1, "operaton:remote", "tenant-a", "case-100", "process-100", "task-101", 3L,
                UserTaskObservation.EventType.COMPLETED, OCCURRED_AT, RECEIVED_AT, attributes);

        assertEquals("fa17f9159a9c7c927705987a31326fb9213a7d1a97343d8a9ed91d1e7320edac", observation.fingerprint());
    }

    private static final class UnsupportedNumber extends Number {
        @Override public int intValue() { return 1; }
        @Override public long longValue() { return 1; }
        @Override public float floatValue() { return 1; }
        @Override public double doubleValue() { return 1; }
    }
}
