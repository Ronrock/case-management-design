package org.casemgmt;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** Prevents a future edit from turning the SLA constraint replacement into a blind drop. */
class SlaLifecycleChangelogStaticValidationTest {

    @Test
    void statusConstraintReplacementRecognisesOnlyKnownShapesAndFailsClosedOtherwise() throws Exception {
        String xml;
        try (var input = getClass().getResourceAsStream("/db/changelog/cm-sla-lifecycle.xml")) {
            assertThat(input).isNotNull();
            xml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(xml).contains("id=\"cm-sla-root-terminalization-status\"")
                .contains("onFail=\"HALT\"")
                .contains("CK_CM_SLAR_STATUS is not a recognised SLA lifecycle constraint")
                .contains("''RUNNING'',''PAUSED'',''MET'',''BREACHED''")
                .contains("''RUNNING'',''PAUSED'',''MET'',''CANCELLED'',''BREACHED''")
                .contains("CREATE UNIQUE INDEX UQ_CM_SLAR_OCCURRENCE")
                .doesNotContain("<addUniqueConstraint tableName=\"CM_SLA_RECORD\"");
    }
}
