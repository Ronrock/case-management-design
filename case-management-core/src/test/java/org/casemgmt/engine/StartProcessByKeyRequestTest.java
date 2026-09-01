package org.casemgmt.engine;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.LinkedHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StartProcessByKeyRequestTest {

    @Test
    void rejectsBlankLegacyProcessDefinitionKey() {
        assertThatThrownBy(() -> new StartProcessByKeyRequest(
                "case-1", null, " ", Map.of(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("processDefinitionKey");
    }

    @Test
    void preservesJsonNullVariableValues() {
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("optional", null);

        StartProcessByKeyRequest request = new StartProcessByKeyRequest(
                "case-1", null, "process", variables, null);

        assertThat(request.variables()).containsEntry("optional", null);
    }
}
