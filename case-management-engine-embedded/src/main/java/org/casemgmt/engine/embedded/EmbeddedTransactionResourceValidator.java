package org.casemgmt.engine.embedded;

import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.operaton.bpm.engine.spring.SpringProcessEngineConfiguration;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.ResourceTransactionManager;

import javax.sql.DataSource;
import java.util.Objects;

/** Fail-fast proof that embedded lifecycle callbacks can participate in one local transaction. */
public final class EmbeddedTransactionResourceValidator implements InitializingBean {

    private final DataSource platformDataSource;
    private final PlatformTransactionManager platformTransactions;
    private final ProcessEngineConfigurationImpl engine;

    public EmbeddedTransactionResourceValidator(
            DataSource platformDataSource,
            PlatformTransactionManager platformTransactions,
            ProcessEngineConfigurationImpl engine) {
        this.platformDataSource = Objects.requireNonNull(platformDataSource,
                "platformDataSource");
        this.platformTransactions = Objects.requireNonNull(platformTransactions,
                "platformTransactions");
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    @Override
    public void afterPropertiesSet() {
        if (!(engine instanceof SpringProcessEngineConfiguration springEngine)) {
            throw new IllegalStateException("Embedded Operaton requires a "
                    + "SpringProcessEngineConfiguration to share transaction authority");
        }
        if (springEngine.getTransactionManager() != platformTransactions) {
            throw new IllegalStateException("Embedded Operaton and case lifecycle must use the "
                    + "same DataSource and transaction manager authority");
        }
        if (!(platformTransactions instanceof ResourceTransactionManager resourceManager)
                || !(resourceManager.getResourceFactory() instanceof DataSource managedResource)) {
            throw new IllegalStateException("Embedded transaction manager must expose its exact "
                    + "managed DataSource resource");
        }
        DataSource repositoryResource = transactionResource(platformDataSource);
        if (managedResource != repositoryResource) {
            throw new IllegalStateException("Embedded transaction manager does not manage the "
                    + "repository DataSource transaction resource");
        }
        if (managedResource != transactionResource(springEngine.getDataSource())) {
            throw new IllegalStateException("Embedded Operaton does not use the transaction "
                    + "manager's exact DataSource resource");
        }
    }

    /** Mirrors Spring's transaction-aware proxy participation without flattening other proxies. */
    private static DataSource transactionResource(DataSource dataSource) {
        DataSource current = Objects.requireNonNull(dataSource, "dataSource");
        while (current instanceof TransactionAwareDataSourceProxy proxy) {
            current = proxy.getTargetDataSource();
            if (current == null) {
                throw new IllegalStateException(
                        "TransactionAwareDataSourceProxy has no target DataSource");
            }
        }
        return current;
    }
}
