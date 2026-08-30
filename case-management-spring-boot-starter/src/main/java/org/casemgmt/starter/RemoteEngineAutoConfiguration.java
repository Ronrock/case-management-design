package org.casemgmt.starter;

import org.casemgmt.engine.EngineCommandDispatcher;
import org.casemgmt.engine.EngineGateway;
import org.casemgmt.engine.OutboxEngineGateway;
import org.casemgmt.engine.remote.RemoteEngineGateway;
import org.casemgmt.engine.remote.RemoteObservationPoller;
import org.casemgmt.engine.remote.RemoteProcessActivityClassifier;
import org.casemgmt.orchestration.OrchestrationDeploymentPort;
import org.casemgmt.orchestration.OutboxOrchestrationDeploymentPort;
import org.casemgmt.repo.EngineCommandRepository;
import org.casemgmt.projection.ActiveBpmnCaseRepository;
import org.casemgmt.projection.CaseProjectionPort;
import org.casemgmt.projection.RemotePollingCheckpointRepository;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.web.client.RestClient;

import java.util.Optional;

@AutoConfiguration(before = CaseManagementAutoConfiguration.class)
// Fix round 1, Important 1: the master switch guard. Without it, casemgmt.enabled=false with
// casemgmt.engine.mode=remote still set (a plausible leftover config, not just a hypothetical)
// left this class active, and its engineRestClient bean demands the CaseManagementProperties
// bean that only CaseManagementAutoConfiguration registers — itself switched off by enabled=false
// — so startup failed with a bean-resolution error instead of the documented "completely
// untouched" behaviour.
@ConditionalOnProperty(prefix = "casemgmt", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(prefix = "casemgmt.engine", name = "mode", havingValue = "remote")
public class RemoteEngineAutoConfiguration {

    @Bean
    public RestClient engineRestClient(CaseManagementProperties props,
                                       Optional<RemoteEngineBearerTokenProvider> bearerTokenProvider) {
        CaseManagementProperties.Engine.Remote remote = props.getEngine().getRemote();
        String baseUrl = remote.getBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException(
                    "casemgmt.engine.mode=remote requires casemgmt.engine.remote.base-url");
        }
        // Timeouts are not optional here (K1, carried from Task 12). Catching RestClientException
        // handles an engine that REFUSES connections, but an engine that is up and hung answers
        // nothing at all: without a read timeout the calling thread blocks forever, no exception
        // is thrown, and the command outbox never gets to make its retry-versus-dead-letter
        // decision. Both bounds are configurable (casemgmt.engine.remote.connect-timeout-ms /
        // read-timeout-ms) rather than hardcoded, defaulting to the same 5s/10s precedent
        // WebhookDispatcher already established in this codebase for exactly this failure mode.
        var requestFactory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(timeoutMillis("connect-timeout-ms",
                remote.getConnectTimeoutMs()));
        requestFactory.setReadTimeout(timeoutMillis("read-timeout-ms", remote.getReadTimeoutMs()));

        RestClient.Builder builder = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory);

        builder = applyAuthentication(builder, remote, bearerTokenProvider);
        return builder.build();
    }

    private static RestClient.Builder applyAuthentication(
            RestClient.Builder builder,
            CaseManagementProperties.Engine.Remote remote,
            Optional<RemoteEngineBearerTokenProvider> bearerTokenProvider) {

        return switch (effectiveAuthMode(remote)) {
            case none -> builder;
            case auto -> throw new IllegalStateException("auth-mode auto should have been resolved");
            case basic -> {
                if (!hasText(remote.getUsername()) || remote.getPassword() == null) {
                    throw new IllegalStateException("casemgmt.engine.remote.username/password "
                            + "must be set when casemgmt.engine.remote.auth-mode=basic");
                }
                yield builder.defaultHeaders(h -> h.setBasicAuth(remote.getUsername(),
                        remote.getPassword()));
            }
            case bearer -> {
                RemoteEngineBearerTokenProvider provider = bearerTokenProvider.orElseGet(() -> {
                    String configuredToken = remote.getBearerToken();
                    if (!hasText(configuredToken)) {
                        throw new IllegalStateException("casemgmt.engine.remote.auth-mode=bearer "
                                + "requires a RemoteEngineBearerTokenProvider bean or "
                                + "casemgmt.engine.remote.bearer-token");
                    }
                    return () -> configuredToken;
                });
                yield builder.requestInterceptor((request, body, execution) -> {
                    String token = provider.bearerToken();
                    if (!hasText(token)) {
                        throw new IllegalStateException("RemoteEngineBearerTokenProvider returned "
                                + "an empty bearer token");
                    }
                    request.getHeaders().setBearerAuth(token);
                    return execution.execute(request, body);
                });
            }
        };
    }

    private static CaseManagementProperties.Engine.Remote.AuthMode effectiveAuthMode(
            CaseManagementProperties.Engine.Remote remote) {
        CaseManagementProperties.Engine.Remote.AuthMode mode = remote.getAuthMode();
        if (mode != CaseManagementProperties.Engine.Remote.AuthMode.auto) {
            return mode;
        }
        return hasText(remote.getUsername())
                ? CaseManagementProperties.Engine.Remote.AuthMode.basic
                : CaseManagementProperties.Engine.Remote.AuthMode.none;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static int timeoutMillis(String property, long value) {
        if (value < 0) {
            throw new IllegalStateException("casemgmt.engine.remote." + property
                    + " must be zero or greater; got " + value);
        }
        try {
            return Math.toIntExact(value);
        } catch (ArithmeticException e) {
            throw new IllegalStateException("casemgmt.engine.remote." + property
                    + " must fit in a 32-bit millisecond timeout; got " + value, e);
        }
    }

    /** The real gateway, used only by the dispatcher — never by a request thread. */
    @Bean
    public RemoteEngineGateway remoteEngineGateway(RestClient engineRestClient) {
        return new RemoteEngineGateway(engineRestClient);
    }

    @Bean
    public RemoteObservationPoller remoteObservationPoller(
            RestClient engineRestClient, ActiveBpmnCaseRepository activeCases,
            org.casemgmt.repo.CaseRepository cases, RemoteProcessActivityClassifier classifier,
            org.casemgmt.observation.RemoteObservationIngestionService ingestion,
            org.casemgmt.observation.RemoteObservationInboxWorker inboxWorker) {
        return new RemoteObservationPoller(engineRestClient, activeCases, cases, classifier,
                ingestion, inboxWorker);
    }

    @Bean
    public RemoteProcessActivityClassifier remoteProcessActivityClassifier(
            RestClient engineRestClient) {
        return new RemoteProcessActivityClassifier(engineRestClient);
    }

    /**
     * What the services get: writes commands in the local transaction (spec §3.5).
     *
     * <p>{@code @Primary} is a fix beyond the brief: {@code RemoteEngineGateway} itself
     * implements {@link EngineGateway} (deliberately, so {@code EngineCommandDispatcher} can
     * call it directly), so without a tie-breaker, every plain {@code EngineGateway} injection
     * point in {@code CaseManagementAutoConfiguration} (e.g. {@code transitionApplier}) fails to
     * start in remote mode with {@code NoUniqueBeanDefinitionException: expected single matching
     * bean but found 2: remoteEngineGateway,outboxEngineGateway} — confirmed by running
     * {@code AutoConfigurationTest.remoteModeRegistersTheOutboxGateway} without this annotation.
     * {@code remoteEngineGateway} itself is still injected by its own concrete type wherever a
     * caller (only {@link #engineCommandDispatcher}) asks for {@code RemoteEngineGateway}
     * specifically, so this does not change which bean the dispatcher's delegate is.
     */
    @Bean
    @Primary
    public EngineGateway outboxEngineGateway(EngineCommandRepository commands) {
        return new OutboxEngineGateway(commands, id -> { });
    }

    @Bean
    public OrchestrationDeploymentPort remoteOrchestrationDeploymentPort(
            EngineCommandRepository commands) {
        return new OutboxOrchestrationDeploymentPort(commands);
    }

    /**
     * Task confirmations remain single-row sync reports. Process start success takes the richer
     * callback so the linked row and, for a root, the owning case receive the real engine identity
     * in one transaction. A definitive process-start failure updates only the waiting link state.
     */
    @Bean
    public org.casemgmt.observation.CommandConfirmationLifecycleReporter commandConfirmationLifecycleReporter(
            org.casemgmt.repo.CaseRepository cases,
            org.casemgmt.repo.LinkedProcessRepository processes,
            org.casemgmt.observation.EngineObservationHandler lifecycle) {
        return new org.casemgmt.observation.CommandConfirmationLifecycleReporter(cases, processes,
                lifecycle);
    }

    @Bean
    public EngineCommandDispatcher engineCommandDispatcher(
            EngineCommandRepository commands, RemoteEngineGateway delegate,
            org.casemgmt.event.EventPublisher events,
            org.casemgmt.observation.CommandConfirmationLifecycleReporter lifecycleReporter) {
        return new EngineCommandDispatcher(commands, delegate,
                "remote-dispatcher-" + java.util.UUID.randomUUID(),
                java.time.Clock.systemUTC(), java.time.Duration.ofMinutes(5), events,
                lifecycleReporter::confirmed);
    }
}
