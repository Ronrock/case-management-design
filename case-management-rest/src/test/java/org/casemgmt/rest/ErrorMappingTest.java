package org.casemgmt.rest;

import org.casemgmt.error.*;
import org.casemgmt.rest.error.MalformedETagException;
import org.casemgmt.rest.error.PreconditionFailedException;
import org.casemgmt.rest.error.ProblemDetailHandler;
import org.casemgmt.rest.filter.ETagSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.ProblemDetail;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class ErrorMappingTest {

    private final ProblemDetailHandler handler = new ProblemDetailHandler();

    @Test
    void notFoundMapsTo404() {
        ProblemDetail problem = handler.onNotFound(new NotFoundException("Case", "eng-a:1"));
        assertThat(problem.getStatus()).isEqualTo(404);
        assertThat(problem.getProperties()).containsEntry("code", "not-found");
    }

    @Test
    void conflictCarriesTheAvailableActions() {
        ProblemDetail problem = handler.onConflict(new CaseConflictException(
                "required-items-open", "blocked", List.of("cancel", "update")));

        assertThat(problem.getStatus()).isEqualTo(409);
        assertThat(problem.getProperties()).containsEntry("code", "required-items-open");
        @SuppressWarnings("unchecked")
        List<String> availableActions = (List<String>) problem.getProperties().get("availableActions");
        assertThat(availableActions).containsExactly("cancel", "update");
    }

    @Test
    void unavailableCaseDefinitionMapsToAStableConflict() {
        ProblemDetail problem = handler.onConflict(new CaseConflictException(
                "case-definition-not-active",
                "Case definition 'orders' has no active version", List.of()));

        assertThat(problem.getStatus()).isEqualTo(409);
        assertThat(problem.getProperties())
                .containsEntry("code", "case-definition-not-active")
                .containsEntry("availableActions", List.of());
    }

    @Test
    void unavailableExactBindingMapsToAStableConflict() {
        ProblemDetail problem = handler.onConflict(new CaseConflictException(
                "case-definition-binding-not-active",
                "Case definition 'orders' has no active binding", List.of()));

        assertThat(problem.getStatus()).isEqualTo(409);
        assertThat(problem.getProperties())
                .containsEntry("code", "case-definition-binding-not-active")
                .containsEntry("availableActions", List.of());
    }

    @Test
    void staleVersionMapsTo412() {
        ProblemDetail problem = handler.onOptimisticLock(
                new OptimisticLockException("Case", "eng-a:1", 3));
        assertThat(problem.getStatus()).isEqualTo(412);
    }

    @Test
    void missingIfMatchMapsTo428() {
        ProblemDetail problem = handler.onPreconditionRequired(new PreconditionRequiredException());
        assertThat(problem.getStatus()).isEqualTo(428);
    }

    // Review fix (Important, fifth vacuous test caught in this plan): the original version
    // of this test asserted only status 422 and containsKey("violations") — it would keep
    // passing if the handler emitted the wrong pointers, or no pointers at all inside the
    // list. Now asserts the exact extracted content, including the two RFC 6901 shapes
    // Task 17 specifically fixed and that are easiest to corrupt: a nested pointer
    // (/nested/outcome) and the empty-string ROOT pointer ("") for a violation against the
    // whole document — the one most easily mangled into "/" or silently dropped.
    @Test
    void formViolationsMapTo422WithPointers() {
        ProblemDetail problem = handler.onFormInvalid(new FormValidationException(List.of(
                new FormValidationException.Violation("/outcome", "must be one of [approve, reject]"),
                new FormValidationException.Violation("/nested/outcome", "must be a string"),
                new FormValidationException.Violation("", "outcome is required"))));

        assertThat(problem.getStatus()).isEqualTo(422);

        @SuppressWarnings("unchecked")
        List<Map<String, String>> violations =
                (List<Map<String, String>>) problem.getProperties().get("violations");

        assertThat(violations).containsExactly(
                Map.of("pointer", "/outcome", "message", "must be one of [approve, reject]"),
                Map.of("pointer", "/nested/outcome", "message", "must be a string"),
                Map.of("pointer", "", "message", "outcome is required"));
    }

    @Test
    void etagsRoundTrip() {
        assertThat(ETagSupport.format(17)).isEqualTo("\"17\"");
        assertThat(ETagSupport.parse("\"17\"")).isEqualTo(17L);
        assertThat(ETagSupport.parse("W/\"17\"")).isEqualTo(17L);
    }

    @Test
    void aMissingIfMatchHeaderIsRejectedRatherThanAssumed() {
        assertThatThrownBy(() -> ETagSupport.parse(null))
                .isInstanceOf(PreconditionRequiredException.class);
    }

    @Test
    void aBlankIfMatchHeaderIsRejectedRatherThanAssumed() {
        assertThatThrownBy(() -> ETagSupport.parse(""))
                .isInstanceOf(PreconditionRequiredException.class);
    }

    // Review fix (Important, I4): RFC 7232 §3.1 defines If-Match: * as "matches any current
    // representation" — the request should proceed, not 400. parse() (kept narrow on
    // purpose, see its javadoc) still rejects "*"; parseIfMatch() is the RFC-complete entry
    // point that understands it.
    @Test
    void wildcardIfMatchMeansAnyCurrentRepresentation() {
        assertThat(ETagSupport.parseIfMatch("*")).isEmpty();
    }

    @Test
    void multiValueIfMatchParsesTheFirstListedTag() {
        assertThat(ETagSupport.parseIfMatch("\"5\", \"7\"")).hasValue(5L);
    }

    @Test
    void expectedVersionAcceptsAnyMatchingTagFromAMultiValueIfMatch() {
        assertThat(ETagSupport.expectedVersion("\"5\", \"7\"", "case c-1",
                () -> java.util.OptionalLong.of(7L))).isEqualTo(7L);
    }

    @Test
    void expectedVersionRejectsAMultiValueIfMatchWhenNoTagMatchesTheCurrentVersion() {
        assertThatThrownBy(() -> ETagSupport.expectedVersion("\"5\", \"7\"", "case c-1",
                () -> java.util.OptionalLong.of(9L)))
                .isInstanceOf(PreconditionFailedException.class);
    }

    @Test
    void multiValueIfMatchStillRejectsAMalformedEntry() {
        assertThatThrownBy(() -> ETagSupport.parseIfMatch("\"5\", not-a-number"))
                .isInstanceOf(MalformedETagException.class);
    }

    @Test
    void embeddedQuotesDoNotGetStrippedIntoAValidVersion() {
        assertThatThrownBy(() -> ETagSupport.parse("\"1\"7\""))
                .isInstanceOf(MalformedETagException.class);
    }

    // Review fix (Important, I6): a malformed If-Match header must map to 400 through its
    // own exception type, not a blanket IllegalArgumentException handler that would also
    // catch unrelated, non-client-shaped exceptions elsewhere in core.
    @Test
    void malformedETagMapsTo400() {
        ProblemDetail problem = handler.onMalformedETag(
                new MalformedETagException("not-a-number", new NumberFormatException()));
        assertThat(problem.getStatus()).isEqualTo(400);
        assertThat(problem.getProperties()).containsEntry("code", "invalid-request");
    }
}
