package org.casemgmt;

import org.casemgmt.observation.ObservationEnvelope;
import org.casemgmt.observation.UserTaskObservation;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Durable remote facts remain self-describing across a process restart and an inbox retry. */
class BpmnFirstFailureRecoveryIT {

    @Test
    void durableTaskCompletionEnvelopeRoundTripsTheCanonicalCaseAndTenantIdentity() {
        Instant occurred = Instant.parse("2026-08-30T10:00:00Z");
        UserTaskObservation original = new UserTaskObservation("remote-task-7-COMPLETED", 1,
                "remote-history", "engine-a", "tenant-a", "case-1", "root-1", "task-7",
                42L, UserTaskObservation.EventType.COMPLETED, occurred, occurred,
                Map.of("taskDefinitionKey", "review"));

        UserTaskObservation recovered = (UserTaskObservation) ObservationEnvelope
                .decode(new ObservationEnvelope(original).payload()).observation();

        assertThat(recovered.caseId()).isEqualTo("case-1");
        assertThat(recovered.tenantId()).isEqualTo("tenant-a");
        assertThat(recovered.processInstanceId()).isEqualTo("root-1");
        assertThat(recovered.entityId()).isEqualTo("task-7");
        assertThat(recovered.eventType()).isEqualTo(UserTaskObservation.EventType.COMPLETED);
        assertThat(recovered.attributes()).containsEntry("taskDefinitionKey", "review");
    }
}
