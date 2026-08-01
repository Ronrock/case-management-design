package org.casemgmt;

import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.oracle.OracleContainer;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;

// No @Testcontainers/@Container here on purpose. That extension manages a *per-class-instance*
// container lifecycle (start before the class's tests, stop after); ORACLE below is a single
// static container deliberately started once and shared for the whole JVM's lifetime across
// every class that extends this one, torn down only when the JVM exits (Ryuk). Annotating with
// @Testcontainers without an @Container field would manage nothing and just be decoration -
// dropped rather than kept for show. If a future task genuinely wants a fresh container per
// test class, that is a different design (an instance field, not this shared static one).
public abstract class OracleTestBase {

    // Reused across all test classes in the JVM: starting Oracle takes ~40s.
    private static final OracleContainer ORACLE =
            new OracleContainer("gvenzl/oracle-free:23-slim-faststart")
                    .withUsername("cm")
                    .withPassword("cm")
                    .withReuse(true);

    private static DataSource dataSource;

    // Captures a startup/migration failure instead of letting it escape the static
    // initializer. A Throwable escaping <clinit> makes the JVM record this class as
    // permanently unusable: the FIRST extending class to touch OracleTestBase sees the real
    // exception, but every subsequent class in the same JVM gets a bare
    // "NoClassDefFoundError: Could not initialize class OracleTestBase" with the actual
    // Liquibase/Testcontainers cause nowhere in the trace - a false lead for whoever debugs
    // it next, since nine out of ten extending classes would point at class-loading, not the
    // real changeset that broke. Recording the failure here and rethrowing it from a
    // per-class @BeforeAll (below) means class init always succeeds and every single
    // extending class reports the real cause.
    private static Throwable startupFailure;

    static {
        try {
            ORACLE.start();
            DriverManagerDataSource ds = new DriverManagerDataSource(
                    ORACLE.getJdbcUrl(), ORACLE.getUsername(), ORACLE.getPassword());
            ds.setDriverClassName("oracle.jdbc.OracleDriver");
            dataSource = ds;
            migrate(ds);
        } catch (Throwable t) {
            startupFailure = t;
        }
    }

    @BeforeAll
    static void verifyOracleStartedAndMigrated() {
        if (startupFailure != null) {
            throw new IllegalStateException("Schema migration failed", startupFailure);
        }
    }

    private static void migrate(DataSource ds) throws Exception {
        try (Connection c = ds.getConnection()) {
            var database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(c));
            try (var liquibase = new Liquibase(
                    "db/changelog/db.changelog-master.xml",
                    new ClassLoaderResourceAccessor(), database)) {
                liquibase.update("");
            }
        }
    }

    protected static DataSource dataSource() {
        return dataSource;
    }

    protected JdbcClient jdbc() {
        return JdbcClient.create(dataSource);
    }

    // All CM_ tables share one schema for the JVM's whole lifetime (see class comment), so
    // without an explicit reset, rows left behind by one test class are visible to every
    // class that runs after it - order-dependent collisions that JUnit gives no guarantee
    // against. Call this from a @BeforeEach in each extending class rather than hand-writing
    // per-class DELETE lists: the FK-safe (child-before-parent) order is captured once, here,
    // instead of being re-derived - and re-risked - by every later task. Deliberately DELETE,
    // not TRUNCATE: row counts per test are tiny, and DELETE keeps the statement list simple
    // to reason about without needing to fight referential-integrity ordering rules that
    // TRUNCATE enforces more strictly across a whole batch.
    protected void deleteAllCaseManagementData() {
        List<String> tablesChildToParent = List.of(
                "CM_TASK",
                "CM_MILESTONE",
                "CM_SLA_RECORD",
                "CM_PARTICIPANT",
                "CM_COMMENT",
                "CM_DOCUMENT",
                "CM_CASE_LINK",
                "CM_LINKED_PROCESS",
                "CM_PLAN_ITEM",
                "CM_CASE",
                "CM_PLAN_ITEM_DEF",
                "CM_CASE_DEF",
                "CM_SLA_TARGET",
                "CM_SLA_POLICY",
                "CM_BUSINESS_CALENDAR",
                "CM_WEBHOOK_DELIVERY",
                "CM_WEBHOOK_SUB",
                "CM_EVENT",
                "CM_BULK_OPERATION_ITEM",
                "CM_BULK_OPERATION",
                "CM_DEF_IDENTITY_LINK",
                "CM_ENGINE_COMMAND",
                "CM_QUEUE",
                "CM_SAVED_FILTER",
                "CM_IDEMPOTENCY_KEY",
                "CM_AUDIT_LOG");
        JdbcClient client = jdbc();
        for (String table : tablesChildToParent) {
            client.sql("DELETE FROM " + table).update();
        }
    }
}
