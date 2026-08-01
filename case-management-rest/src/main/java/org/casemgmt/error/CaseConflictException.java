package org.casemgmt.error;

import java.util.List;

/**
 * Thrown by {@code ActionPolicy.assertAllowed} (and, on the task side,
 * {@code assertAllowedOnTask}) when a caller requests an action the policy does not
 * currently permit. Maps to HTTP 409 (spec §6.5): the body names the current state
 * and the actions that ARE available, so a client that raced the state machine can
 * self-correct without a second read.
 *
 * <p>Lives outside {@code org.casemgmt.rest.policy} deliberately: it is a domain-level
 * conflict signal, not a policy-package-private detail, and a future persistence or
 * service layer throwing the same "illegal transition" shape should depend on this
 * class rather than reach into the policy package for it.
 */
public class CaseConflictException extends RuntimeException {

    private final String code;
    private final List<String> availableActions;

    public CaseConflictException(String code, String message, List<String> availableActions) {
        super(message);
        this.code = code;
        this.availableActions = List.copyOf(availableActions);
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
