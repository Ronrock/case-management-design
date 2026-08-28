package org.casemgmt.repo;

import org.casemgmt.service.CanonicalPatch;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

    @Test
    void rejectsAnActiveTransactionBoundToADifferentDataSource() throws SQLException {
        DataSource canonicalDataSource = mock(DataSource.class);
        DataSource unrelatedDataSource = mock(DataSource.class);
        Connection unrelatedConnection = mock(Connection.class);
        when(unrelatedDataSource.getConnection()).thenReturn(unrelatedConnection);
        when(unrelatedConnection.getAutoCommit()).thenReturn(true);
        CaseRepository repository = new CaseRepository(canonicalDataSource);
        TransactionTemplate unrelatedTransaction = new TransactionTemplate(
                new DataSourceTransactionManager(unrelatedDataSource));
        CanonicalPatch patch = new CanonicalPatch("case-1", "reviewTask", 0L, List.of(
                new CanonicalPatch.FieldChange("/mappings/0", "decisionVar", "decision",
                        CanonicalPatch.WriteMode.REPLACE, false, null, "approved", false)));

        assertThatThrownBy(() -> unrelatedTransaction.executeWithoutResult(
                status -> repository.applyCanonicalPatch(patch)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("repository DataSource");
    }
}
