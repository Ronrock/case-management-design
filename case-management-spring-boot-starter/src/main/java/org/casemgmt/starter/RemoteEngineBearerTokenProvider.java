package org.casemgmt.starter;

/**
 * Supplies bearer tokens for the remote Operaton engine client.
 *
 * <p>Production deployments should implement this from the bank's OIDC/client-credential
 * integration instead of storing long-lived engine credentials in application configuration.
 */
@FunctionalInterface
public interface RemoteEngineBearerTokenProvider {
    String bearerToken();
}
