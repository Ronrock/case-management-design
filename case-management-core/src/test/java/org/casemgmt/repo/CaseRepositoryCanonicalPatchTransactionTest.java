package org.casemgmt.repo;

import org.casemgmt.service.CanonicalPatch;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class CaseRepositoryCanonicalPatchTransactionTest {

    @Test
    void rejectsCanonicalCompareAndApplyWithoutAnActiveCallerTransaction() {
        CaseRepository repository = new CaseRepository(mock(JdbcClient.class));
        CanonicalPatch patch = new CanonicalPatch("case-1", "reviewTask", 0L, List.of(
                new CanonicalPatch.FieldChange("/mappings/0", "decisionVar", "decision",
                        CanonicalPatch.WriteMode.REPLACE, false, null, "approved", false)));

        assertThatThrownBy(() -> repository.applyCanonicalPatch(patch))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("active caller transaction");
    }
}
