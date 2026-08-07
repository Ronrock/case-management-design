package org.casemgmt.poc;

import org.casemgmt.repo.CaseDefinitionRepository;
import org.casemgmt.repo.SlaRepository;
import org.casemgmt.service.CaseDefinitionService;
import org.operaton.bpm.engine.IdentityService;
import org.operaton.bpm.engine.RepositoryService;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** Seeds everything the PoC needs to be demonstrable from a cold database. */
@Configuration
public class PocBootstrap {

    /**
     * The one tenant this PoC runs. Every seeded user is a member of {@code tenant:TENANT_ID}
     * (deviation D1 — see class-level note below the bean method): {@link
     * org.casemgmt.rest.CallerResolver#tenantId} requires exactly one {@code tenant:<id>}
     * identity group on the caller, and the brief's own seeding sketch created none, which
     * would make every single request from every seeded user fail with 403 "no tenant
     * assignment" before it ever reached a controller's own logic.
     */
    static final String TENANT_ID = "t1";

    @Bean
    public ApplicationRunner seed(IdentityService identity, RepositoryService repository,
                                  CaseDefinitionService definitions, CaseDefinitionRepository defRepo,
                                  SlaRepository sla) {
        return args -> {
            seedUsers(identity);
            seedProcesses(repository);
            seedSla(sla);
            seedDefinition(definitions, defRepo);
        };
    }

    private void seedUsers(IdentityService identity) {
        createGroup(identity, "intake");
        createGroup(identity, "handlers");
        createGroup(identity, "reviewers");
        // Deviation D1: the brief's own seeding created no tenant group at all. Added so every
        // seeded caller can pass CallerResolver.tenantId — see TENANT_ID's Javadoc.
        createGroup(identity, "tenant:" + TENANT_ID);
        createUser(identity, "alice", "alice", List.of("intake", "handlers", "tenant:" + TENANT_ID));
        createUser(identity, "bob", "bob", List.of("handlers", "reviewers", "tenant:" + TENANT_ID));
        createUser(identity, "carol", "carol", List.of("reviewers", "tenant:" + TENANT_ID));
    }

    private void createGroup(IdentityService identity, String id) {
        if (identity.createGroupQuery().groupId(id).count() == 0) {
            var group = identity.newGroup(id);
            group.setName(id);
            identity.saveGroup(group);
        }
    }

    private void createUser(IdentityService identity, String id, String password, List<String> groups) {
        if (identity.createUserQuery().userId(id).count() == 0) {
            var user = identity.newUser(id);
            user.setPassword(password);
            user.setFirstName(id);
            identity.saveUser(user);
            groups.forEach(g -> identity.createMembership(id, g));
        }
    }

    private void seedProcesses(RepositoryService repository) {
        repository.createDeployment()
                .addClasspathResource("processes/decision-letter.bpmn")
                .enableDuplicateFiltering(true)
                .name("poc-processes")
                .deploy();
    }

    private void seedSla(SlaRepository sla) {
        if (sla.calendarIdOf("sla-complaint") != null) {
            return;
        }
        Map<String, Object> workday = Map.of("from", "09:00", "to", "17:00");
        sla.insertCalendar("cal-nl", Map.of(
                "timezone", "Europe/Amsterdam",
                "workingHours", Map.of(
                        "MONDAY", List.of(workday), "TUESDAY", List.of(workday),
                        "WEDNESDAY", List.of(workday), "THURSDAY", List.of(workday),
                        "FRIDAY", List.of(workday)),
                "holidays", List.of("2026-12-25", "2026-12-26")));

        sla.insertPolicy("sla-complaint", "Complaint SLA", null, "cal-nl");
        sla.insertTarget("sla-first-response", "sla-complaint", "firstResponse",
                "First response", "PT4H", "PT3H",
                List.of("WAITING_ON_CUSTOMER"), List.of("EMIT_EVENT"));
        sla.insertTarget("sla-resolution", "sla-complaint", "resolution",
                "Resolution", "P5D", "P4D",
                List.of("WAITING_ON_CUSTOMER"), List.of("EMIT_EVENT", "ESCALATE"));
    }

    /**
     * Deviation D2: {@code CaseDefinitionService.deploy} takes a {@code tenantId} as its third
     * argument (Task 24 fix round 2 moved it out of the document body — see that class's
     * Javadoc), which the brief's own two-argument call does not compile against. The document's
     * own {@code "tenantId": "t1"} field is harmless but now ignored by the service; passed
     * {@link #TENANT_ID} explicitly instead so the two can never silently disagree.
     */
    private void seedDefinition(CaseDefinitionService definitions, CaseDefinitionRepository repo) throws Exception {
        if (repo.findLatest("complaint", TENANT_ID).isPresent()) {
            return;
        }
        String json = new String(new ClassPathResource("definitions/complaint-v1.json")
                .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        definitions.deploy(json, "system", TENANT_ID);
    }
}
