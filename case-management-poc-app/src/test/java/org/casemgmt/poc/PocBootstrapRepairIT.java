package org.casemgmt.poc;

import org.casemgmt.poc.support.PocOracleSupport;
import org.casemgmt.repo.SlaRepository;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.IdentityService;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.LinkedHashMap;
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
 */
class PocBootstrapRepairIT {

    @Test
    void seedingRepairsAPartiallySeededDatabaseOnTheNextStartup() {
        ConfigurableApplicationContext first = bootPocApplication();
        try {
            IdentityService identity = first.getBean(IdentityService.class);
            SlaRepository sla = first.getBean(SlaRepository.class);

            // Confirm the damage actually lands on what a fresh boot seeded, not on nothing.
            assertThat(identity.createGroupQuery().groupMember("alice").list())
                    .extracting(g -> g.getId()).contains("tenant:t1");
            assertThat(sla.targetsFor("sla-complaint")).extracting(SlaRepository.TargetRow::id)
                    .contains("sla-resolution");

            identity.deleteMembership("alice", "tenant:t1");
            deleteSlaTarget(first, "sla-resolution");

            assertThat(identity.createGroupQuery().groupMember("alice").list())
                    .extracting(g -> g.getId()).doesNotContain("tenant:t1");
            assertThat(sla.targetsFor("sla-complaint")).extracting(SlaRepository.TargetRow::id)
                    .doesNotContain("sla-resolution");
        } finally {
            first.close();
        }

        ConfigurableApplicationContext second = bootPocApplication();
        try {
            IdentityService identity = second.getBean(IdentityService.class);
            SlaRepository sla = second.getBean(SlaRepository.class);

            assertThat(identity.createGroupQuery().groupMember("alice").list())
                    .as("alice's tenant:t1 membership is repaired by the next startup, not lost forever")
                    .extracting(g -> g.getId()).contains("tenant:t1");
            assertThat(sla.targetsFor("sla-complaint"))
                    .as("the missing SLA target is repaired by the next startup")
                    .extracting(SlaRepository.TargetRow::id).contains("sla-resolution");

            // And the untouched rows were left alone rather than duplicated/reset.
            assertThat(identity.createUserQuery().userId("alice").count()).isEqualTo(1);
            assertThat(sla.targetsFor("sla-complaint")).hasSize(2);
        } finally {
            second.close();
        }
    }

    private static void deleteSlaTarget(ConfigurableApplicationContext ctx, String targetId) {
        var jdbcClient = ctx.getBean(org.springframework.jdbc.core.simple.JdbcClient.class);
        jdbcClient.sql("DELETE FROM CM_SLA_TARGET WHERE ID_ = :id").param("id", targetId).update();
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
