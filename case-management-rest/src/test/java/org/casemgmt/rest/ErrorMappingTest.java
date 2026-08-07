package org.casemgmt.rest;

import org.casemgmt.error.*;
import org.casemgmt.rest.error.ProblemDetailHandler;
import org.casemgmt.rest.filter.ETagSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import java.util.List;

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

    @Test
    void formViolationsMapTo422WithPointers() {
        ProblemDetail problem = handler.onFormInvalid(new FormValidationException(
                List.of(new FormValidationException.Violation("/outcome", "must be one of [approve, reject]"))));

        assertThat(problem.getStatus()).isEqualTo(422);
        assertThat(problem.getProperties()).containsKey("violations");
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
}
