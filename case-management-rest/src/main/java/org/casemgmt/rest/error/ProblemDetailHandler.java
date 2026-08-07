package org.casemgmt.rest.error;

import org.casemgmt.error.*;
import org.casemgmt.rules.CriterionEvaluationException;
import org.casemgmt.rules.PlanModelLoopException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.util.List;
import java.util.Map;

/** RFC 9457 responses with a stable `code` field frontends can switch on (spec §6.5). */
@RestControllerAdvice
public class ProblemDetailHandler {

    private static final String TYPE_BASE = "https://casemgmt.org/problems/";

    @ExceptionHandler(NotFoundException.class)
    public ProblemDetail onNotFound(NotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "not-found", e.getMessage(), Map.of());
    }

    @ExceptionHandler(CaseConflictException.class)
    public ProblemDetail onConflict(CaseConflictException e) {
        return problem(HttpStatus.CONFLICT, e.code(), e.getMessage(),
                Map.of("availableActions", e.availableActions()));
    }

    @ExceptionHandler(OptimisticLockException.class)
    public ProblemDetail onOptimisticLock(OptimisticLockException e) {
        return problem(HttpStatus.PRECONDITION_FAILED, "version-conflict", e.getMessage(), Map.of());
    }

    @ExceptionHandler(PreconditionRequiredException.class)
    public ProblemDetail onPreconditionRequired(PreconditionRequiredException e) {
        return problem(HttpStatus.PRECONDITION_REQUIRED, "if-match-required", e.getMessage(), Map.of());
    }

    @ExceptionHandler(FormValidationException.class)
    public ProblemDetail onFormInvalid(FormValidationException e) {
        List<Map<String, String>> violations = e.violations().stream()
                .map(v -> Map.of("pointer", v.pointer(), "message", v.message()))
                .toList();
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "form-invalid",
                "Payload does not satisfy the form schema", Map.of("violations", violations));
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ProblemDetail onIdempotencyConflict(IdempotencyConflictException e) {
        return problem(HttpStatus.CONFLICT, "idempotency-conflict", e.getMessage(), Map.of());
    }

    @ExceptionHandler({CriterionEvaluationException.class, PlanModelLoopException.class})
    public ProblemDetail onModelError(RuntimeException e) {
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "model-error", e.getMessage(), Map.of());
    }

    // Scoped to MalformedETagException, NOT a blanket IllegalArgumentException handler
    // (review finding, Important): core throws IllegalArgumentException from several
    // sites that are not client-shaped — WebhookRepository's paging-limit guard,
    // PlanModelInstantiator's bad parentStageKey (which the status table above routes to
    // 500 model-error) — and a blanket handler here would misclassify both as 400. See
    // MalformedETagException's javadoc.
    @ExceptionHandler(MalformedETagException.class)
    public ProblemDetail onMalformedETag(MalformedETagException e) {
        return problem(HttpStatus.BAD_REQUEST, "invalid-request", e.getMessage(), Map.of());
    }

    /**
     * Tenant isolation (fix round 1, Critical 2). See {@link ForbiddenException} for why this is
     * a different answer from {@code ActionPolicy}'s 409 {@code action-not-available}.
     */
    @ExceptionHandler(ForbiddenException.class)
    public ProblemDetail onForbidden(ForbiddenException e) {
        return problem(HttpStatus.FORBIDDEN, "forbidden", e.getMessage(), Map.of());
    }

    /**
     * Request values this layer parses itself — today, enums (fix round 1, I5). Scoped to our own
     * type for exactly the reason {@link #onMalformedETag} is: a blanket
     * {@code IllegalArgumentException} handler would misclassify core's several non-client-shaped
     * uses of that type as 400.
     */
    @ExceptionHandler(InvalidRequestException.class)
    public ProblemDetail onInvalidRequest(InvalidRequestException e) {
        return problem(HttpStatus.BAD_REQUEST, "invalid-request", e.getMessage(), Map.of());
    }

    /**
     * Carried finding C4. Same 412 as {@link #onOptimisticLock}, deliberately a different
     * {@code code}: "no current representation to match" is not "matched the wrong version".
     */
    @ExceptionHandler(PreconditionFailedException.class)
    public ProblemDetail onPreconditionFailed(PreconditionFailedException e) {
        return problem(HttpStatus.PRECONDITION_FAILED, "precondition-failed", e.getMessage(), Map.of());
    }

    /**
     * Carried finding C2: a plan item declaring a {@code formKey} the case definition has no
     * schema for used to escape as a bare {@code IllegalStateException} and ship as an opaque
     * 500. See {@link InvalidCaseDefinitionException} for why the definition author, not the
     * server, owns this one.
     */
    @ExceptionHandler(InvalidCaseDefinitionException.class)
    public ProblemDetail onInvalidCaseDefinition(InvalidCaseDefinitionException e) {
        return problem(HttpStatus.BAD_REQUEST, "case-definition-invalid", e.getMessage(),
                Map.of("caseDefinitionKey", e.caseDefinitionKey()));
    }

    private ProblemDetail problem(HttpStatus status, String code, String detail,
                                  Map<String, Object> extras) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(TYPE_BASE + code));
        problem.setProperty("code", code);
        extras.forEach(problem::setProperty);
        return problem;
    }
}
