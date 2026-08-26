package org.casemgmt;

import com.zaxxer.hikari.HikariDataSource;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.casemgmt.config.TransactionManagerConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.oracle.OracleContainer;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

// No @Testcontainers/@Container here on purpose. That extension manages a *per-class-instance*
// container lifecycle (start before the class's tests, stop after); ORACLE below is a single
// static container deliberately started once and shared for the module test fork's lifetime
// across every class that extends this one, torn down when that fork exits (Ryuk). Annotating with
// @Testcontainers without an @Container field would manage nothing and just be decoration -
// dropped rather than kept for show. If a future task genuinely wants a fresh container per
// test class, that is a different design (an instance field, not this shared static one).
public abstract class OracleTestBase {

    private static final String ORACLE_IMAGE = "gvenzl/oracle-free:23-slim-faststart";

    private static final String LIGHTWEIGHT_ORACLE_SPFILE = String.join("\n",
            "cat > /tmp/initFREE-light.ora <<'EOF'",
            "common_user_prefix=\"\"",
            "compatible=\"23.6.0\"",
            "control_files=\"/opt/oracle/oradata/FREE/control01.ctl\",\"/opt/oracle/oradata/FREE/control02.ctl\"",
            "control_management_pack_access=\"DIAGNOSTIC+TUNING\"",
            "cpu_count=2",
            "db_block_size=8192",
            "db_name=\"FREE\"",
            "diagnostic_dest=\"/opt/oracle\"",
            "dispatchers=\"(PROTOCOL=TCP) (SERVICE=FREEXDB)\"",
            "enable_pluggable_database=true",
            "fast_start_parallel_rollback=\"FALSE\"",
            "job_queue_processes=1",
            "local_listener=\"\"",
            "mle_prog_languages=\"OFF\"",
            "nls_language=\"AMERICAN\"",
            "nls_territory=\"AMERICA\"",
            "open_cursors=300",
            "pga_aggregate_target=256m",
            "processes=300",
            "remote_login_passwordfile=\"EXCLUSIVE\"",
            "sga_target=768m",
            "shared_servers=0",
            "spatial_vector_acceleration=FALSE",
            "undo_tablespace=\"UNDOTBS1\"",
            "EOF",
            "sqlplus -s / as sysdba <<'SQL'",
            "WHENEVER SQLERROR EXIT SQL.SQLCODE",
            "CREATE SPFILE='${ORACLE_HOME}/dbs/spfile${ORACLE_SID}.ora' FROM PFILE='/tmp/initFREE-light.ora';",
            "EXIT;",
            "SQL",
            "exec container-entrypoint.sh",
            "");

    // Reused across all core test classes in this module's forked JVM: starting Oracle takes ~40s.
    private static final OracleContainer ORACLE = oracleContainer();

    // Pooled rather than a plain DriverManagerDataSource: the schema-reset hooks below run
    // from every extending class's @BeforeEach (every test method) and @AfterAll, and Tasks
    // 4-6 add roughly ten more classes on top of this base. Each unpooled DriverManager
    // connection is a fresh physical TNS connect+logon; at the volume ~10 classes' worth of
    // per-test cleanup produces, that reliably exhausts the listener's protocol handlers
    // (ORA-12516) - reproduced directly: a tight loop of raw unpooled connections hit the
    // same error, while pacing connections out did not, confirming it is connection churn,
    // not elapsed time. A small Hikari pool (already on the classpath via
    // spring-boot-starter-jdbc) reuses a handful of physical connections instead.
    private static HikariDataSource dataSource;

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

    private static OracleContainer oracleContainer() {
        return new OracleContainer(ORACLE_IMAGE)
                .withUsername("cm")
                .withPassword("cm")
                .withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint(
                        "bash", "-lc", LIGHTWEIGHT_ORACLE_SPFILE));
    }

    static {
        try {
            ORACLE.start();
            HikariDataSource ds = new HikariDataSource();
            ds.setJdbcUrl(ORACLE.getJdbcUrl());
            ds.setUsername(ORACLE.getUsername());
            ds.setPassword(ORACLE.getPassword());
            ds.setDriverClassName("oracle.jdbc.OracleDriver");
            ds.setPoolName("OracleTestBasePool");
            // A handful is plenty: this pool only ever serves one JVM's worth of sequential
            // test execution, never concurrent load.
            ds.setMaximumPoolSize(5);
            dataSource = ds;
            migrate(ds);
            // The pool has no natural close() call site otherwise: dataSource is a static
            // field with no owning test instance to tie a teardown to. Closing it here means
            // it releases its physical connections before Ryuk removes the container, rather
            // than leaking connections until the JVM's own exit tears the socket down anyway.
            Runtime.getRuntime().addShutdownHook(new Thread(ds::close, "OracleTestBasePool-close"));
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

    // Isolation is automatic and inherited: every class extending OracleTestBase gets a clean
    // schema before each of its own test methods (JUnit runs a superclass's @BeforeEach before
    // the subclass's own, so a subclass @BeforeEach that inserts fixtures still runs against a
    // clean slate) and again after its last test method finishes, so it never leaves the
    // schema dirty for whatever class or developer looks at the container next. Subclasses
    // need do nothing to get this - no per-class opt-in, no convention to remember.
    @BeforeEach
    void resetSchemaBeforeEachTest() {
        deleteAllCaseManagementData();
    }

    @AfterAll
    static void resetSchemaAfterAllTestsInClass() {
        deleteAllCaseManagementData();
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

    /**
     * Builds a minimal Spring context for tests that need a genuine
     * {@code @Transactional}-capable bean (Task 13's prerequisite — see
     * {@link TransactionManagerConfig}) rather than the plain {@code JdbcClient}/{@code
     * DataSource} every other repository test in this module uses directly.
     *
     * <p>Deliberately NOT built once and shared statically the way {@link #ORACLE} and
     * {@link #dataSource} are: unlike the container, an {@code AnnotationConfigApplicationContext}
     * is cheap to build and tear down (no process, no network handshake, no migration) and each
     * caller typically wants a different set of {@code @Transactional} beans registered — sharing
     * one context across test classes would mean either registering every caller's beans into a
     * single context (coupling unrelated tests' bean definitions together) or fighting Spring
     * over re-registering beans after refresh. Building one per test class keeps registration
     * simple: call this from a {@code @BeforeEach}, close the returned context from the matching
     * {@code @AfterEach}. The {@link DataSource} bean wired in is the SAME pooled Hikari instance
     * every other test in the JVM uses — this opens no second connection pool and starts no
     * second container.
     *
     * @param componentClasses additional {@code @Configuration}/{@code @Component} classes to
     *                         register alongside {@link TransactionManagerConfig} — typically a
     *                         test-local class with one or more {@code @Transactional} methods.
     */
    protected static AnnotationConfigApplicationContext springContext(Class<?>... componentClasses) {
        var ctx = new AnnotationConfigApplicationContext();
        // setDestroyMethodName("") is load-bearing, not decoration: Spring infers a "(inferred)"
        // destroy method for any singleton whose type exposes close()/shutdown(), and
        // HikariDataSource exposes close(). Without disabling that inference, closing THIS
        // per-test context (as every caller's @AfterEach is expected to do) calls close() on the
        // shared static pool every other test class in the JVM depends on - confirmed by running
        // this: it killed OracleTestBasePool and broke every subsequent test in the same JVM run
        // with "HikariDataSource has been closed". The pool's own lifecycle is already owned by
        // OracleTestBase's shutdown hook (see the static initializer above); this context must
        // never touch it.
        ctx.registerBean(DataSource.class, () -> dataSource, bd -> bd.setDestroyMethodName(""));
        ctx.register(TransactionManagerConfig.class);
        if (componentClasses.length > 0) {
            ctx.register(componentClasses);
        }
        ctx.refresh();
        return ctx;
    }

    // All CM_ tables share one schema for the JVM's whole lifetime (see class comment). This
    // is the single, structural reset that keeps that safe: it runs automatically from the
    // @BeforeEach/@AfterAll above, in FK-safe (child-before-parent) order derived once here
    // from db-design.sql's foreign keys, instead of being re-derived - and re-risked - by
    // every later task. Static (not protected-instance-only) so the static @AfterAll above can
    // call it directly, and so no subclass can bypass it. Deliberately DELETE, not TRUNCATE:
    // row counts per test are tiny, and DELETE keeps the statement list simple to reason about
    // without needing to fight referential-integrity ordering rules that TRUNCATE enforces
    // more strictly across a whole batch.
    //
    // Runs all 26 deletes over ONE borrowed connection, as one JDBC batch, rather than one
    // connection per table: independent of pooling, this is strictly less load per call (one
    // borrow/return instead of 26) and faster, and it is what actually eliminates the bulk of
    // the connection churn this method used to cause on every single test method across every
    // extending class.
    private static void deleteAllCaseManagementData() {
        List<String> tablesChildToParent = List.of(
                "CM_CASE_DEF_BINDING",
                "CM_CASE_DEF_RELEASE",
                "CM_ENGINE_POLL_CHECKPOINT",
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
        try (Connection c = dataSource.getConnection();
             Statement stmt = c.createStatement()) {
            for (String table : tablesChildToParent) {
                stmt.addBatch("DELETE FROM " + table);
            }
            stmt.executeBatch();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to reset schema between tests", e);
        }
    }
}
