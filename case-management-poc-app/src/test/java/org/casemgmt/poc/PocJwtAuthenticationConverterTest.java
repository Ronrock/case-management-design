package org.casemgmt.poc;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PocJwtAuthenticationConverterTest {

    @Test
    void mapsOidcTenantGroupsAndWorkerPermissionsToCaseAuthorities() {
        PocSecurityProperties properties = new PocSecurityProperties();
        Jwt jwt = new Jwt("token", Instant.now(), Instant.now().plusSeconds(60),
                Map.of("alg", "none"),
                Map.of("sub", "alice",
                        "tenant", "t1",
                        "groups", List.of("reviewers"),
                        "worker_permissions", List.of("admin", "case:write")));

        var authentication = new PocJwtAuthenticationConverter(properties.getOidc()).convert(jwt);

        assertThat(authentication.getName()).isEqualTo("alice");
        assertThat(authentication.getAuthorities())
                .extracting(Object::toString)
                .contains("tenant:t1", "reviewers", "admin", "case:write");
    }

    @Test
    void engineAccessRequiresTheDedicatedClaimInOidcMode() {
        PocSecurityProperties properties = new PocSecurityProperties();
        properties.setMode(PocSecurityProperties.Mode.oidc);
        Jwt jwt = new Jwt("token", Instant.now(), Instant.now().plusSeconds(60),
                Map.of("alg", "none"),
                Map.of("sub", "admin", "groups", List.of("engine-api")));

        var groupOnly = new PocJwtAuthenticationConverter(properties.getOidc()).convert(jwt);

        assertThat(PocSecurityConfig.canWriteEngine(groupOnly, properties)).isFalse();

        Jwt integrationJwt = new Jwt("token", Instant.now(), Instant.now().plusSeconds(60),
                Map.of("alg", "none"),
                Map.of("sub", "service-account", "engine_permissions", List.of("api")));
        var integration = new PocJwtAuthenticationConverter(properties.getOidc()).convert(integrationJwt);

        assertThat(integration.getAuthorities()).extracting(Object::toString).contains("engine:api");
        assertThat(PocSecurityConfig.canWriteEngine(integration, properties)).isTrue();
    }

    @Test
    void configuredBasicIntegrationPrincipalDoesNotGrantOidcAccessByName() {
        PocSecurityProperties properties = new PocSecurityProperties();
        var admin = UsernamePasswordAuthenticationToken.authenticated("admin", "n/a",
                List.of(new SimpleGrantedAuthority("users")));

        assertThat(PocSecurityConfig.canWriteEngine(admin, properties)).isTrue();

        properties.setMode(PocSecurityProperties.Mode.oidc);
        assertThat(PocSecurityConfig.canWriteEngine(admin, properties)).isFalse();
    }
}
