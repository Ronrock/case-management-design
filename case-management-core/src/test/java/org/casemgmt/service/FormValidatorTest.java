package org.casemgmt.service;

import org.casemgmt.error.FormValidationException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class FormValidatorTest {

    private final FormValidator validator = new FormValidator();

    private Map<String, Object> schema() {
        return Map.of(
                "type", "object",
                "required", List.of("outcome"),
                "properties", Map.of(
                        "outcome", Map.of("type", "string", "enum", List.of("approve", "reject")),
                        "amount", Map.of("type", "integer", "minimum", 0)));
    }

    @Test
    void acceptsAConformingPayload() {
        assertThatNoException().isThrownBy(() ->
                validator.validate(schema(), Map.of("outcome", "approve", "amount", 10)));
    }

    @Test
    void acceptsUiWidgetAnnotationAsNonValidationMetadata() {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of("summary", Map.of(
                        "type", "string",
                        "title", "Summary",
                        "ui:widget", "textarea")));

        assertThatNoException().isThrownBy(() ->
                validator.validate(schema, Map.of("summary", "Customer supplied context")));
    }

    /**
     * A missing required property has no field of its own to point at — the violation applies
     * to the object as a whole, so networknt reports it at the document root. Per RFC 6901,
     * the pointer to the whole document is the empty string, NOT {@code "/"} (which would
     * actually mean "the property named ''"). Confirmed against the real library output before
     * asserting: this is exactly what it emits, not a guess.
     */
    @Test
    void rejectsAMissingRequiredField() {
        assertThatThrownBy(() -> validator.validate(schema(), Map.of("amount", 10)))
                .isInstanceOf(FormValidationException.class)
                .satisfies(e -> assertThat(((FormValidationException) e).violations())
                        .anySatisfy(v -> {
                            assertThat(v.pointer()).isEqualTo("");
                            assertThat(v.message()).contains("outcome");
                        }));
    }

    /**
     * The pointer is the contract a frontend renderer parses with a standard RFC 6901 resolver
     * to attach a message to the right input (spec §6.5) — a substring check would pass for
     * networknt's default JSONPath-flavoured output ({@code "$.outcome"}) just as easily as for
     * a real pointer, which is exactly how that mismatch went unnoticed before. Asserting the
     * exact string is the point of this test.
     */
    @Test
    void rejectsAValueOutsideTheEnumAndReportsAnExactJsonPointer() {
        assertThatThrownBy(() -> validator.validate(schema(), Map.of("outcome", "maybe")))
                .isInstanceOf(FormValidationException.class)
                .satisfies(e -> assertThat(((FormValidationException) e).violations())
                        .anySatisfy(v -> assertThat(v.pointer()).isEqualTo("/outcome")));
    }

    /** Nested field: proves the pointer is a real path, not just a leaf field name. */
    @Test
    void rejectsANestedValueOutsideTheEnumAndReportsTheFullJsonPointer() {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of("nested", Map.of(
                        "type", "object",
                        "required", List.of("outcome"),
                        "properties", Map.of(
                                "outcome", Map.of("type", "string", "enum", List.of("approve", "reject"))))));

        assertThatThrownBy(() -> validator.validate(schema, Map.of("nested", Map.of("outcome", "maybe"))))
                .isInstanceOf(FormValidationException.class)
                .satisfies(e -> assertThat(((FormValidationException) e).violations())
                        .anySatisfy(v -> assertThat(v.pointer()).isEqualTo("/nested/outcome")));
    }

    @Test
    void aNullSchemaMeansNoValidation() {
        assertThatNoException().isThrownBy(() -> validator.validate(null, Map.of("anything", 1)));
    }

    /**
     * Review note (Minor): every other schema in this suite leaves {@code additionalProperties}
     * unset, so an extra, undeclared field always passes — correct per JSON Schema (open by
     * default), not a bug, but nothing here had ever actually proven the validator honours the
     * knob a case-definition author would reach for to close that door. This is the proof: with
     * {@code additionalProperties: false} declared, an undeclared field IS rejected.
     */
    @Test
    void rejectsAnUndeclaredPropertyWhenTheSchemaDisallowsIt() {
        Map<String, Object> strictSchema = Map.of(
                "type", "object",
                "additionalProperties", false,
                "properties", Map.of("outcome", Map.of("type", "string")));

        assertThatThrownBy(() -> validator.validate(strictSchema,
                Map.of("outcome", "approve", "unexpectedField", "x")))
                .isInstanceOf(FormValidationException.class)
                .satisfies(e -> assertThat(((FormValidationException) e).violations())
                        .anySatisfy(v -> assertThat(v.message()).contains("unexpectedField")));
    }
}
