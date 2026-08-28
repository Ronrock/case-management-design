package org.casemgmt.starter;

import org.casemgmt.engine.EngineGateway;
import org.casemgmt.engine.embedded.EmbeddedEngineEventBridge;
import org.casemgmt.engine.embedded.EmbeddedTransactionResourceValidator;
import org.casemgmt.engine.embedded.ProcessActivityClassifier;
import org.casemgmt.engine.embedded.ProcessCaseCorrelation;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.RepositoryService;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.operaton.bpm.engine.spring.SpringProcessEngineConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class EmbeddedTransactionAuthorityAutoConfigurationTest {

    @Test
    void registersValidatorForOperatonsDeclaredConfigurationTypeAndAcceptsQualifiedProxies() {
        var target = dataSource("context-shared");
        DataSource platformDataSource = new TransactionAwareDataSourceProxy(target);
        DataSource operatonDataSource = new LazyConnectionDataSourceProxy(target);
        var sharedTransactions = new DataSourceTransactionManager(operatonDataSource);
        ProxyFactory proxyFactory = new ProxyFactory(sharedTransactions);
        PlatformTransactionManager platformTransactionProxy =
                (PlatformTransactionManager) proxyFactory.getProxy();
        var engineConfiguration = springConfiguration(operatonDataSource, sharedTransactions);

        runner(platformDataSource, platformTransactionProxy, engineConfiguration)
                .withBean("operatonBpmDataSource", DataSource.class, () -> operatonDataSource)
                .withBean("operatonBpmTransactionManager", PlatformTransactionManager.class,
                        () -> sharedTransactions)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(EmbeddedTransactionResourceValidator.class);
                });
    }

    @Test
    void rejectsASeparateOperatonDataSourceAtContextStartup() {
        var platformDataSource = dataSource("context-platform");
        var operatonDataSource = dataSource("context-operaton");
        var transactions = new DataSourceTransactionManager(platformDataSource);
        var engineConfiguration = springConfiguration(operatonDataSource, transactions);

        runner(platformDataSource, transactions, engineConfiguration)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage("Embedded Operaton and case lifecycle must use "
                                    + "the same DataSource and transaction manager authority");
                });
    }

    @Test
    void rejectsASeparateOperatonTransactionManagerAtContextStartup() {
        var dataSource = dataSource("context-same-data-source");
        var platformTransactions = new DataSourceTransactionManager(dataSource);
        var operatonTransactions = new DataSourceTransactionManager(dataSource);
        var engineConfiguration = springConfiguration(dataSource, operatonTransactions);

        runner(dataSource, platformTransactions, engineConfiguration)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage("Embedded Operaton and case lifecycle must use "
                                    + "the same DataSource and transaction manager authority");
                });
    }

    @Test
    void embeddedContextWithoutAConfiguredOperatonEngineFailsInsteadOfSkippingValidation() {
        var dataSource = dataSource("context-no-engine");
        var transactions = new DataSourceTransactionManager(dataSource);

        baseRunner()
                .withBean("dataSource", DataSource.class, () -> dataSource)
                .withBean("transactionManager", PlatformTransactionManager.class,
                        () -> transactions)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("ProcessEngineConfigurationImpl");
                });
    }

    private ApplicationContextRunner runner(
            DataSource platformDataSource,
            PlatformTransactionManager platformTransactions,
            ProcessEngineConfigurationImpl engineConfiguration) {
        return baseRunner()
                .withBean("dataSource", DataSource.class, () -> platformDataSource)
                .withBean("transactionManager", PlatformTransactionManager.class,
                        () -> platformTransactions)
                .withBean("processEngineConfigurationImpl", ProcessEngineConfigurationImpl.class,
                        () -> engineConfiguration)
                .withBean(ProcessEngine.class, () -> mock(ProcessEngine.class));
    }

    private ApplicationContextRunner baseRunner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(
                        EmbeddedEngineAutoConfiguration.EmbeddedEngineGatewayConfiguration.class)
                .withPropertyValues("casemgmt.engine.mode=embedded")
                .withBean(EngineGateway.class, () -> mock(EngineGateway.class))
                .withBean(ProcessCaseCorrelation.class,
                        () -> processInstanceId -> "case-1")
                .withBean(ProcessActivityClassifier.class,
                        () -> mock(ProcessActivityClassifier.class))
                .withBean(EmbeddedEngineEventBridge.class,
                        () -> mock(EmbeddedEngineEventBridge.class))
                .withBean(RepositoryService.class, () -> mock(RepositoryService.class));
    }

    private static SpringProcessEngineConfiguration springConfiguration(
            DataSource dataSource, PlatformTransactionManager transactions) {
        var configuration = new SpringProcessEngineConfiguration();
        configuration.setDataSource(dataSource);
        configuration.setTransactionManager(transactions);
        return configuration;
    }

    private static SimpleDriverDataSource dataSource(String name) {
        return new SimpleDriverDataSource(new org.h2.Driver(),
                "jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1");
    }
}
