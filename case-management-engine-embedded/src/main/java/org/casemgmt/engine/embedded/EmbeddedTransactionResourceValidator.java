package org.casemgmt.engine.embedded;

import org.operaton.bpm.engine.spring.SpringProcessEngineConfiguration;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.Objects;

/** Fail-fast proof that embedded lifecycle callbacks can participate in one local transaction. */
public final class EmbeddedTransactionResourceValidator implements InitializingBean {

    private final DataSource platformDataSource;
    private final PlatformTransactionManager platformTransactions;
    private final SpringProcessEngineConfiguration engine;

    public EmbeddedTransactionResourceValidator(
            DataSource platformDataSource,
            PlatformTransactionManager platformTransactions,
            SpringProcessEngineConfiguration engine) {
        this.platformDataSource = Objects.requireNonNull(platformDataSource,
                "platformDataSource");
        this.platformTransactions = Objects.requireNonNull(platformTransactions,
                "platformTransactions");
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    @Override
    public void afterPropertiesSet() {
        if (unwrap(engine.getDataSource()) != unwrap(platformDataSource)
                || engine.getTransactionManager() != platformTransactions) {
            throw new IllegalStateException("Embedded Operaton and case lifecycle must use the "
                    + "same DataSource and transaction manager instances");
        }
    }

    private static DataSource unwrap(DataSource dataSource) {
        DataSource current = dataSource;
        while (current instanceof DelegatingDataSource delegating
                && delegating.getTargetDataSource() != null) {
            current = delegating.getTargetDataSource();
        }
        return current;
    }
}
