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

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail onBadRequest(IllegalArgumentException e) {
        return problem(HttpStatus.BAD_REQUEST, "invalid-request", e.getMessage(), Map.of());
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
