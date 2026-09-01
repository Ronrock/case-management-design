package org.casemgmt.starter;

import org.casemgmt.engine.EngineCommandDispatcher;
import org.casemgmt.engine.remote.RemoteObservationPoller;
import org.casemgmt.event.WebhookDispatcher;
import org.casemgmt.repo.IdempotencyRepository;
import org.casemgmt.sla.SlaSweeper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@AutoConfiguration(after = CaseManagementAutoConfiguration.class)
// Fix round 1, Important 1: the master switch guard, matching EmbeddedEngineAutoConfiguration
// and CaseManagementAutoConfiguration. Without it, casemgmt.enabled=false left this class active
// (it only checked casemgmt.schedulers.enabled), demanding WebhookDispatcher and SlaSweeper as
// hard constructor dependencies that no longer exist once CaseManagementAutoConfiguration itself
// is switched off — turning "leaves a plain Operaton app completely untouched"
// (CaseManagementProperties' own Javadoc for casemgmt.enabled) into a startup failure instead.
@ConditionalOnProperty(prefix = "casemgmt", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(prefix = "casemgmt.schedulers", name = "enabled", havingValue = "true",
        matchIfMissing = true)
@EnableScheduling
public class CaseManagementSchedulers {

    private static final Logger log = LoggerFactory.getLogger(CaseManagementSchedulers.class);

    private final WebhookDispatcher webhooks;
    private final ObjectProvider<EngineCommandDispatcher> engineCommands;
    private final ObjectProvider<RemoteObservationPoller> remoteObservations;
    private final SlaSweeper sla;
    private final IdempotencyRepository idempotency;
    private final CaseManagementProperties properties;

    public CaseManagementSchedulers(WebhookDispatcher webhooks,
                                    ObjectProvider<EngineCommandDispatcher> engineCommands,
                                    ObjectProvider<RemoteObservationPoller> remoteObservations,
                                    SlaSweeper sla, IdempotencyRepository idempotency,
                                    CaseManagementProperties properties) {
        this.webhooks = webhooks;
        this.engineCommands = engineCommands;
        this.remoteObservations = remoteObservations;
        this.sla = sla;
        this.idempotency = idempotency;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${casemgmt.schedulers.webhook-interval-ms:5000}")
    public void dispatchWebhooks() {
        webhooks.drainOnce();
    }

    /** Only present in remote mode; ObjectProvider keeps this a no-op in embedded mode. */
    @Scheduled(fixedDelayString = "${casemgmt.schedulers.engine-command-interval-ms:5000}")
    public void dispatchEngineCommands() {
        engineCommands.ifAvailable(EngineCommandDispatcher::drainOnce);
    }

    @Scheduled(fixedDelayString = "${casemgmt.schedulers.engine-poll-interval-ms:5000}")
    public void pollRemoteEngine() {
        remoteObservations.ifAvailable(RemoteObservationPoller::pollOnce);
    }

    @Scheduled(fixedDelayString = "${casemgmt.schedulers.engine-reconcile-interval-ms:300000}")
    public void reconcileRemoteEngine() {
        remoteObservations.ifAvailable(RemoteObservationPoller::reconcileAllActive);
    }

    @Scheduled(fixedDelayString = "${casemgmt.schedulers.sla-sweep-interval-ms:60000}")
    public void sweepSlas() {
        sla.sweep();
    }

    /**
     * Spec §6.4's 48h idempotency-key retention (final whole-branch review, Important 5).
     *
     * <p>{@code IdempotencyRepository.purgeOlderThanHours} existed, was documented ("Retention:
     * 48h, per spec §6.4. Call from a scheduled job"), and had no caller anywhere — grep found
     * only its own Javadoc and a test comment. This class scheduled webhooks, engine commands
     * and SLA, but not this, so {@code CM_IDEMPOTENCY_KEY} grew one row per create, forever, on
     * every deployment.
     *
     * <p>Both numbers are properties consistent with the three schedulers above
     * ({@code casemgmt.schedulers.idempotency-purge-interval-ms} and
     * {@code ...idempotency-retention-hours}), rather than the literals the sibling methods'
     * intervals would suggest, because the retention window is a contract figure a deployment
     * may legitimately need to change.
     *
     * @return how many rows were deleted, so a caller (and this method's own log line) can see
     *         the sweep doing real work rather than assume it
     */
    @Scheduled(fixedDelayString =
            "${casemgmt.schedulers.idempotency-purge-interval-ms:3600000}")
    public int purgeIdempotencyKeys() {
        int purged = idempotency.purgeOlderThanHours(
                properties.getSchedulers().getIdempotencyRetentionHours());
        if (purged > 0) {
            log.debug("Purged {} idempotency key(s) older than {}h", purged,
                    properties.getSchedulers().getIdempotencyRetentionHours());
        }
        return purged;
    }
}
