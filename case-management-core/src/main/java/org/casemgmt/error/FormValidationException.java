package org.casemgmt.error;

import java.util.List;

/**
 * Thrown when a task-completion payload does not satisfy the form schema declared for the
 * plan item's {@code formKey} (spec §4.6). Maps to HTTP 422 at the REST layer: the violations
 * list is precise enough (JSON Pointer + message per violation) that a client can highlight
 * the offending field(s) without a second round trip.
 */
public class FormValidationException extends RuntimeException {

    public record Violation(String pointer, String message) {}

    private final List<Violation> violations;

    public FormValidationException(List<Violation> violations) {
        super("Payload does not satisfy the form schema: " + violations);
        this.violations = violations;
    }

    public List<Violation> violations() {
        return violations;
    }
}
