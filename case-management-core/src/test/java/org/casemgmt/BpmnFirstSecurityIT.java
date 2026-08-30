package org.casemgmt;

import org.casemgmt.error.InvalidCaseDefinitionException;
import org.casemgmt.release.JsonSchemaCaseContractValidator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Publication is the security boundary for a second, competing lifecycle authority. */
class BpmnFirstSecurityIT {

    @Test
    void rejectsLifecycleVocabularyFromAnOtherwiseValidBpmnContract() {
        assertThatThrownBy(() -> new JsonSchemaCaseContractValidator().validate("complaint", """
                {
                  "key":"complaint", "orchestrationMode":"BPMN", "fields":{}, "forms":{},
                  "lifecycle":{"closeWhen":"all-work-complete"}
                }
                """.getBytes()))
                .isInstanceOf(InvalidCaseDefinitionException.class)
                .hasMessageContaining("BPMN orchestration is authoritative")
                .hasMessageContaining("lifecycle");
    }
}
