package org.casemgmt.engine.embedded;

import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.operaton.bpm.engine.spring.SpringProcessEngineConfiguration;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.transaction.PlatformTransactionManager;

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
        if (unwrapDataSource(springEngine.getDataSource())
                != unwrapDataSource(platformDataSource)
                || transactionAuthority(springEngine.getTransactionManager())
                != transactionAuthority(platformTransactions)) {
            throw new IllegalStateException("Embedded Operaton and case lifecycle must use the "
                    + "same DataSource and transaction manager authority");
        }
    }

    private static Object transactionAuthority(PlatformTransactionManager transactions) {
        return unwrapAopProxy(transactions);
    }

    private static DataSource unwrapDataSource(DataSource dataSource) {
        Object current = unwrapAopProxy(dataSource);
        while (current instanceof DelegatingDataSource delegating
                && delegating.getTargetDataSource() != null) {
            current = unwrapAopProxy(delegating.getTargetDataSource());
        }
        if (!(current instanceof DataSource unwrapped)) {
            throw new IllegalStateException(
                    "Embedded transaction authority did not resolve to a DataSource");
        }
        return unwrapped;
    }

    private static Object unwrapAopProxy(Object candidate) {
        Object current = Objects.requireNonNull(candidate, "transaction resource");
        Object target;
        while ((target = AopProxyUtils.getSingletonTarget(current)) != null
                && target != current) {
            current = target;
        }
        return current;
    }
}
