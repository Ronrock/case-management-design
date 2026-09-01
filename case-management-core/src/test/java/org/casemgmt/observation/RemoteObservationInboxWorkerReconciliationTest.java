package org.casemgmt.observation;

import org.casemgmt.repo.ObservationInboxRepository;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The inbox acknowledgement is last: lifecycle application and evidence reconciliation precede it. */
class RemoteObservationInboxWorkerReconciliationTest {

    @Test
    void appliesDurableObservationBeforeReconcilingAndAcknowledgingIt() {
        ObservationInboxRepository inbox = mock(ObservationInboxRepository.class);
        EngineObservationHandler handler = mock(EngineObservationHandler.class);
        CompleteTaskObservationCommandReconciler reconciler =
                mock(CompleteTaskObservationCommandReconciler.class);
        UserTaskObservation observation = completed();
        ObservationInboxRepository.Claim claim = new ObservationInboxRepository.Claim(
                observation.fingerprint(), new ObservationEnvelope(observation).payload(), 1, "lease-1");
        when(inbox.claimDue(anyInt(), any(), any())).thenReturn(List.of(claim));
        when(handler.apply(observation)).thenReturn(new ApplyResult(observation.observationId(),
                ApplyStatus.APPLIED, 2L, List.of()));

        new RemoteObservationInboxWorker(inbox, handler, new LocalTransactionManager(), reconciler)
                .drainOnce();

        var ordered = inOrder(handler, reconciler, inbox);
        ordered.verify(handler).apply(observation);
        ordered.verify(reconciler).reconcile(observation);
        ordered.verify(inbox).markApplied(claim);
    }

    private static UserTaskObservation completed() {
        Instant now = Instant.parse("2026-08-30T10:00:00Z");
        return new UserTaskObservation("remote-task-task-42-COMPLETED", 1, "remote-history",
                "engine-west", "tenant-a", "case-1", "process-1", "task-42", null,
                UserTaskObservation.EventType.COMPLETED, now, now, Map.of("taskDefinitionKey", "review"));
    }

    private static final class LocalTransactionManager extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) { }
        @Override protected void doCommit(DefaultTransactionStatus status) { }
        @Override protected void doRollback(DefaultTransactionStatus status) { }
    }
}
