package org.casemgmt.poc;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

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
}
