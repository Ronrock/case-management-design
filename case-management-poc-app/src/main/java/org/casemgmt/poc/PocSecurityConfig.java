package org.casemgmt.poc;

import org.operaton.bpm.engine.IdentityService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

import java.util.List;

/**
 * Identity comes from Operaton's own user/group tables over HTTP basic auth (spec §7),
 * so participant roles and candidate groups behave exactly as the engine sees them.
 * Swapping in OAuth2 replaces this class and nothing else.
 */
@Configuration
public class PocSecurityConfig {

    @Bean
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
     * authentication; {@code RemoteModeComplaintIT} was extended (see its own Javadoc) to prove
     * the remote gateway's credentials are what let it through, not merely that the port answers.
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())          // no browser sessions: this is an API
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/case-api/v2/**", "/engine-rest/**").authenticated()
                        .anyRequest().permitAll())
                .httpBasic(basic -> { })
                .build();
    }
}
