package org.casemgmt.rest.dto;

import org.casemgmt.domain.CaseInstance;
import org.casemgmt.domain.CasePriority;
import org.casemgmt.domain.CaseState;
import org.casemgmt.permissions.PermissionDecision;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
class CaseResponseFieldProjectionTest {

    @Test
    void emptyMaskDoesNotDiscloseCaseValues() {
        CaseInstance instance = instance();

        Dtos.CaseResponse response = Dtos.CaseResponse.of(instance, List.of(), List.of(),
                new PermissionDecision("case-1", true, List.of()));

        assertThat(response.title()).isNull();
        assertThat(response.businessKey()).isNull();
        assertThat(response.variables()).isEmpty();
    }

    @Test
    void projectsCanonicalVariableFieldsWithoutDisclosingOthers() {
        CaseInstance instance = instance();

        Dtos.CaseResponse response = Dtos.CaseResponse.of(instance, List.of(), List.of(),
                new PermissionDecision("case-1", true,
                        List.of("title", "variables.customerName")));

        assertThat(response.title()).isEqualTo("Sample case");
        assertThat(response.variables()).containsExactlyEntriesOf(Map.of("customerName", "Ada"));
    }

    private static CaseInstance instance() {
        return new CaseInstance("case-1", null, "tenant-1", "definition-1", "sample-case", 1,
                "C-1", "Sample case", CaseState.ACTIVE, CasePriority.MEDIUM, null, null,
                "worker-1", null, null, null,
                Map.of("customerName", "Ada", "secret", "hidden"), 1, null, null, null);
    }
}
