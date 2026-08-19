package org.casemgmt.error;

/**
 * Thrown when a case definition is internally inconsistent. Deploy-time validation uses this for
 * malformed submitted definitions; runtime paths use it for older or externally inserted
 * definitions whose inconsistency was not caught before a case was started.
 *
 * <p><b>Why this type exists (carried finding C2, opened by Task 17, deferred by Task 22).</b>
 * That condition used to be a bare {@link IllegalStateException}. {@code ProblemDetailHandler}
 * maps no such type, so it reached Spring's default handler and shipped as an opaque 500 with
 * no {@code code} a client could switch on — a server-fault shape for what is actually a
 * case-definition authoring typo. Deploy time does not (yet) cross-check {@code formKey}
 * against the {@code forms} map, so the mistake is only detectable here.
 *
 * <p><b>Why 400 and not 500.</b> The definition arrived over the same API
 * ({@code POST /case-definitions}); the thing that is wrong is submitted content, not the
 * server's own state, and the detail message names the offending {@code formKey} precisely
 * enough for the author to fix it. Deliberately its own type rather than reusing
 * {@link CaseConflictException}: this is not a state-machine conflict and it carries no
 * alternative actions — nothing the caller can do to this case makes the completion legal;
 * the definition has to change.
 */
public class InvalidCaseDefinitionException extends RuntimeException {

    private final String caseDefinitionKey;

    public InvalidCaseDefinitionException(String caseDefinitionKey, String message) {
        super(message);
        this.caseDefinitionKey = caseDefinitionKey;
    }

    public String caseDefinitionKey() {
        return caseDefinitionKey;
    }
}
