package org.casemgmt.poc.support;

import java.time.Duration;

import org.testcontainers.oracle.OracleContainer;
import org.testcontainers.utility.DockerImageName;

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
 * BPMN complaint release to every test through a whole edit-rerun cycle during development, with no
 * signal that the fixture on disk and the fixture actually exercised had diverged. A fresh
 * container per test-JVM run removes that whole class of self-inflicted staleness, at the cost of
 * the one-time startup above.
 */
public final class PocOracleSupport {

    private static final DockerImageName ORACLE_IMAGE = DockerImageName.parse(
                    "gvenzl/oracle-free:23.26.3-slim-faststart@sha256:f5ff19033860d662c821cb04eb10483fa94f14f78eae252d054291ea07028093")
            .asCompatibleSubstituteFor("gvenzl/oracle-free");

    public static final OracleContainer ORACLE = oracleContainer();

    private static OracleContainer oracleContainer() {
        return new OracleContainer(ORACLE_IMAGE)
                .withUsername("cm")
                .withPassword("cm")
                .withStartupTimeout(Duration.ofMinutes(5));
    }

    static {
        try {
            ORACLE.start();
        } catch (RuntimeException | Error failure) {
            preserveOracleLogs(failure);
            throw failure;
        }
    }

    private static void preserveOracleLogs(Throwable failure) {
        try {
            System.err.printf("Oracle container startup failed (%s).%n%s%n",
                    ORACLE_IMAGE, ORACLE.getLogs());
        } catch (Throwable logFailure) {
            failure.addSuppressed(logFailure);
        }
    }

    private PocOracleSupport() {}
}
