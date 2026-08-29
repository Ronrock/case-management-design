package org.casemgmt.migration;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OracleFinalSchemaPreconditionTest {

    @Test
    void productionContractExhaustivelyNamesEveryCommandActionAndTransitionColumn() {
        var contract = OracleFinalSchemaPrecondition.productionCommandContract();

        assertThat(contract.columns().stream()
                .filter(column -> column.table().equals("CM_ENGINE_COMMAND"))).hasSize(61);
        assertThat(contract.columns().stream()
                .filter(column -> column.table().equals("CM_ENGINE_COMMAND_ACTION"))).hasSize(15);
        assertThat(contract.columns().stream()
                .filter(column -> column.table().equals("CM_ENGINE_COMMAND_TRANSITION")))
                .hasSize(16);
        assertThat(contract.constraints()).extracting(
                OracleFinalSchemaPrecondition.ConstraintContract::name)
                .containsExactlyInAnyOrder("CK_CM_ENGCMD_STATUS", "CK_CM_ENGCMD_COUNTERS",
                        "CK_CM_ENGCMD_LEASE", "CK_CM_ENGCMD_TEMPORAL",
                        "CK_CM_ECA_INVARIANTS", "FK_CM_ECA_COMMAND",
                        "UQ_CM_ECA_SEQUENCE_C",
                        "PK_CM_ECT", "CK_CM_ECT_INVARIANTS", "FK_CM_ECT_COMMAND",
                        "FK_CM_ECT_ACTION");
        assertThat(contract.constraints().stream()
                .filter(constraint -> constraint.name().equals("CK_CM_ECT_INVARIANTS"))
                .findFirst().orElseThrow().expression())
                .startsWith("OUTCOME_FORMAT_IN(1,2)AND");
        assertThat(contract.indexes()).extracting(
                OracleFinalSchemaPrecondition.IndexContract::name)
                .containsExactlyInAnyOrder("IX_CM_ENGCMD_DUE", "IX_CM_ENGCMD_CLAIM",
                        "UQ_CM_ENGCMD_OPERATION", "UQ_CM_ENGCMD_IDEMPOTENCY",
                        "IX_CM_ENGCMD_PROD_DUE", "IX_CM_ENGCMD_LEASE",
                        "IX_CM_ENGCMD_CASE_STATUS", "IX_CM_ENGCMD_REVIEW",
                        "UQ_CM_ECA_ACTION", "UQ_CM_ECA_SEQUENCE", "PK_CM_ECT");
    }

    @Test
    void productionTemporalContractAllowsAnImmediateDueRetry() {
        String temporal = OracleFinalSchemaPrecondition.productionCommandContract().constraints()
                .stream().filter(constraint -> constraint.name().equals("CK_CM_ENGCMD_TEMPORAL"))
                .findFirst().orElseThrow().expression();

        assertThat(temporal)
                .contains("NEXT_ATTEMPT_AT_>=DECIDED_AT_")
                .doesNotContain("NEXT_ATTEMPT_AT_>DECIDED_AT_");
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
                "VARCHAR2", 32, "B", null, null, false, "PENDING").matches(expected)).isTrue();
        assertThat(new OracleFinalSchemaPrecondition.ActualColumn(
                "VARCHAR2", 32, "C", null, null, false, "PENDING").matches(expected)).isFalse();
        assertThat(new OracleFinalSchemaPrecondition.ActualColumn(
                "VARCHAR2", 32, "B", null, null, true, "PENDING").matches(expected)).isFalse();
        assertThat(new OracleFinalSchemaPrecondition.ActualColumn(
                "VARCHAR2", 32, "B", null, null, false, null).matches(expected)).isFalse();
    }

    @Test
    void timestampMatcherAcceptsOnlyExactOracleDictionaryTypeAndScale() {
        var expected = OracleFinalSchemaPrecondition.engineObservationContract().columns()
                .stream().filter(column -> column.name().equals("ENGINE_OCCURRED_AT_"))
                .findFirst().orElseThrow();

        assertThat(new OracleFinalSchemaPrecondition.ActualColumn(
                "TIMESTAMP(6) WITH TIME ZONE", 0, null, null, 6,
                false, null).matches(expected)).isTrue();
        assertThat(new OracleFinalSchemaPrecondition.ActualColumn(
                "TIMESTAMP(3) WITH TIME ZONE", 0, null, null, 3,
                false, null).matches(expected)).isFalse();
        assertThat(new OracleFinalSchemaPrecondition.ActualColumn(
                "TIMESTAMP(6)", 0, null, null, 6,
                false, null).matches(expected)).isFalse();
    }

    @Test
    void indexContractsUseOracleCanonicalUtcEntriesForTimeZoneColumns() {
        var observationStatus = OracleFinalSchemaPrecondition.engineObservationContract().indexes()
                .stream().filter(index -> index.name().equals("IX_CM_AEO_STATUS"))
                .findFirst().orElseThrow();
        assertThat(observationStatus.entries())
                .containsExactly("STATUS_", "SYS_EXTRACT_UTC(CLAIMED_AT_)");

        var production = OracleFinalSchemaPrecondition.productionCommandContract().indexes();
        assertThat(production.stream().filter(index -> index.name().equals("IX_CM_ENGCMD_DUE"))
                .findFirst().orElseThrow().entries())
                .containsExactly("STATUS_", "SYS_EXTRACT_UTC(NEXT_ATTEMPT_AT_)");
        assertThat(production.stream().filter(index -> index.name().equals("IX_CM_ENGCMD_PROD_DUE"))
                .findFirst().orElseThrow().entries())
                .containsExactly("STATUS_", "SYS_EXTRACT_UTC(NEXT_ATTEMPT_AT_)",
                        "SYS_EXTRACT_UTC(CREATED_AT_)");
        assertThat(production.stream().filter(index -> index.name().equals("IX_CM_ENGCMD_LEASE"))
                .findFirst().orElseThrow().entries())
                .containsExactly("STATUS_", "SYS_EXTRACT_UTC(LEASE_EXPIRES_AT_)");
        assertThat(production.stream().filter(index -> index.name().equals("IX_CM_ENGCMD_REVIEW"))
                .findFirst().orElseThrow().entries())
                .containsExactly("STATUS_", "SYS_EXTRACT_UTC(UPDATED_AT_)");
    }

    @Test
    void optionalIndexGuardParsesAnExactOrderedOracleMetadataContract() {
        var guard = new OracleOptionalIndexPrecondition();
        guard.setName("UQ_CM_ENGCMD_OPERATION");
        guard.setTable("CM_ENGINE_COMMAND");
        guard.setUnique("true");
        guard.setEntries("CASEWHENTENANT_ID_ISNULLTHEN1ELSE0END|TENANT_ID_|OPERATION_ID_");

        assertThat(guard.expected()).isEqualTo(
                new OracleFinalSchemaPrecondition.IndexContract(
                        "UQ_CM_ENGCMD_OPERATION", "CM_ENGINE_COMMAND", true,
                        List.of("CASEWHENTENANT_ID_ISNULLTHEN1ELSE0END",
                                "TENANT_ID_", "OPERATION_ID_")));
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
                .hasSize(49).doesNotHaveDuplicates();
        assertThat(OracleByteSemanticsMigration.targets("engine-observation"))
                .hasSize(16).doesNotHaveDuplicates();
    }
}
