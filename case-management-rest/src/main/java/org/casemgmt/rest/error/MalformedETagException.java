package org.casemgmt.rest.error;

/**
 * Thrown when an {@code If-Match} header's entity-tag cannot be parsed as the numeric
 * version this system uses for its ETags (spec §6.3). Deliberately its own type rather
 * than a bare {@code IllegalArgumentException}: core throws {@code IllegalArgumentException}
 * for several unrelated, non-client-shaped reasons (e.g. {@code WebhookRepository}'s
 * paging-limit guard, a bad {@code parentStageKey}, which the
 * status table routes to 500 {@code model-error}) — a blanket
 * {@code @ExceptionHandler(IllegalArgumentException.class)} would misclassify those as
 * 400 {@code invalid-request} along with this one. Scoping the handler to this type instead
 * keeps the 400 mapping specific to what {@link org.casemgmt.rest.filter.ETagSupport}
 * actually guards: a malformed If-Match header.
 */
public class MalformedETagException extends RuntimeException {
    public MalformedETagException(String header, Throwable cause) {
        super("Malformed If-Match header: " + header, cause);
    }
}
