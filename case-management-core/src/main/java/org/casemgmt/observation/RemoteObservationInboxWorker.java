package org.casemgmt.observation;

import org.casemgmt.repo.ObservationInboxRepository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;

/** Applies leased inbox rows through the one canonical lifecycle handler. */
public class RemoteObservationInboxWorker {
    private static final int POISON_AFTER = 5;
    private final ObservationInboxRepository inbox;
    private final EngineObservationHandler handler;
    private final TransactionTemplate transactions;

    public RemoteObservationInboxWorker(ObservationInboxRepository inbox,
                                        EngineObservationHandler handler,
                                        PlatformTransactionManager transactionManager) {
        this.inbox = Objects.requireNonNull(inbox, "inbox");
        this.handler = Objects.requireNonNull(handler, "handler");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager,
                "transactionManager"));
    }

    public int drainOnce() { return drainOnce(100); }

    public int drainOnce(int limit) {
        if (limit < 1) throw new IllegalArgumentException("limit must be positive");
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        List<ObservationInboxRepository.Claim> claims = transactions.execute(status ->
                inbox.claimDue(limit, now, now.plusMinutes(5)));
        if (claims == null) return 0;
        for (ObservationInboxRepository.Claim claim : claims) apply(claim);
        return claims.size();
    }

    private void apply(ObservationInboxRepository.Claim claim) {
        try {
            // Handler owns its lifecycle transaction.  The acknowledgement is only committed
            // after that transaction succeeds, so a crash/retry is harmless via fingerprinting.
            handler.apply(ObservationEnvelope.decode(claim.payload()).observation());
            transactions.executeWithoutResult(status -> inbox.markApplied(claim));
        } catch (RuntimeException failure) {
            transactions.executeWithoutResult(status ->
                    inbox.markFailed(claim, failure.getMessage(), POISON_AFTER));
        }
    }
}
