package org.casemgmt.starter;

import org.casemgmt.engine.EngineCommandDispatcher;
import org.casemgmt.event.WebhookDispatcher;
import org.casemgmt.sla.SlaSweeper;
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

    private final WebhookDispatcher webhooks;
    private final ObjectProvider<EngineCommandDispatcher> engineCommands;
    private final SlaSweeper sla;

    public CaseManagementSchedulers(WebhookDispatcher webhooks,
                                    ObjectProvider<EngineCommandDispatcher> engineCommands,
                                    SlaSweeper sla) {
        this.webhooks = webhooks;
        this.engineCommands = engineCommands;
        this.sla = sla;
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

    @Scheduled(fixedDelayString = "${casemgmt.schedulers.sla-sweep-interval-ms:60000}")
    public void sweepSlas() {
        sla.sweep();
    }
}
