package org.casemgmt.poc;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class PocJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final PocSecurityProperties.Oidc properties;

    public PocJwtAuthenticationConverter(PocSecurityProperties.Oidc properties) {
        this.properties = properties;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Set<GrantedAuthority> authorities = new LinkedHashSet<>();
        addAll(authorities, claimValues(jwt, properties.getGroupsClaim()), "");
        addAll(authorities, claimValues(jwt, properties.getWorkerPermissionsClaim()),
                nullToEmpty(properties.getWorkerPermissionAuthorityPrefix()));
        addAll(authorities, claimValues(jwt, properties.getEnginePermissionsClaim()),
                nullToEmpty(properties.getEnginePermissionAuthorityPrefix()));

        String tenant = jwt.getClaimAsString(properties.getTenantClaim());
        if (tenant != null && !tenant.isBlank()) {
            authorities.add(new SimpleGrantedAuthority(
                    tenant.startsWith("tenant:") ? tenant : "tenant:" + tenant));
        }

        String principal = jwt.getClaimAsString(properties.getPrincipalClaim());
        if (principal == null || principal.isBlank()) {
            principal = jwt.getSubject();
        }
        return new JwtAuthenticationToken(jwt, authorities, principal);
    }

    private static void addAll(Set<GrantedAuthority> authorities, List<String> values, String prefix) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                authorities.add(new SimpleGrantedAuthority(prefix + value));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> claimValues(Jwt jwt, String claim) {
        if (claim == null || claim.isBlank() || !jwt.hasClaim(claim)) {
            return List.of();
        }
        Object value = jwt.getClaim(claim);
        if (value instanceof String s) {
            return s.isBlank() ? List.of() : List.of(s);
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream().map(String::valueOf).toList();
        }
        return List.of(String.valueOf(value));
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
