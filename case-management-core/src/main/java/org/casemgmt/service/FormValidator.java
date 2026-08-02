package org.casemgmt.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.casemgmt.error.FormValidationException;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One schema, two jobs (spec §4.6): the same document the frontend renders is the
 * document the service validates against, so client and server cannot disagree
 * about what a valid submission is.
 *
 * <p>Note: networknt brings Jackson 2 ({@code com.fasterxml}), which is the ONLY Jackson on
 * this module's classpath (core has no Jackson 3 / {@code tools.jackson.*} — see
 * {@code JsonCodec}'s note on the same point). The imports here are deliberately Jackson 2.
 */
public class FormValidator {

    private final com.fasterxml.jackson.databind.ObjectMapper mapper =
            new com.fasterxml.jackson.databind.ObjectMapper();
    private final JsonSchemaFactory factory =
            JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

    /**
     * Validates {@code payload} against {@code schema}. A null (or empty) schema means the
     * plan item declared no form contract to enforce — every payload is accepted in that case,
     * not rejected: absence of a schema is not itself a violation.
     */
    public void validate(Map<String, Object> schema, Map<String, Object> payload) {
        if (schema == null || schema.isEmpty()) {
            return;   // no schema declared for this form key: nothing to enforce
        }
        JsonNode schemaNode = mapper.valueToTree(schema);
        JsonNode payloadNode = mapper.valueToTree(payload == null ? Map.of() : payload);

        JsonSchema compiled = factory.getSchema(schemaNode);
        Set<ValidationMessage> messages = compiled.validate(payloadNode);

        if (!messages.isEmpty()) {
            List<FormValidationException.Violation> violations = messages.stream()
                    .map(m -> new FormValidationException.Violation(
                            m.getInstanceLocation() == null ? "/" : m.getInstanceLocation().toString(),
                            m.getMessage()))
                    .toList();
            throw new FormValidationException(violations);
        }
    }
}
