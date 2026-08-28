package org.casemgmt.repo;

import org.casemgmt.service.CanonicalPatch;
import org.casemgmt.service.CaseDataMappingService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.contains;
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
    void rejectsSynchronizationOnlyConnectionCreatedByRequireInsideForeignTransaction()
            throws SQLException {
        DataSource canonicalDataSource = canonicalDataSource();
        DataSource unrelatedDataSource = mock(DataSource.class);
        Connection unrelatedConnection = mock(Connection.class);
        when(unrelatedDataSource.getConnection()).thenReturn(unrelatedConnection);
        when(unrelatedConnection.getAutoCommit()).thenReturn(true);
        CaseRepository repository = new CaseRepository(canonicalDataSource);
        TransactionTemplate unrelatedTransaction = new TransactionTemplate(
                new DataSourceTransactionManager(unrelatedDataSource));

        unrelatedTransaction.executeWithoutResult(status -> {
            repository.require("case-1"); // mapTaskOutput's real read-before-apply sequence
            assertThatThrownBy(() -> repository.applyCanonicalPatch(patch()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("repository DataSource");
        });
    }

    @Test
    void joinsTargetTransactionWhenConstructedThroughTransactionAwareProxy() throws SQLException {
        DataSource targetDataSource = canonicalDataSource();
        TransactionAwareDataSourceProxy proxy = new TransactionAwareDataSourceProxy(targetDataSource);
        CaseRepository repository = new CaseRepository(proxy);
        TransactionTemplate targetTransaction = new TransactionTemplate(
                new DataSourceTransactionManager(targetDataSource));

        CaseDataMappingService.PatchResult result = targetTransaction.execute(
                status -> repository.applyCanonicalPatch(patch()));

        assertThat(result.status()).isEqualTo(CaseDataMappingService.PatchStatus.APPLIED);
        assertThat(result.caseVersion()).isEqualTo(1L);
    }

    private static CanonicalPatch patch() {
        return new CanonicalPatch("case-1", "reviewTask", 0L, List.of(
                new CanonicalPatch.FieldChange("/mappings/0", "decisionVar", "decision",
                        CanonicalPatch.WriteMode.REPLACE, false, null, "approved", false)));
    }

    private static DataSource canonicalDataSource() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement firstRead = mock(PreparedStatement.class);
        PreparedStatement lockedRead = mock(PreparedStatement.class);
        PreparedStatement update = mock(PreparedStatement.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.prepareStatement(contains("SELECT "))).thenReturn(firstRead, lockedRead);
        when(connection.prepareStatement(contains("UPDATE CM_CASE"))).thenReturn(update);
        ResultSet firstRow = caseRow();
        ResultSet lockedRow = caseRow();
        when(firstRead.executeQuery()).thenReturn(firstRow);
        when(lockedRead.executeQuery()).thenReturn(lockedRow);
        when(update.executeUpdate()).thenReturn(1);
        return dataSource;
    }

    private static ResultSet caseRow() throws SQLException {
        ResultSet row = mock(ResultSet.class);
        when(row.next()).thenReturn(true, false);
        when(row.getString("ID_")).thenReturn("case-1");
        when(row.getString("ENGINE_ID_")).thenReturn("engine-a");
        when(row.getString("TENANT_ID_")).thenReturn("tenant-a");
        when(row.getString("CASE_DEF_ID_")).thenReturn("sample-case:1");
        when(row.getString("CASE_DEF_KEY_")).thenReturn("sample-case");
        when(row.getInt("CASE_DEF_VER_")).thenReturn(1);
        when(row.getString("BUSINESS_KEY_")).thenReturn("BK-1");
        when(row.getString("TITLE_")).thenReturn("Sample");
        when(row.getString("STATE_")).thenReturn("ACTIVE");
        when(row.getString("PRIORITY_")).thenReturn("MEDIUM");
        when(row.getString("SLA_STATUS_")).thenReturn("NONE");
        when(row.getString("VARIABLES_JSON_")).thenReturn("{}");
        when(row.getLong("VERSION_")).thenReturn(0L);
        OffsetDateTime now = OffsetDateTime.parse("2026-08-28T10:00:00Z");
        when(row.getObject("CREATED_AT_", OffsetDateTime.class)).thenReturn(now);
        when(row.getObject("UPDATED_AT_", OffsetDateTime.class)).thenReturn(now);
        when(row.getString("PROJECTION_STATUS_")).thenReturn("CURRENT");
        return row;
    }
}
