package org.casemgmt.engine.embedded;

import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.spring.SpringProcessEngineConfiguration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    private static SimpleDriverDataSource dataSource(String name) {
        return new SimpleDriverDataSource(new org.h2.Driver(),
                "jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1");
    }
}
