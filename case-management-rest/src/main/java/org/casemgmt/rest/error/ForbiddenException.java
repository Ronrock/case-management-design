package org.casemgmt.rest.error;

/**
 * Thrown when an authenticated caller asks for a resource that belongs to a different tenant, or
 * makes a request that has no tenant to run under at all.
 *
 * <p><b>Why this is distinct from {@code ActionPolicy}'s 409 {@code action-not-available}</b>
 * (Task 24 fix round 1, review finding Critical 2). Those two answer different questions.
 * {@code ActionPolicy} answers "is this action available to you on this resource, in its current
 * state" — a question that only makes sense once the resource is yours to reason about. This one
 * answers "is this resource in your tenant at all", which is prior to it: no role, no state and
 * no action can make another tenant's data visible. Collapsing them would make a cross-tenant
 * probe indistinguishable from an ordinary role refusal, and would invite a caller to go looking
 * for the role that unlocks it.
 *
 * <p>Deliberately NOT used to hide the existence of a specific case: a case in another tenant is
 * reported as {@link org.casemgmt.error.NotFoundException} instead, because a 403 on a
 * caller-supplied id is itself an existence oracle. This type is for requests where the caller
 * has explicitly named a tenant (a filter, a listing) or has none — where there is nothing to
 * leak by saying so plainly.
 */
public class ForbiddenException extends RuntimeException {
    public ForbiddenException(String message) {
        super(message);
    }
}
