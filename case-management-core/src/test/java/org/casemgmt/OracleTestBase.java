package org.casemgmt;

import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.oracle.OracleContainer;

import javax.sql.DataSource;
import java.sql.Connection;

@Testcontainers
public abstract class OracleTestBase {

    // Reused across all test classes in the JVM: starting Oracle takes ~40s.
    private static final OracleContainer ORACLE =
            new OracleContainer("gvenzl/oracle-free:23-slim-faststart")
                    .withUsername("cm")
                    .withPassword("cm")
                    .withReuse(true);

    private static DataSource dataSource;

    static {
        ORACLE.start();
        DriverManagerDataSource ds = new DriverManagerDataSource(
                ORACLE.getJdbcUrl(), ORACLE.getUsername(), ORACLE.getPassword());
        ds.setDriverClassName("oracle.jdbc.OracleDriver");
        dataSource = ds;
        migrate(ds);
    }

    private static void migrate(DataSource ds) {
        try (Connection c = ds.getConnection()) {
            var database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(c));
            try (var liquibase = new Liquibase(
                    "db/changelog/db.changelog-master.xml",
                    new ClassLoaderResourceAccessor(), database)) {
                liquibase.update("");
            }
        } catch (Exception e) {
            throw new IllegalStateException("Schema migration failed", e);
        }
    }

    protected static DataSource dataSource() {
        return dataSource;
    }

    protected JdbcClient jdbc() {
        return JdbcClient.create(dataSource);
    }
}
