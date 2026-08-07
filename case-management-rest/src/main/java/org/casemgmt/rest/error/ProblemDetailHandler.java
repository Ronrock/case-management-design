package org.casemgmt.rest.error;

import org.casemgmt.error.*;
import org.casemgmt.rules.CriterionEvaluationException;
import org.casemgmt.rules.PlanModelLoopException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * RFC 9457 responses with a stable `code` field frontends can switch on (spec §6.5).
 *
 * <p><b>Extends {@link ResponseEntityExceptionHandler}</b> (final whole-branch review, Minor).
 * Without it, the exceptions Spring MVC raises before any handler runs — most importantly
 * {@link org.springframework.http.converter.HttpMessageNotReadableException}, i.e. a malformed
 * request body — escaped this advice entirely and shipped as Spring's default error page. That
 * is the one error shape a generic consumer is most likely to trigger while learning the API,
 * and it was the only one outside the contract everything else honours.
 * {@link #handleExceptionInternal} folds every one of them back into the same body shape, so a
 * client never has to learn a second error format.
 */
@RestControllerAdvice
public class ProblemDetailHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ProblemDetailHandler.class);

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

    /**
     * The one 500 this advice mints — and the only handler here that does NOT put the
     * exception's message in {@code detail} (final whole-branch review, Minor).
     *
     * <p>Every other handler above maps a client error, where the message IS the explanation the
     * caller needs. This one maps a server fault, and exception messages in this codebase quote
     * plan-item ids, case-definition keys, state names and raw criterion expressions — shipping
     * them to the client on a fault the client did not cause is information disclosure with no
     * corresponding benefit, since there is no client-side action to take. The message is logged
     * (at ERROR, with the stack trace) so it stays available to whoever operates the deployment;
     * the response says only which subsystem failed.
     */
    @ExceptionHandler({CriterionEvaluationException.class, PlanModelLoopException.class})
    public ProblemDetail onModelError(RuntimeException e) {
        log.error("Plan-model evaluation failed", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "model-error",
                "The case's plan model could not be evaluated. The failure has been logged; "
                        + "quote the request's timestamp when reporting it.", Map.of());
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

    /**
     * Every exception Spring MVC itself raises before a handler runs — a malformed request body,
     * an unsupported media type, an unreadable parameter, an unknown path — arrives here (final
     * whole-branch review, Minor). Spring has already chosen the status and, in Boot 4, already
     * built a {@link ProblemDetail}; this adds the two things that make it OUR contract rather
     * than a second one: the stable {@code code} a client switches on, and the {@code type} URI.
     *
     * <p>{@code detail} is left exactly as Spring wrote it for 4xx (it describes the client's own
     * malformed input, which is what the client needs) and replaced for 5xx, for the same
     * information-disclosure reason {@link #onModelError} gives.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body,
                                                             HttpHeaders headers,
                                                             HttpStatusCode statusCode,
                                                             WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers,
                statusCode, request);
        if (response == null || !(response.getBody() instanceof ProblemDetail problem)) {
            return response;
        }
        String code = statusCode.is4xxClientError() ? "invalid-request" : "server-error";
        if (statusCode.is5xxServerError()) {
            log.error("Unhandled request failure", ex);
            problem.setDetail("The request could not be processed. The failure has been logged.");
        }
        decorate(problem, HttpStatus.valueOf(statusCode.value()), code);
        return new ResponseEntity<>(problem, response.getHeaders(), statusCode);
    }

    private ProblemDetail problem(HttpStatus status, String code, String detail,
                                  Map<String, Object> extras) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        decorate(problem, status, code);
        extras.forEach(problem::setProperty);
        return problem;
    }

    /**
     * {@code type} and {@code code}.
     *
     * <p><b>Deliberately does NOT call {@code setTitle}</b> — and this is a CORRECTION to the
     * final whole-branch review, which recorded as a Minor that "{@code ProblemDetail.title} is
     * never set, so it is omitted from every problem body". On Spring Framework 7 (Boot 4, what
     * this build runs) that is not true. {@code ProblemDetail.getTitle()} falls back to
     * {@code HttpStatus.resolve(status).getReasonPhrase()} whenever the field is null —
     * confirmed by disassembling {@code spring-web-7.0.8.jar}, and confirmed on the wire:
     * {@code setTitle(status.getReasonPhrase())} was added here and then stripped again, and
     * {@code CaseApiErrorContractTest.everyProblemBodyCarriesAnRfc9457Title} passed both times
     * with identical values. Every problem body this service produces has always carried a
     * conformant {@code title}.
     *
     * <p>The setter was therefore removed rather than kept as belt and braces: it wrote exactly
     * the value the getter already derives, so it could never change an outcome — a mechanism
     * that reads as protective and cannot be, which is precisely the shape this project has
     * caught nine times. The TEST is kept: "the wire body carries a title" is a real contract
     * assertion worth pinning whoever supplies the value, and it would catch a future Spring
     * upgrade that dropped the fallback.
     */
    private static void decorate(ProblemDetail problem, HttpStatus status, String code) {
        problem.setType(URI.create(TYPE_BASE + code));
        problem.setProperty("code", code);
    }
}
