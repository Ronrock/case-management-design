package org.casemgmt.rest.error;

/**
 * Thrown when an {@code If-Match} precondition evaluates to false for a reason that is not an
 * optimistic-lock collision — today exactly one: {@code If-Match: *} against a target that has
 * no current representation.
 *
 * <p><b>Why this exists (carried finding C4).</b> RFC 7232 §3.1 defines {@code If-Match: *} as
 * "true if the origin server has a current representation for the target resource". {@link
 * org.casemgmt.rest.filter.ETagSupport#parseIfMatch} already decodes the wildcard into
 * {@code OptionalLong.empty()} ("any version"), but the second half of the rule belongs to the
 * caller: when the representation does <em>not</em> exist, the condition is false and the
 * response is 412 — not the 404 the resource lookup would otherwise produce, and not a 200 for
 * a resource that isn't there. That distinction is observable: a client using {@code If-Match: *}
 * as "update it only if it still exists" must be able to tell "gone" from "not authorised" and
 * from "wrong version".
 *
 * <p>Deliberately distinct from {@link org.casemgmt.error.OptimisticLockException}, which also
 * maps to 412: that one means "the representation exists but is at a different version"
 * ({@code code: version-conflict}); this one means "there is no representation to match"
 * ({@code code: precondition-failed}). Same status, different {@code code} — which is the field
 * spec §6.5 says clients switch on.
 */
public class PreconditionFailedException extends RuntimeException {

    public PreconditionFailedException(String message) {
        super(message);
    }
}
