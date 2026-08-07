package org.casemgmt.rest;

import org.casemgmt.error.NotFoundException;
import org.casemgmt.repo.ParticipantRepository;
import org.casemgmt.rest.error.ForbiddenException;
import org.casemgmt.service.Actor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Maps the authenticated principal onto the {@link Actor} the services expect, onto the per-case
 * role set {@link org.casemgmt.rest.policy.ActionPolicy} enforces against, and onto the tenant
 * every request runs under. Identity comes from Operaton's own user/group tables via basic auth
 * (spec §7); swapping in OAuth2 changes only this class and the security configuration.
 *
 * <p>Not annotated as a Spring component: like every service and repository in
 * case-management-core, this is a plain class the application assembly declares as a bean, so
 * a consumer can substitute its own identity mapping without excluding a component scan.
 */
public class CallerResolver {

    /**
     * Identity-group prefix that carries the caller's tenant, e.g. {@code tenant:t1}.
     *
     * <p>The tenant is derived from the principal and from nothing else (Task 24 fix round 1,
     * review finding Critical 2). It used to be taken from request bodies and query parameters,
     * which made {@code POST /webhooks} a cross-tenant exfiltration primitive: any authenticated
     * user could register their own endpoint against another tenant and receive that tenant's
     * case events continuously. A prefixed group is the smallest mechanism that carries a tenant
     * through Operaton's existing identity model without a schema change; a production
     * deployment would more likely read it from an OAuth2 claim, which changes this method and
     * nothing else.
     */
    private static final String TENANT_GROUP_PREFIX = "tenant:";

    private final ParticipantRepository participants;

    public CallerResolver(ParticipantRepository participants) {
        this.participants = participants;
    }

    public Actor actor(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            // Every endpoint is behind authentication; reaching a controller without a
            // principal means the security configuration and the controllers disagree, which
            // is a deployment fault, not a client error worth a tailored problem response.
            throw new IllegalStateException("No authenticated principal on the request");
        }
        List<String> groups = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .map(a -> a.startsWith("ROLE_") ? a.substring(5) : a)
                .toList();
        return new Actor(authentication.getName(), groups);
    }

    /**
     * Participant roles the caller holds on this case, directly or through one of their groups.
     * An empty set is the normal answer for a caller who is not a participant, and is exactly
     * what {@code ActionPolicy} turns into "no actions available" — the deny half of the
     * authorization rule, not an error.
     *
     * <p>These are {@code CM_PARTICIPANT.ROLE_} values and nothing else. The caller's identity
     * groups are a separate vocabulary and reach {@code ActionPolicy} as a separate argument —
     * see {@link #groups} and {@code ActionPolicy.mayActOnTask}.
     */
    public Set<String> roles(String caseId, Actor actor) {
        return participants.rolesOf(caseId, actor.userId(), actor.groups());
    }

    /** {@link #roles} for a page of cases, in two queries rather than two per case. */
    public Map<String, Set<String>> roles(Collection<String> caseIds, Actor actor) {
        return participants.rolesOf(caseIds, actor.userId(), actor.groups());
    }

    /**
     * The caller's identity groups — the vocabulary {@code CM_TASK.CAND_GROUPS_JSON_} and
     * {@code ActionPolicy}'s administration rule are expressed in. Never mixed with
     * {@link #roles}: see {@code ActionPolicy.mayActOnTask}'s Javadoc for the escalation that
     * merging the two allowed.
     */
    public Set<String> groups(Actor actor) {
        return Set.copyOf(actor.groups());
    }

    /**
     * The tenant this caller acts in, from their {@code tenant:<id>} identity group.
     *
     * @throws ForbiddenException if the principal carries no tenant, or more than one. Both are
     *         configuration errors, and both are refused rather than guessed: defaulting to
     *         "no tenant filter" would silently reopen exactly the cross-tenant hole this
     *         mechanism exists to close.
     */
    public String tenantId(Actor actor) {
        List<String> tenants = actor.groups().stream()
                .filter(g -> g.startsWith(TENANT_GROUP_PREFIX))
                .map(g -> g.substring(TENANT_GROUP_PREFIX.length()))
                .distinct()
                .toList();
        if (tenants.size() != 1) {
            throw new ForbiddenException(tenants.isEmpty()
                    ? "User '" + actor.userId() + "' has no tenant assignment"
                    : "User '" + actor.userId() + "' is assigned to more than one tenant: " + tenants);
        }
        return tenants.get(0);
    }

    /**
     * Resolves the tenant a listing should run under: the caller's own, and only that.
     *
     * <p>A {@code tenantId} query parameter is still accepted — it is in the spec and a caller
     * may legitimately state which tenant they mean — but it can only ever narrow to the tenant
     * they already have. Asking for another one is refused outright rather than silently
     * rewritten to their own: quietly ignoring a filter a client sent is how a client ends up
     * believing it saw another tenant's data and finding none.
     */
    public String requireTenant(Actor actor, String requested) {
        String own = tenantId(actor);
        if (requested != null && !requested.isBlank() && !requested.equals(own)) {
            throw new ForbiddenException("User '" + actor.userId() + "' cannot access tenant '"
                    + requested + "'");
        }
        return own;
    }

    /**
     * Gate for a resource fetched by a caller-supplied id.
     *
     * <p>Reports another tenant's resource as {@link NotFoundException} — 404, not 403 — on
     * purpose: a 403 on an id the caller guessed confirms that the id exists, which is an
     * existence oracle across a tenant boundary. From this caller's position the resource
     * genuinely does not exist.
     */
    public void requireVisible(String resourceType, String resourceId, String resourceTenantId,
                               Actor actor) {
        if (!tenantId(actor).equals(resourceTenantId)) {
            throw new NotFoundException(resourceType, resourceId);
        }
    }
}
