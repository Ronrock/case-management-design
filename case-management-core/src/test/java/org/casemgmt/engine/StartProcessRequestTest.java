package org.casemgmt.engine;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.LinkedHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StartProcessRequestTest {

    @Test
    void rejectsMissingExactProcessDefinitionId() {
        assertThatThrownBy(() -> new StartProcessRequest(
                "case-1", null, null, "order-process", "tenant-a", Map.of(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("processDefinitionId");
    }

    @Test
    void rejectsBlankExactProcessDefinitionId() {
        assertThatThrownBy(() -> new StartProcessRequest(
                "case-1", null, "  ", "order-process", "tenant-a", Map.of(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("processDefinitionId");
    }

    @Test
    void preservesJsonNullVariableValues() {
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("optional", null);

        StartProcessRequest request = new StartProcessRequest(
                "case-1", null, "process:1:exact", "process", null, variables, null);

        assertThat(request.variables()).containsEntry("optional", null);
    }
}
