package org.casemgmt.release;

/**
 * Validates a case-definition contract release at publication time and returns it typed.
 *
 * <p>This is the single gate between "a JSON document somebody uploaded" and "a contract the
 * runtime may rely on". Before it existed, publication leaned on handwritten map inspection
 * scattered across services, so an unknown or misspelled property was accepted at publication
 * and only surfaced when a live case reached the part of the model that needed it (design
 * §9.9, review comment 6).
 *
 * <p>Implementations must fail closed: any document they do not fully understand is rejected
 * with {@link org.casemgmt.error.InvalidCaseDefinitionException}, never accepted with the
 * unrecognised part dropped.
 */
public interface CaseContractValidator {

    /**
     * @param definitionKey the case-definition key the release is published under; the contract's
     *                      own {@code key} must equal it, because the two are stored separately
     *                      and a mismatch would bind one definition's BPMN to another's forms.
     * @param utf8Json      the contract release content, exactly as stored.
     * @return the typed contract, so callers stop re-parsing {@code Map<String,Object>} and
     *         re-deciding what an ad-hoc action or an SLA binding means.
     * @throws org.casemgmt.error.InvalidCaseDefinitionException if the content is not valid
     *         UTF-8 JSON, violates the published JSON Schema, or breaks a mode rule. The message
     *         carries the JSON path of each violation and is bounded; it never echoes submitted
     *         values.
     */
    ValidatedCaseContract validate(String definitionKey, byte[] utf8Json);
}
