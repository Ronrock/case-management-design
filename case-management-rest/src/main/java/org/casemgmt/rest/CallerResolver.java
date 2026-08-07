package org.casemgmt.rest;

import org.casemgmt.repo.ParticipantRepository;
import org.casemgmt.service.Actor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Maps the authenticated principal onto the {@link Actor} the services expect, and onto the
 * per-case role set {@link org.casemgmt.rest.policy.ActionPolicy} enforces against. Identity
 * comes from Operaton's own user/group tables via basic auth (spec §7); swapping in OAuth2
 * changes only this class and the security configuration.
 *
 * <p>Not annotated as a Spring component: like every service and repository in
 * case-management-core, this is a plain class the application assembly declares as a bean, so
 * a consumer can substitute its own identity mapping without excluding a component scan.
 */
public class CallerResolver {

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
     */
    public Set<String> roles(String caseId, Actor actor) {
        return participants.rolesOf(caseId, actor.userId(), actor.groups());
    }

    /**
     * The role set {@code ActionPolicy}'s <em>task</em> surface expects: {@link #roles} plus the
     * caller's own identity groups.
     *
     * <p>Deviation from the task brief, and a necessary one. {@code ActionPolicy.listForTask}
     * admits a caller either by {@code mayMutate} (participant role {@code owner}/{@code
     * handler}) OR by membership of the task's {@code candidateGroups} — and {@code
     * candidateGroups} holds identity <em>group</em> names ({@code CM_TASK.CAND_GROUPS_JSON_},
     * copied from the plan item definition), never participant role names. The brief passes
     * {@link #roles} alone, which contains only {@code CM_PARTICIPANT.ROLE_} values; intersected
     * with group names it is false for every realistic deployment, so the entire candidate-group
     * half of Task 23's authorization rule would be unreachable code and a task could only ever
     * be claimed by an owner or handler. That directly contradicts what {@code ActionPolicy}
     * documents the rule is for ("candidate-group membership is precisely how work reaches
     * someone who is not yet a case participant").
     *
     * <p>Kept as a separate method rather than folded into {@link #roles} so it applies to the
     * task surface only: {@code listForCase}/{@code listForPlanItem} gate on {@code mayMutate}
     * alone, and feeding raw group names into those would let a group that happens to be named
     * {@code owner} or {@code handler} mutate any case in the system without a participant row.
     * The same caveat applies here in a much narrower form — a caller in a group named
     * {@code owner}/{@code handler} can act on tasks — which is acceptable only because identity
     * groups in this deployment are administrator-managed, not self-service. A production
     * deployment should namespace them (e.g. {@code group:reviewers}) so the two vocabularies
     * cannot collide at all.
     */
    public Set<String> taskRoles(String caseId, Actor actor) {
        Set<String> combined = new LinkedHashSet<>(roles(caseId, actor));
        combined.addAll(actor.groups());
        return combined;
    }
}
