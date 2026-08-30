package org.casemgmt.observation;

import org.casemgmt.repo.ObservationCheckpointRepository;
import org.casemgmt.repo.ObservationInboxRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The atomic receive boundary for one remote-history page.  The checkpoint is deliberately
 * advanced only after every envelope in that page has been inserted (or deduplicated) into the
 * durable inbox.  A rollback therefore leaves both the page and cursor untouched.
 */
public class RemoteObservationIngestionService {
    private final ObservationInboxRepository inbox;
    private final ObservationCheckpointRepository checkpoints;

    public RemoteObservationIngestionService(ObservationInboxRepository inbox,
                                             ObservationCheckpointRepository checkpoints) {
        this.inbox = Objects.requireNonNull(inbox, "inbox");
        this.checkpoints = Objects.requireNonNull(checkpoints, "checkpoints");
    }

    @Transactional
    public int persistPage(String tenantId, ObservationStream stream,
                           List<ObservationEnvelope> envelopes, ObservationCursor last) {
        if (envelopes.isEmpty()) return 0;
        int inserted = 0;
        for (ObservationEnvelope envelope : envelopes) {
            if (inbox.enqueue(tenantId, stream, envelope)) inserted++;
        }
        checkpoints.advance(tenantId, stream, last);
        return inserted;
    }

    /** Reconciliation is evidence ingestion too, but is not a history-feed cursor. */
    @Transactional
    public int persist(String tenantId, ObservationStream stream,
                       List<ObservationEnvelope> envelopes) {
        int inserted = 0;
        for (ObservationEnvelope envelope : envelopes) {
            if (inbox.enqueue(tenantId, stream, envelope)) inserted++;
        }
        return inserted;
    }

    public Optional<ObservationCursor> oldestCursor(ObservationStream stream) {
        return checkpoints.findOldest(stream);
    }
}
