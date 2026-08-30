package org.casemgmt;

import org.casemgmt.error.InvalidCaseDefinitionException;
import org.casemgmt.release.JsonSchemaCaseContractValidator;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Clean-room release boundary: a BPMN-only build must reject retained legacy input and must give
 * an operator an explicit, documented decision point rather than silently changing old data.
 */
class BpmnFirstMigrationIT {

    @Test
    void rejectsLegacyPlanModelContractsAndDocumentsTheUpgradePreflight() throws Exception {
        assertThatThrownBy(() -> new JsonSchemaCaseContractValidator().validate("complaint", """
                {"key":"complaint","orchestrationMode":"PLAN_MODEL","fields":{},"forms":{}}
                """.getBytes()))
                .isInstanceOf(InvalidCaseDefinitionException.class)
                .hasMessageContaining("PLAN_MODEL")
                .hasMessageContaining("BPMN");

        String operations = Files.readString(Path.of("..", "docs", "guide", "operations.md"));
        assertThat(operations)
                .contains("## BPMN-only upgrade preflight")
                .contains("CM-BPMN-ONLY-LEGACY-DATA")
                .contains("does not delete or convert legacy data");
    }
}
