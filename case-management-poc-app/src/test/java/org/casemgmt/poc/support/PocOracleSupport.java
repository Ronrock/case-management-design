package org.casemgmt.poc.support;

import org.testcontainers.oracle.OracleContainer;

/**
 * One Testcontainers Oracle instance, shared by every full-application IT in this module
 * (surefire runs all of a module's test classes in a single forked JVM by default, so a plain
 * static field here starts the container exactly once for the whole module's test run —
 * starting Oracle takes roughly 40 seconds, and every full {@code @SpringBootTest} class below
 * pays for it only once).
 *
 * <p>Deliberately not reusable across separate {@code mvn test} invocations: {@code
 * PocBootstrap.seedDefinition} is idempotent ("does complaint v1 already exist? skip if so"), so
 * a container reused from an earlier local run would silently keep serving a stale {@code
 * complaint-v1.json} to every test through a whole edit-rerun cycle during development, with no
 * signal that the fixture on disk and the fixture actually exercised had diverged. A fresh
 * container per test-JVM run removes that whole class of self-inflicted staleness, at the cost of
 * the one-time startup above.
 */
public final class PocOracleSupport {

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

    public static final OracleContainer ORACLE = oracleContainer();

    private static OracleContainer oracleContainer() {
        return new OracleContainer(ORACLE_IMAGE)
                .withUsername("cm")
                .withPassword("cm")
                .withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint(
                        "bash", "-lc", LIGHTWEIGHT_ORACLE_SPFILE));
    }

    static {
        ORACLE.start();
    }

    private PocOracleSupport() {}
}
