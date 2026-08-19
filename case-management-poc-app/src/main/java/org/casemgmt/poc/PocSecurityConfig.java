package org.casemgmt.poc;

import org.operaton.bpm.engine.IdentityService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

import java.util.List;

/**
 * Local mode can still read identity from Operaton's own user/group tables over HTTP Basic.
 * OIDC mode validates bearer JWTs and maps tenant, group and Worker Permissions claims onto
 * the authorities the reusable case API already consumes.
 */
@Configuration
@EnableConfigurationProperties(PocSecurityProperties.class)
public class PocSecurityConfig {

    @Bean
    @ConditionalOnProperty(prefix = "casemgmt.security", name = "mode",
            havingValue = "basic", matchIfMissing = true)
    public AuthenticationProvider operatonAuthenticationProvider(IdentityService identityService) {
        return new AuthenticationProvider() {
            @Override
            public Authentication authenticate(Authentication authentication) throws AuthenticationException {
                String username = authentication.getName();
                String password = String.valueOf(authentication.getCredentials());

                if (!identityService.checkPassword(username, password)) {
                    throw new BadCredentialsException("Unknown user or bad password: " + username);
                }
                List<SimpleGrantedAuthority> groups = identityService.createGroupQuery()
                        .groupMember(username).list().stream()
                        .map(g -> new SimpleGrantedAuthority(g.getId()))
                        .toList();
                return new UsernamePasswordAuthenticationToken(username, password, groups);
            }

            @Override
            public boolean supports(Class<?> authentication) {
                return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
            }
        };
    }

    /**
     * Fix round 1, Important 4 (review): {@code /engine-rest/**} used to fall through to {@code
     * .anyRequest().permitAll()} — the brief's own config, verbatim. On a runnable app that also
     * seeds a real {@code admin} user, that meant anyone who could reach the port could complete
     * tasks, deploy processes and read history through Operaton's own REST API, bypassing every
     * role, tenant and {@code If-Match} check the case API enforces — and it meant the remote
     * gateway's basic-auth credentials ({@code casemgmt.engine.remote.username/password}, sent by
     * {@code RemoteEngineAutoConfiguration.engineRestClient}) were never actually checked by
     * anything. Both {@code /case-api/v2/**} and {@code /engine-rest/**} now require
     * authentication; writes to {@code /engine-rest/**} are additionally reserved for the
     * configured basic-mode integration identity or {@code engine:api}, which is mapped only from
     * the dedicated OIDC {@code engine_permissions} claim. User names and ordinary group claims
     * cannot grant this access.
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   PocSecurityProperties properties) throws Exception {
        http.csrf(csrf -> csrf.disable())          // no browser sessions: this is an API
                .authorizeHttpRequests(auth -> auth
                    .requestMatchers(HttpMethod.GET, "/engine-rest/**").authenticated()
                    .requestMatchers("/engine-rest/**").access((authentication, context) -> {
                        Authentication current = authentication.get();
                        return new AuthorizationDecision(canWriteEngine(current, properties));
                    })
                    .requestMatchers("/case-api/v2/**").authenticated()
                    .anyRequest().permitAll());

        if (properties.getMode() == PocSecurityProperties.Mode.oidc) {
            http.oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt
                    .jwtAuthenticationConverter(
                            new PocJwtAuthenticationConverter(properties.getOidc()))));
        } else {
            http.httpBasic(basic -> { });
        }
        return http.build();
    }

    static boolean canWriteEngine(Authentication current, PocSecurityProperties properties) {
        if (current == null || !current.isAuthenticated()) {
            return false;
        }
        boolean localIntegration = properties.getMode() == PocSecurityProperties.Mode.basic
                && properties.getEngineIntegrationPrincipal().equals(current.getName());
        boolean claimedIntegration = current.getAuthorities().stream()
                .anyMatch(a -> "engine:api".equals(a.getAuthority()));
        return localIntegration || claimedIntegration;
    }
}
