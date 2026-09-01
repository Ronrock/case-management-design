package org.casemgmt.starter;

import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.operaton.bpm.engine.spring.SpringProcessEngineConfiguration;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class EmbeddedTransactionResourceValidatorTest {

    @Test
    void acceptsTheSameDataSourceAndTransactionManagerInstances() {
        var dataSource = dataSource("same");
        var transactions = new DataSourceTransactionManager(dataSource);
        var engine = new SpringProcessEngineConfiguration();
        engine.setDataSource(dataSource);
        engine.setTransactionManager(transactions);

        assertThatCode(() -> new EmbeddedTransactionResourceValidator(
                dataSource, transactions, engine).afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsLazyManagerResourceWhenRepositoryUsesTransactionAwareRawResource() {
        var target = dataSource("proxied-shared-resource");
        DataSource platformDataSource = new TransactionAwareDataSourceProxy(target);
        DataSource engineDataSource = new LazyConnectionDataSourceProxy(target);
        var sharedTransactions = new DataSourceTransactionManager(engineDataSource);
        var engine = new SpringProcessEngineConfiguration();
        engine.setDataSource(engineDataSource);
        engine.setTransactionManager(sharedTransactions);

        assertThatThrownBy(() -> new EmbeddedTransactionResourceValidator(
                platformDataSource, sharedTransactions, engine).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("repository DataSource transaction resource");
    }

    @Test
    void acceptsTransactionAwareRepositoryUsingTheManagersExactRawResource() {
        var target = dataSource("transaction-aware-shared-resource");
        DataSource repositoryDataSource = new TransactionAwareDataSourceProxy(target);
        var sharedTransactions = new DataSourceTransactionManager(repositoryDataSource);
        var engine = new SpringProcessEngineConfiguration();
        engine.setDataSource(target);
        engine.setTransactionManager(sharedTransactions);

        assertThatCode(() -> new EmbeddedTransactionResourceValidator(
                repositoryDataSource, sharedTransactions, engine).afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsASeparateEngineDataSourceEvenWhenTransactionManagersHaveTheSameType() {
        var platformDataSource = dataSource("platform");
        var engineDataSource = dataSource("engine");
        var platformTransactions = new DataSourceTransactionManager(platformDataSource);
        var engineTransactions = new DataSourceTransactionManager(engineDataSource);
        var engine = new SpringProcessEngineConfiguration();
        engine.setDataSource(engineDataSource);
        engine.setTransactionManager(engineTransactions);

        assertThatThrownBy(() -> new EmbeddedTransactionResourceValidator(
                platformDataSource, platformTransactions, engine).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("same DataSource")
                .hasMessageContaining("transaction manager");
    }

    @Test
    void rejectsSeparateTransactionManagersEvenForTheSameDataSource() {
        var dataSource = dataSource("shared-resource");
        var platformTransactions = new DataSourceTransactionManager(dataSource);
        var engineTransactions = new DataSourceTransactionManager(dataSource);
        var engine = new SpringProcessEngineConfiguration();
        engine.setDataSource(dataSource);
        engine.setTransactionManager(engineTransactions);

        assertThatThrownBy(() -> new EmbeddedTransactionResourceValidator(
                dataSource, platformTransactions, engine).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("transaction manager");
    }

    @Test
    void rejectsANonSpringOperatonConfigurationExplicitly() {
        var dataSource = dataSource("non-spring");
        var transactions = new DataSourceTransactionManager(dataSource);
        ProcessEngineConfigurationImpl engine = mock(ProcessEngineConfigurationImpl.class);

        assertThatThrownBy(() -> new EmbeddedTransactionResourceValidator(
                dataSource, transactions, engine).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SpringProcessEngineConfiguration");
    }

    private static SimpleDriverDataSource dataSource(String name) {
        return new SimpleDriverDataSource(new org.h2.Driver(),
                "jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1");
    }
}
