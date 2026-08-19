package org.casemgmt.starter;

import org.casemgmt.event.WebhookDispatcher;
import org.casemgmt.event.WebhookSecretCodec;
import org.casemgmt.event.WebhookSecretStore;
import org.casemgmt.repo.AuditRepository;
import org.casemgmt.repo.DatabaseWebhookSecretStore;
import org.casemgmt.repo.EventRepository;
import org.casemgmt.repo.WebhookRepository;
import org.casemgmt.service.WebhookService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class CaseManagementWebhookConfiguration {

    @Bean
    @ConditionalOnMissingBean(WebhookSecretStore.class)
    public WebhookSecretStore webhookSecretStore(WebhookRepository webhooks,
                                                 ObjectProvider<WebhookSecretCodec> codecs,
                                                 CaseManagementProperties props) {
        WebhookSecretCodec codec = codecs.getIfAvailable(() ->
                org.casemgmt.event.AesGcmWebhookSecretCodec.fromBase64(
                        props.getWebhooks().getSecretKeyId(),
                        props.getWebhooks().getSecretEncryptionKey()));
        return new DatabaseWebhookSecretStore(webhooks, codec);
    }

    @Bean
    public WebhookService webhookService(WebhookRepository webhooks, WebhookSecretStore secrets,
                                         AuditRepository audit) {
        return new WebhookService(webhooks, secrets, audit);
    }

    @Bean
    public WebhookDispatcher webhookDispatcher(WebhookRepository webhooks, EventRepository events,
                                               WebhookSecretStore secrets) {
        return new WebhookDispatcher(webhooks, events, id -> secrets.find(id).orElse(null));
    }
}
