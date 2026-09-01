package org.casemgmt.service;

import org.casemgmt.domain.*;
import org.casemgmt.error.CaseConflictException;
import org.casemgmt.error.NotFoundException;
import org.casemgmt.error.OptimisticLockException;
import org.casemgmt.event.CaseEvent;
import org.casemgmt.event.EventPublisher;
import org.casemgmt.event.EventTypes;
import org.casemgmt.orchestration.CaseOrchestration;
import org.casemgmt.orchestration.CaseOrchestrationRegistry;
import org.casemgmt.repo.*;
import org.casemgmt.rules.CaseSnapshot;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Case lifecycle for BPMN-backed cases. Operaton owns process sequencing and activity
 * lifecycle; this service owns the canonical case aggregate and delegates process lifecycle
 * commands through {@link CaseOrchestration}.
 *
 * <p>{@code @Transactional} here is genuine only when this bean is obtained through a
 * Spring-managed proxy (see {@code org.casemgmt.config.TransactionManagerConfig}) — the AOP
 * proxy is what actually opens/commits/rolls back the transaction. It does nothing for a plain
 * {@code new CaseService(...)} (which is how {@code TestServices} builds it for this module's
 * tests, same as every other repository test here).
 */
public class CaseService {

    private final CaseRepository cases;
    private final CaseDefinitionRepository definitions;
    private final PlanItemRepository planItems;
    private final ParticipantRepository participants;
    private final CaseOrchestrationRegistry orchestrations;
    private final EventPublisher publisher;
    private final String engineId;

    public CaseService(CaseRepository cases, CaseDefinitionRepository definitions,
                       PlanItemRepository planItems, ParticipantRepository participants,
                       CaseOrchestrationRegistry orchestrations,
                       EventPublisher publisher, String engineId) {
        this.cases = cases;
        this.definitions = definitions;
        this.planItems = planItems;
        this.participants = participants;
        this.orchestrations = orchestrations;
        this.publisher = publisher;
        this.engineId = engineId;
    }

    @Transactional
    public CaseInstance create(String caseDefKey, String tenantId, String businessKey, String title,
                               CasePriority priority, Map<String, Object> variables, Actor actor) {
        CaseDefinition def = definitions.findLatestStartable(caseDefKey, tenantId)
                .orElseGet(() -> {
                    if (definitions.findLatest(caseDefKey, tenantId).isPresent()) {
                        throw new CaseConflictException("case-definition-not-active",
                                "Case definition '" + caseDefKey
                                        + "' has no ACTIVE binding available for new cases",
                                List.of());
                    }
                    throw new NotFoundException("CaseDefinition", caseDefKey);
                });

        OffsetDateTime now = OffsetDateTime.now();
        CaseInstance created = new CaseInstance(CaseIds.newCaseId(engineId), engineId, tenantId,
                def.id(), def.key(), def.versionNo(), businessKey, title, CaseState.ACTIVE,
                priority == null ? CasePriority.MEDIUM : priority, null, null, actor.userId(),
                "NONE", null, null, variables == null ? Map.of() : variables, 0L, now, now, null);
        cases.insert(created);

        participants.insert(CaseIds.newId(), created.id(), actor.userId(), null, "owner");
        CaseOrchestration orchestration = orchestration(def);
        orchestration.onCaseCreated(created, def);

        publisher.publish(event(created, EventTypes.CASE_CREATED, Map.of(
                "caseDefinitionKey", def.key(), "state", created.state().name(),
                "businessKey", businessKey == null ? "" : businessKey)));
        publisher.audit(created.id(), tenantId, actor.userId(), "case.create", "Case",
                created.id(), null, Map.of("state", created.state().name(), "title", title));

        // Starting BPMN binds the root process and projection state onto CM_CASE. Return that
        // orchestrated row so the create response does not expose the pre-start snapshot.
        return cases.require(created.id());
    }

    public CaseInstance get(String caseId) {
        return cases.require(caseId);
    }

    public CaseSnapshot snapshot(String caseId) {
        return snapshot(cases.require(caseId));
    }

    /**
     * Builds a snapshot around a {@link CaseInstance} the caller already holds, instead of
     * re-reading {@code CM_CASE}.
     *
     * <p>Added by Task 24 for the REST layer, and for the same reason Task 4's rule exists: a
     * controller that has just performed a successful optimistic update holds the authoritative
     * post-write row and must not go back to the database for it. {@code availableActions[]} is
     * derived from the snapshot, so re-reading here would let a concurrent writer's row decide
     * which actions get advertised alongside a body built from <em>this</em> call's write —
     * the two would describe different versions of the case. Projected activities are still read
     * because they are not part of the row the caller wrote and may have advanced independently.
     */
    public CaseSnapshot snapshot(CaseInstance instance) {
        return new CaseSnapshot(instance, definitions.require(instance.caseDefId()),
                planItems.findByCase(instance.id()));
    }

    @SuppressWarnings("unchecked")
    @Transactional
    public CaseInstance update(String caseId, long expectedVersion, Map<String, Object> patch, Actor actor) {
        CaseInstance current = cases.require(caseId);
        requireLive(current, "update");

        CaseInstance patched = current;
        if (patch.containsKey("title")) {
            patched = new CaseInstance(patched.id(), patched.engineId(), patched.tenantId(),
                    patched.caseDefId(), patched.caseDefKey(), patched.caseDefVersion(),
                    patched.businessKey(), (String) patch.get("title"), patched.state(),
                    patched.priority(), patched.assignee(), patched.queueId(), patched.initiator(),
                    patched.slaStatus(), patched.outcome(), patched.cancelReason(),
                    patched.variables(), patched.version(), patched.createdAt(),
                    patched.updatedAt(), patched.closedAt());
        }
        if (patch.containsKey("variables")) {
            Object variablesPatch = patch.get("variables");
            patched = patched.withVariables(variablesPatch == null
                    ? Map.of()
                    : mergeObject(patched.variables(), (Map<String, Object>) variablesPatch));
        }

        CaseInstance saved = cases.update(patched, expectedVersion);
        publisher.publish(event(saved, EventTypes.CASE_UPDATED, Map.of("fields", patch.keySet())));
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("title", current.title());
        before.put("variables", current.variables());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("title", saved.title());
        after.put("variables", saved.variables());
        publisher.audit(caseId, saved.tenantId(), actor.userId(), "case.update", "Case", caseId,
                before, after);

        // `saved` is what CaseRepository.update already proved committed (see its Javadoc):
        // re-reading here would risk returning a concurrent writer's row as if it confirmed
        // this call's own write. The orchestration start never touches CM_CASE, so `saved` is
        // exactly correct.
        return saved;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mergeObject(Map<String, Object> current,
                                                   Map<String, Object> patch) {
        Map<String, Object> merged = new LinkedHashMap<>(current == null ? Map.of() : current);
        for (Map.Entry<String, Object> entry : patch.entrySet()) {
            if (entry.getValue() == null) {
                merged.remove(entry.getKey());
            } else if (entry.getValue() instanceof Map<?, ?> patchObject
                    && merged.get(entry.getKey()) instanceof Map<?, ?> currentObject) {
                merged.put(entry.getKey(), mergeObject((Map<String, Object>) currentObject,
                        (Map<String, Object>) patchObject));
            } else {
                merged.put(entry.getKey(), entry.getValue());
            }
        }
        return merged;
    }

    /** BPMN cases normally close only from the authoritative root-process observation. */
    @Transactional
    public CaseInstance close(String caseId, long expectedVersion, String outcome, Actor actor) {
        CaseInstance current = cases.require(caseId);
        requireTransitionAllowed(current, CaseState.CLOSED, "close");

        throw new CaseConflictException("explicit-close-not-supported",
                "BPMN cases close when their root process completes", List.of("cancel"));
    }

    @Transactional
    public CaseInstance cancel(String caseId, long expectedVersion, String reason, Actor actor) {
        // Serialize the API precondition and engine command with lifecycle observations. The
        // synchronous embedded handler acquires this same row lock first and participates in
        // this transaction, so no independently committed termination can be mistaken for this
        // request's callback between the version check and engine cancellation.
        cases.lockForObservation(caseId);
        CaseInstance current = cases.require(caseId);
        requireTransitionAllowed(current, CaseState.CANCELLED, "cancel");
        if (current.version() != expectedVersion) {
            throw new OptimisticLockException("Case", caseId, expectedVersion);
        }

        orchestration(definitions.require(current.caseDefId())).onCaseCancelled(current, reason);

        // Embedded Operaton callbacks run synchronously inside this transaction. Root process
        // termination therefore may already have performed the authoritative case transition,
        // version bump, projection sweep and CASE_CANCELLED publication. Re-read after returning
        // from orchestration and, in that case, add only the user's reason and intent audit at
        // the fresh version. Remote/no-callback orchestration still follows the service-owned
        // transition below.
        CaseInstance afterOrchestration = cases.require(caseId);
        if (afterOrchestration.state() == CaseState.CANCELLED) {
            // Root termination itself owns one version increment. Anything beyond that means a
            // different writer advanced the case after this request's precondition read; do not
            // hide that conflict merely because the synchronous callback also cancelled it.
            if (afterOrchestration.version() != expectedVersion + 1) {
                throw new OptimisticLockException("Case", caseId, expectedVersion);
            }
            CaseInstance saved = cases.updateCancellationReason(
                    afterOrchestration, reason, afterOrchestration.version());
            auditCancellationIntent(current, saved, reason, actor);
            return saved;
        }

        CaseInstance cancelled = new CaseInstance(current.id(), current.engineId(), current.tenantId(),
                current.caseDefId(), current.caseDefKey(), current.caseDefVersion(),
                current.businessKey(), current.title(), CaseState.CANCELLED, current.priority(),
                current.assignee(), current.queueId(), current.initiator(), current.slaStatus(),
                current.outcome(), reason, current.variables(), current.version(),
                current.createdAt(), current.updatedAt(), OffsetDateTime.now());

        CaseInstance saved = cases.update(cancelled, expectedVersion);
        publisher.publish(event(saved, EventTypes.CASE_CANCELLED, Map.of("reason", reason == null ? "" : reason)));
        auditCancellationIntent(current, saved, reason, actor);
        return saved;
    }

    private void auditCancellationIntent(CaseInstance current, CaseInstance saved,
                                         String reason, Actor actor) {
        // Same null-intolerance fix as close() above, for the same reason: POST
        // /cases/{id}/cancel also accepts no body (CaseController.cancel), so reason may be null.
        Map<String, Object> cancelAuditValues = new LinkedHashMap<>();
        cancelAuditValues.put("state", "CANCELLED");
        cancelAuditValues.put("reason", reason);
        publisher.audit(saved.id(), saved.tenantId(), actor.userId(), "case.cancel", "Case",
                saved.id(), Map.of("state", current.state().name()), cancelAuditValues);
    }

    private CaseOrchestration orchestration(CaseDefinition definition) {
        return orchestrations.require(definition.orchestrationMode());
    }

    private void requireLive(CaseInstance c, String action) {
        if (c.state() == CaseState.CLOSED || c.state() == CaseState.CANCELLED) {
            throw new CaseConflictException("illegal-state",
                    "Cannot " + action + " a case in state " + c.state(),
                    availableActionsFrom(c.state()));
        }
    }

    /**
     * Validates a close/cancel request against {@link CaseState#canTransitionTo}, the domain's
     * own state machine, rather than a hand-rolled "not CLOSED/CANCELLED" check: unlike
     * {@link #requireLive} (used by {@link #update}, which is not itself a state transition),
     * close and cancel each target a specific state, and the transition table already encodes
     * the real rule — for instance, CLOSED can only be reached from ACTIVE, never from
     * SUSPENDED, which a blanket "anything but CLOSED/CANCELLED" check would have missed.
     */
    private void requireTransitionAllowed(CaseInstance c, CaseState target, String action) {
        if (!c.state().canTransitionTo(target)) {
            throw new CaseConflictException("illegal-state",
                    "Cannot " + action + " a case in state " + c.state(),
                    availableActionsFrom(c.state()));
        }
    }

    private List<String> availableActionsFrom(CaseState state) {
        List<String> actions = new ArrayList<>();
        if (state.canTransitionTo(CaseState.ACTIVE)) actions.add("reactivate");
        if (state.canTransitionTo(CaseState.CLOSED)) actions.add("close");
        if (state.canTransitionTo(CaseState.CANCELLED)) actions.add("cancel");
        if (state.canTransitionTo(CaseState.SUSPENDED)) actions.add("suspend");
        return actions;
    }

    private CaseEvent event(CaseInstance c, String type, Map<String, Object> data) {
        return new CaseEvent(CaseIds.newId(), engineId, type, c.id(), c.tenantId(),
                OffsetDateTime.now(), data);
    }
}
