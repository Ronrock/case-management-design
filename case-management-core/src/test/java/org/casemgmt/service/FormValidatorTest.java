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
    void rejectsAMissingRequiredField() {
        assertThatThrownBy(() -> validator.validate(schema(), Map.of("amount", 10)))
                .isInstanceOf(FormValidationException.class)
                .satisfies(e -> assertThat(((FormValidationException) e).violations())
                        .anySatisfy(v -> assertThat(v.message()).contains("outcome")));
    }

    @Test
    void rejectsAValueOutsideTheEnumAndReportsAPointer() {
        assertThatThrownBy(() -> validator.validate(schema(), Map.of("outcome", "maybe")))
                .isInstanceOf(FormValidationException.class)
                .satisfies(e -> assertThat(((FormValidationException) e).violations())
                        .anySatisfy(v -> assertThat(v.pointer()).contains("outcome")));
    }

    @Test
    void aNullSchemaMeansNoValidation() {
        assertThatNoException().isThrownBy(() -> validator.validate(null, Map.of("anything", 1)));
    }
}
