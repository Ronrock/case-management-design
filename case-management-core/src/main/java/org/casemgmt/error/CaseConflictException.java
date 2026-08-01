package org.casemgmt.error;

import java.util.List;

/**
 * Thrown when a caller requests an action the current state does not permit — the
 * shared conflict signal behind both {@code ActionPolicy}'s enforcement methods
 * (Task 23: {@code assertAllowed}, {@code assertAllowedOnPlanItem},
 * {@code assertAllowedOnTask}) and, later, this task's own service-layer mutations.
 * Maps to HTTP 409 (spec §6.5): the body names the current state and the actions
 * that ARE available, so a client that raced the state machine can self-correct
 * without a second read.
 *
 * <p>Forward-ported from this task by Task 23 (ActionPolicy), which needed exactly
 * this shape and would otherwise have declared a duplicate under
 * {@code case-management-rest}, colliding once this task landed the real one here.
 * Lives in {@code org.casemgmt.error} (not {@code org.casemgmt.rest.policy})
 * deliberately: it is a domain-level conflict signal any layer — service, policy,
 * or persistence — can depend on.
 */
public class CaseConflictException extends RuntimeException {

    private final String code;
    private final List<String> availableActions;

    public CaseConflictException(String code, String message, List<String> availableActions) {
        super(message);
        this.code = code;
        this.availableActions = availableActions == null ? List.of() : availableActions;
    }

    /** Stable machine-readable code (RFC 9457 problem+json {@code code} field, spec §6.5). */
    public String code() {
        return code;
    }

    /** The actions that are actually available right now, for a self-correcting client. */
    public List<String> availableActions() {
        return availableActions;
    }
}
