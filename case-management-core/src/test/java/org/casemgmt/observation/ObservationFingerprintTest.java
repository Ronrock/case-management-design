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
                "obs-process-100", 1, "operaton:embedded", "engine-a", "tenant-a", "case-100", "process-100", "process-100", 7L,
                ProcessObservation.EventType.STARTED, OCCURRED_AT, RECEIVED_AT,
                Map.of("definitionKey", "intake", "businessKey", "case-100"));

        assertEquals("88a444e3e63a9557d9551d6454fe5e0c39f9151fbf09abef0cc637822dc35ead",
                ObservationFingerprint.of(observation).value());
    }

    @Test
    void fingerprintsUserTaskObservationsFromTheirStableIdentity() {
        var observation = new UserTaskObservation(
                "obs-task-101", 1, "operaton:remote", "engine-a", "tenant-a", "case-100", "process-100", "task-101", 3L,
                UserTaskObservation.EventType.COMPLETED, OCCURRED_AT, RECEIVED_AT,
                Map.of("activityId", "review", "assignee", "worker-7"));

        assertEquals("1ef16d542f7b8c8ba97d7198dedea69421ceaea2979d8e0acbf0ef8d704fd1f2",
                ObservationFingerprint.of(observation).value());
    }

    @Test
    void fingerprintsActivityLifecycleObservationsFromTheirStableIdentity() {
        var observation = new ActivityLifecycleObservation(
                "obs-activity-201", 1, "operaton:embedded", "engine-b", "tenant-b", "case-200", "process-200", "activity-201", 11L,
                ActivityLifecycleObservation.EventType.COMPLETED, OCCURRED_AT, RECEIVED_AT,
                Map.of("activityId", "verify-documents", "activityType", "serviceTask"));

        assertEquals("70f0d2c546ca50309cd14d5e1cb4ec8adf38979d6f1f8fba377dcd61a9067c19",
                ObservationFingerprint.of(observation).value());
    }

    @Test
    void fingerprintsMilestoneObservationsFromTheirStableIdentity() {
        var observation = new MilestoneObservation(
                "obs-milestone-202", 1, "operaton:remote", "engine-b", "tenant-b", "case-200", "process-200", "milestone-202", 12L,
                MilestoneObservation.EventType.REACHED, OCCURRED_AT, RECEIVED_AT,
                Map.of("milestoneId", "documents-verified", "activityId", "verify-documents"));

        assertEquals("d109b0621885dfc33378a56e14c986de96f038294ba43cfb8fc6a3a8c4532cab",
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
    void sharesIdentityAcrossAdapterSourcesButSeparatesEngineInstances() {
        var embedded = new UserTaskObservation(
                "obs-embedded", 1, "operaton:embedded", "engine-west", "tenant-a",
                "case-100", "process-100", "task-101", 3L,
                UserTaskObservation.EventType.COMPLETED, OCCURRED_AT, RECEIVED_AT,
                Map.of("activityId", "review"));
        var reconciliation = new UserTaskObservation(
                "obs-reconciliation", 1, "reconciliation", "engine-west", "tenant-a",
                "case-100", "process-100", "task-101", 3L,
                UserTaskObservation.EventType.COMPLETED, OCCURRED_AT, RECEIVED_AT.plusSeconds(30),
                Map.of("activityId", "review"));
        var anotherEngine = new UserTaskObservation(
                "obs-other-engine", 1, "operaton:embedded", "engine-east", "tenant-a",
                "case-100", "process-100", "task-101", 3L,
                UserTaskObservation.EventType.COMPLETED, OCCURRED_AT, RECEIVED_AT,
                Map.of("activityId", "review"));

        assertEquals(embedded.fingerprint(), reconciliation.fingerprint());
        assertNotEquals(embedded.fingerprint(), anotherEngine.fingerprint());
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
    void allowsTenantlessObservationsButRejectsBlankTenantIds() {
        assertThrows(IllegalArgumentException.class, () -> new ProcessObservation(
                " ", 1, "operaton:embedded", "tenant-a", "case-100", "process-100", "process-100", 7L,
                ProcessObservation.EventType.STARTED, OCCURRED_AT, RECEIVED_AT, Map.of()));
        var tenantless = new ProcessObservation(
                "obs-process-tenantless", 1, "operaton:embedded", null, "case-100", "process-100", "process-100", 7L,
                ProcessObservation.EventType.STARTED, OCCURRED_AT, RECEIVED_AT, Map.of());
        var tenantlessRedelivery = new ProcessObservation(
                "obs-process-tenantless-redelivery", 1, "operaton:embedded", null, "case-100", "process-100", "process-100", 7L,
                ProcessObservation.EventType.STARTED, OCCURRED_AT, RECEIVED_AT, Map.of());
        var namedTenant = new ProcessObservation(
                "obs-process-named-tenant", 1, "operaton:embedded", "tenant-a", "case-100", "process-100", "process-100", 7L,
                ProcessObservation.EventType.STARTED, OCCURRED_AT, RECEIVED_AT, Map.of());

        assertEquals(tenantless.fingerprint(), tenantlessRedelivery.fingerprint());
        assertNotEquals(tenantless.fingerprint(), namedTenant.fingerprint());
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
                "obs-task-null-attribute", 1, "operaton:remote", "engine-a", "tenant-a", "case-100", "process-100", "task-101", 3L,
                UserTaskObservation.EventType.COMPLETED, OCCURRED_AT, RECEIVED_AT, attributes);

        assertEquals("69df3df52379800d7b1e4e389451f77d235613a9de052cae2960ebca6dee9d96", observation.fingerprint());
    }

    private static final class UnsupportedNumber extends Number {
        @Override public int intValue() { return 1; }
        @Override public long longValue() { return 1; }
        @Override public float floatValue() { return 1; }
        @Override public double doubleValue() { return 1; }
    }
}
