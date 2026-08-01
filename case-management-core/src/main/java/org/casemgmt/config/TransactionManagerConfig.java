package org.casemgmt.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;

/**
 * Registers a real {@link PlatformTransactionManager} for case-management-core.
 *
 * <p>Task 5 discovered this module had no transaction manager at all: every {@code JdbcClient}
 * statement ran on whatever autocommitted connection Hikari handed out, so any
 * {@code @Transactional} annotation a later task wrote would have been a silent no-op — no AOP
 * proxy exists to intercept the call, so nothing opens a transaction, commits, or rolls back.
 * That silently breaks two claims the spec makes:
 *
 * <ul>
 * <li>§6.1's transactional outbox needs the entity mutation, the {@code CM_EVENT} row, the
 * {@code CM_AUDIT_LOG} row and the {@code CM_WEBHOOK_DELIVERY} fan-out rows to commit as one
 * unit — "no event for a rolled-back change, no lost events".</li>
 * <li>§3.5 says embedded mode's whole advantage over remote mode is that the Operaton engine
 * call joins the <em>same</em> case transaction. Without a real transaction manager, "joins the
 * same transaction" is fiction: there is no transaction to join.</li>
 * </ul>
 *
 * <p>A {@link DataSourceTransactionManager} bound to the module's single {@link DataSource} is
 * the minimum that makes {@code @Transactional} genuine: it borrows a connection from the pool,
 * turns autocommit off, commits on normal method return, and rolls back on any unchecked
 * exception (Spring's default rollback rule — checked exceptions do NOT trigger rollback unless
 * a method's {@code @Transactional} explicitly opts them in). It is deliberately NOT JTA/XA: this
 * module has exactly one {@link DataSource}, so a resource-local transaction manager is both
 * sufficient and simpler.
 *
 * <p>Tasks 1-6's explicit connection-scoped transactions (see
 * {@code CaseDefinitionRepository.insert}) predate this class and remain valid as-is — they are
 * not rewritten by it. Everything from Task 13 onward that needs atomicity across more than one
 * repository call should reach for {@code @Transactional} on a Spring-managed bean instead of
 * hand-rolling another {@code SingleConnectionDataSource} block.
 *
 * <p>Not a Spring Boot auto-configuration: case-management-core is a library module with no
 * {@code @SpringBootApplication} of its own. The eventual application assembly
 * (case-management-poc-app) imports or component-scans this class so its beans exist in the real
 * application context. {@code org.casemgmt.OracleTestBase#springContext} does the same on a much
 * smaller scale for persistence tests that need to prove an {@code @Transactional} method
 * genuinely commits and rolls back (see {@code TransactionManagerTest}).
 */
@Configuration
@EnableTransactionManagement
public class TransactionManagerConfig {

    @Bean
    public PlatformTransactionManager transactionManager(DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }
}
