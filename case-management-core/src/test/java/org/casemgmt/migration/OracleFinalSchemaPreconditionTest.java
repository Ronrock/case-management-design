package org.casemgmt.migration;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OracleFinalSchemaPreconditionTest {

    @Test
    void productionContractExhaustivelyNamesEveryCommandAndActionColumn() {
        var contract = OracleFinalSchemaPrecondition.productionCommandContract();

        assertThat(contract.columns().stream()
                .filter(column -> column.table().equals("CM_ENGINE_COMMAND"))).hasSize(61);
        assertThat(contract.columns().stream()
                .filter(column -> column.table().equals("CM_ENGINE_COMMAND_ACTION"))).hasSize(15);
        assertThat(contract.constraints()).extracting(
                OracleFinalSchemaPrecondition.ConstraintContract::name)
                .containsExactlyInAnyOrder("CK_CM_ENGCMD_STATUS", "CK_CM_ENGCMD_COUNTERS",
                        "CK_CM_ENGCMD_LEASE", "CK_CM_ENGCMD_TEMPORAL",
                        "CK_CM_ECA_INVARIANTS", "FK_CM_ECA_COMMAND");
        assertThat(contract.indexes()).extracting(
                OracleFinalSchemaPrecondition.IndexContract::name)
                .containsExactlyInAnyOrder("IX_CM_ENGCMD_DUE", "IX_CM_ENGCMD_CLAIM",
                        "UQ_CM_ENGCMD_OPERATION", "UQ_CM_ENGCMD_IDEMPOTENCY",
                        "IX_CM_ENGCMD_PROD_DUE", "IX_CM_ENGCMD_LEASE",
                        "IX_CM_ENGCMD_CASE_STATUS", "IX_CM_ENGCMD_REVIEW",
                        "UQ_CM_ECA_ACTION", "UQ_CM_ECA_SEQUENCE");
    }

    @Test
    void observationContractCoversLedgerAndEveryHardenedRelatedObject() {
        var contract = OracleFinalSchemaPrecondition.engineObservationContract();

        assertThat(contract.columns().stream().filter(column ->
                column.table().equals("CM_APPLIED_ENGINE_OBSERVATION"))).hasSize(19);
        assertThat(contract.columns()).anySatisfy(column -> {
            assertThat(column.table()).isEqualTo("CM_PLAN_ITEM");
            assertThat(column.name()).isEqualTo("PROC_INST_ID_");
        }).anySatisfy(column -> {
            assertThat(column.table()).isEqualTo("CM_TASK");
            assertThat(column.name()).isEqualTo("PROC_INST_ID_");
        }).anySatisfy(column -> {
            assertThat(column.table()).isEqualTo("CM_LINKED_PROCESS");
            assertThat(column.name()).isEqualTo("PROC_DEF_ID_");
        });
        assertThat(contract.constraints()).hasSize(2);
        assertThat(contract.indexes()).extracting(
                OracleFinalSchemaPrecondition.IndexContract::name)
                .containsExactlyInAnyOrder("UQ_CM_AEO_AUTH_FINGERPRINT", "IX_CM_AEO_STATUS",
                        "IX_CM_AEO_ENGINE_ENTITY", "IX_CM_PI_PROC_INST",
                        "IX_CM_TASK_PROC_INST");
    }

    @Test
    void metadataNormalizationIsInsensitiveOnlyToOracleFormatting() {
        assertThat(OracleFinalSchemaPrecondition.normalize(
                " CASE WHEN \"TENANT_ID_\" IS NULL THEN 1 ELSE 0 END "))
                .isEqualTo("CASEWHENTENANT_ID_ISNULLTHEN1ELSE0END");
        assertThat(OracleFinalSchemaPrecondition.normalize(" 'PENDING' "))
                .isEqualTo("PENDING");
    }

    @Test
    void columnMatcherFailsClosedOnSemanticsPrecisionNullabilityAndDefault() {
        var expected = OracleFinalSchemaPrecondition.productionCommandContract().columns()
                .stream().filter(column -> column.name().equals("STATUS_")).findFirst()
                .orElseThrow();

        assertThat(new OracleFinalSchemaPrecondition.ActualColumn(
                "VARCHAR2", 20, "B", null, null, false, "PENDING").matches(expected)).isTrue();
        assertThat(new OracleFinalSchemaPrecondition.ActualColumn(
                "VARCHAR2", 20, "C", null, null, false, "PENDING").matches(expected)).isFalse();
        assertThat(new OracleFinalSchemaPrecondition.ActualColumn(
                "VARCHAR2", 20, "B", null, null, true, "PENDING").matches(expected)).isFalse();
        assertThat(new OracleFinalSchemaPrecondition.ActualColumn(
                "VARCHAR2", 20, "B", null, null, false, null).matches(expected)).isFalse();
    }

    @Test
    void migrationColumnDeclarationsMakeByteSemanticsIndependentOfTheOracleSession()
            throws Exception {
        for (String changelog : List.of(
                "db/changelog/cm-production-engine-command.xml",
                "db/changelog/cm-engine-observation-effects.xml")) {
            try (var input = getClass().getClassLoader().getResourceAsStream(changelog)) {
                var document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                        .parse(input);
                var columns = document.getElementsByTagName("column");
                for (int index = 0; index < columns.getLength(); index++) {
                    Element column = (Element) columns.item(index);
                    String type = column.getAttribute("type");
                    if (type.startsWith("VARCHAR2(")) {
                        assertThat(type).as(changelog + ":" + column.getAttribute("name"))
                                .endsWith(" BYTE)");
                    }
                }
            }
        }
    }

    @Test
    void byteConversionPlansCoverEveryContractVarcharWithoutDuplicateTargets() {
        assertThat(OracleByteSemanticsMigration.targets("production-command"))
                .hasSize(39).doesNotHaveDuplicates();
        assertThat(OracleByteSemanticsMigration.targets("engine-observation"))
                .hasSize(16).doesNotHaveDuplicates();
    }
}
