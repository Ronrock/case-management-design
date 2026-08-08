package org.casemgmt.poc.support;

import org.testcontainers.oracle.OracleContainer;

/**
 * One Testcontainers Oracle instance, shared by every full-application IT in this module
 * (surefire runs all of a module's test classes in a single forked JVM by default, so a plain
 * static field here starts the container exactly once for the whole module's test run —
 * starting Oracle takes roughly 40 seconds, and every full {@code @SpringBootTest} class below
 * pays for it only once).
 *
 * <p>Deliberately NOT {@code withReuse(true)}, unlike {@code case-management-core}'s
 * {@code OracleTestBase}. That flag keeps a container alive ACROSS separate {@code mvn test}
 * invocations — exactly wrong here: {@code PocBootstrap.seedDefinition} is idempotent
 * ("does complaint v1 already exist? skip if so"), so a container reused from an earlier local
 * run would silently keep serving a stale {@code complaint-v1.json} to every test through a
 * whole edit-rerun cycle during development, with no signal that the fixture on disk and the
 * fixture actually exercised had diverged. A fresh container per test-JVM run removes that
 * whole class of self-inflicted staleness, at the cost of the one-time startup above.
 */
public final class PocOracleSupport {

    public static final OracleContainer ORACLE =
            new OracleContainer("gvenzl/oracle-free:23-slim-faststart")
                    .withUsername("cm")
                    .withPassword("cm");

    static {
        ORACLE.start();
    }

    private PocOracleSupport() {}
}
