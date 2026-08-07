package org.casemgmt.poc;

import org.casemgmt.poc.support.PocOracleSupport;
import org.casemgmt.repo.SlaRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.IdentityService;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fix round 1, Minor (review): {@code PocBootstrap}'s seeding used to be repair-blind — a
 * partially-seeded database (an existing user missing a group a later build added, or a crash
 * between the SLA calendar and its policy/targets) stayed broken forever, since every guard
 * either skipped a whole method once ANYTHING existed, or only ever ran inside a
 * "doesn't exist yet" branch. This boots one real {@link PocApplication} context (letting {@link
 * PocBootstrap} seed normally), deliberately damages the seeded state directly against the same
 * Oracle schema — removes {@code alice}'s {@code tenant:t1} membership and the
 * {@code sla-resolution} SLA target — closes that context, then boots a SECOND, fresh context
 * against the SAME database and asserts both are repaired without a full re-seed being needed
 * for anything else.
 *
 * <p><b>Damage containment (Task 27, carried Minor).</b> This class writes destructively to the
 * Oracle schema every other class in this module's single test JVM shares: with {@code alice}'s
 * tenant membership gone, every later request she makes answers 403 and unrelated classes fail
 * for a reason that looks nothing like this one. Earlier only the FIRST boot was wrapped, and its
 * {@code finally} merely closed the context — so any assertion failure between the deletes and the
 * second boot left the schema broken and turned one failure into several.
 *
 * <p>Restoration is now unconditional and, deliberately, does NOT go through {@link PocBootstrap}:
 * {@link #restoreDamage()} re-applies the exact rows it captured before deleting them, over a raw
 * JDBC connection to the same container, from an {@code @AfterEach} that runs whether the test
 * passed, failed, or threw. Using the seeding path as the cleanup path would have made cleanup
 * fail in precisely the scenario cleanup exists for — a broken repair mechanism — so the restore
 * shares no code with the thing under test. Both statements are idempotent (guarded by
 * {@code WHERE NOT EXISTS} / a row-existence check), so a successful run, where {@code PocBootstrap}
 * has already repaired everything, restores nothing and duplicates nothing.
 */
class PocBootstrapRepairIT {

    private static final String ALICE = "alice";
    private static final String TENANT_GROUP = "tenant:t1";
    private static final String SLA_TARGET_ID = "sla-resolution";

    /** The {@code CM_SLA_TARGET} row as it stood immediately before deletion, or null if this
     * test never got as far as deleting it. Column name -> value, in table order. */
    private Map<String, Object> deletedSlaTarget;

    /** Set the instant before the membership delete is issued, so restoration is attempted for
     * every failure mode after that point, including one inside the delete's own assertions. */
    private boolean membershipDeleted;

    @Test
    void seedingRepairsAPartiallySeededDatabaseOnTheNextStartup() throws Exception {
        ConfigurableApplicationContext first = bootPocApplication();
        try {
            IdentityService identity = first.getBean(IdentityService.class);
            SlaRepository sla = first.getBean(SlaRepository.class);

            // Confirm the damage actually lands on what a fresh boot seeded, not on nothing.
            assertThat(identity.createGroupQuery().groupMember(ALICE).list())
                    .extracting(g -> g.getId()).contains(TENANT_GROUP);
            assertThat(sla.targetsFor("sla-complaint")).extracting(SlaRepository.TargetRow::id)
                    .contains(SLA_TARGET_ID);

            deletedSlaTarget = captureSlaTarget(SLA_TARGET_ID);
            assertThat(deletedSlaTarget)
                    .as("the row to be deleted must be captured before deleting it, or it cannot be restored")
                    .isNotEmpty();

            membershipDeleted = true;
            identity.deleteMembership(ALICE, TENANT_GROUP);
            deleteSlaTarget(SLA_TARGET_ID);

            assertThat(identity.createGroupQuery().groupMember(ALICE).list())
                    .extracting(g -> g.getId()).doesNotContain(TENANT_GROUP);
            assertThat(sla.targetsFor("sla-complaint")).extracting(SlaRepository.TargetRow::id)
                    .doesNotContain(SLA_TARGET_ID);
        } finally {
            first.close();
        }

        ConfigurableApplicationContext second = bootPocApplication();
        try {
            IdentityService identity = second.getBean(IdentityService.class);
            SlaRepository sla = second.getBean(SlaRepository.class);

            assertThat(identity.createGroupQuery().groupMember(ALICE).list())
                    .as("alice's tenant:t1 membership is repaired by the next startup, not lost forever")
                    .extracting(g -> g.getId()).contains(TENANT_GROUP);
            assertThat(sla.targetsFor("sla-complaint"))
                    .as("the missing SLA target is repaired by the next startup")
                    .extracting(SlaRepository.TargetRow::id).contains(SLA_TARGET_ID);

            // And the untouched rows were left alone rather than duplicated/reset.
            assertThat(identity.createUserQuery().userId(ALICE).count()).isEqualTo(1);
            assertThat(sla.targetsFor("sla-complaint")).hasSize(2);
        } finally {
            second.close();
        }
    }

    /**
     * Puts back exactly what this class deleted, over raw JDBC, whatever happened above. Runs
     * even when the test failed — that is the whole point — and is a no-op on the happy path,
     * where {@code PocBootstrap} has already restored both rows.
     */
    @AfterEach
    void restoreDamage() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                PocOracleSupport.ORACLE.getJdbcUrl(),
                PocOracleSupport.ORACLE.getUsername(),
                PocOracleSupport.ORACLE.getPassword())) {

            if (membershipDeleted) {
                // Operaton's own membership table. Guarded so the ordinary, fully-repaired run
                // does not attempt a duplicate-key insert.
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO ACT_ID_MEMBERSHIP (USER_ID_, GROUP_ID_)
                        SELECT ?, ? FROM DUAL WHERE NOT EXISTS (
                            SELECT 1 FROM ACT_ID_MEMBERSHIP WHERE USER_ID_ = ? AND GROUP_ID_ = ?)""")) {
                    statement.setString(1, ALICE);
                    statement.setString(2, TENANT_GROUP);
                    statement.setString(3, ALICE);
                    statement.setString(4, TENANT_GROUP);
                    statement.executeUpdate();
                }
            }

            if (deletedSlaTarget != null && !rowExists(connection, SLA_TARGET_ID)) {
                List<String> columns = new ArrayList<>(deletedSlaTarget.keySet());
                String sql = "INSERT INTO CM_SLA_TARGET (" + String.join(", ", columns) + ") VALUES ("
                        + String.join(", ", columns.stream().map(c -> "?").toList()) + ")";
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    for (int i = 0; i < columns.size(); i++) {
                        statement.setObject(i + 1, deletedSlaTarget.get(columns.get(i)));
                    }
                    statement.executeUpdate();
                }
            }
        }
    }

    /** Reads the row generically (column name -> value) so the restore never has to be
     * re-typed when {@code CM_SLA_TARGET} gains a column. CLOBs come back as Strings, which is
     * what {@code setObject} needs to put them back. */
    private Map<String, Object> captureSlaTarget(String targetId) throws Exception {
        Map<String, Object> row = new LinkedHashMap<>();
        try (Connection connection = DriverManager.getConnection(
                PocOracleSupport.ORACLE.getJdbcUrl(),
                PocOracleSupport.ORACLE.getUsername(),
                PocOracleSupport.ORACLE.getPassword());
             PreparedStatement statement =
                     connection.prepareStatement("SELECT * FROM CM_SLA_TARGET WHERE ID_ = ?")) {
            statement.setString(1, targetId);
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                        row.put(rs.getMetaData().getColumnName(i), rs.getString(i));
                    }
                }
            }
        }
        return row;
    }

    private void deleteSlaTarget(String targetId) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                PocOracleSupport.ORACLE.getJdbcUrl(),
                PocOracleSupport.ORACLE.getUsername(),
                PocOracleSupport.ORACLE.getPassword());
             PreparedStatement statement =
                     connection.prepareStatement("DELETE FROM CM_SLA_TARGET WHERE ID_ = ?")) {
            statement.setString(1, targetId);
            statement.executeUpdate();
        }
    }

    private boolean rowExists(Connection connection, String targetId) throws Exception {
        try (PreparedStatement statement =
                     connection.prepareStatement("SELECT 1 FROM CM_SLA_TARGET WHERE ID_ = ?")) {
            statement.setString(1, targetId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        }
    }

    private ConfigurableApplicationContext bootPocApplication() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("server.port", "0");
        properties.put("spring.datasource.url", PocOracleSupport.ORACLE.getJdbcUrl());
        properties.put("spring.datasource.username", PocOracleSupport.ORACLE.getUsername());
        properties.put("spring.datasource.password", PocOracleSupport.ORACLE.getPassword());

        String[] args = properties.entrySet().stream()
                .map(e -> "--" + e.getKey() + "=" + e.getValue())
                .toArray(String[]::new);
        return new SpringApplicationBuilder(PocApplication.class).run(args);
    }
}
