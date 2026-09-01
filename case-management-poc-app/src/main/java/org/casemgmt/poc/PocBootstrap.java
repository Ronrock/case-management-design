package org.casemgmt.poc;

import org.casemgmt.repo.CaseDefinitionRepository;
import org.casemgmt.repo.SlaRepository;
import org.casemgmt.service.CaseDefinitionReleaseService;
import org.casemgmt.service.CaseDefinitionVersionService;
import org.casemgmt.release.ReleaseKind;
import org.operaton.bpm.engine.IdentityService;
import org.operaton.bpm.engine.RepositoryService;
import org.operaton.bpm.engine.identity.Group;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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
                                  CaseDefinitionRepository defRepo,
                                  CaseDefinitionReleaseService releases,
                                  CaseDefinitionVersionService versions, SlaRepository sla) {
        return args -> {
            seedUsers(identity);
            seedProcesses(repository);
            seedSla(sla);
            seedBpmnDefinition(defRepo, releases, versions);
        };
    }

    private void seedUsers(IdentityService identity) {
        createGroup(identity, "intake");
        createGroup(identity, "handlers");
        createGroup(identity, "reviewers");
        // Deviation D1: the brief's own seeding created no tenant group at all. Added so every
        // seeded caller can pass CallerResolver.tenantId — see TENANT_ID's Javadoc.
        createGroup(identity, "tenant:" + TENANT_ID);
        // Task 27 corrective round: the identity group ActionPolicy.ADMIN_GROUPS gates the three
        // deployment-wide endpoints on — POST /case-definitions, POST /webhooks and
        // GET /webhooks/{id}/dead-letters. Until this was added, NO seeded caller held it, so a
        // third of the API's administration surface was unreachable in the one application whose
        // job is to be the runnable demonstration of that API, and OpenApiConformanceIT could not
        // validate those responses against openapi-specs.md at all. Purely additive: no existing
        // user gains or loses anything, and every authorization expectation those users already
        // carry is unchanged.
        createGroup(identity, "admin");
        createUser(identity, "alice", "alice", List.of("intake", "handlers", "tenant:" + TENANT_ID));
        createUser(identity, "bob", "bob", List.of("handlers", "reviewers", "tenant:" + TENANT_ID));
        createUser(identity, "carol", "carol", List.of("reviewers", "tenant:" + TENANT_ID));
        // Deliberately NOT named "admin": application.yaml seeds Operaton's own admin-user with
        // exactly that id, and reusing it would collide with the engine's own account.
        createUser(identity, "olivia", "olivia", List.of("admin", "tenant:" + TENANT_ID));
    }

    private void createGroup(IdentityService identity, String id) {
        if (identity.createGroupQuery().groupId(id).count() == 0) {
            var group = identity.newGroup(id);
            group.setName(id);
            identity.saveGroup(group);
        }
    }

    /**
     * Fix round 1, Minor (review): seeding used to be repair-blind. {@code createMembership}
     * calls only ever ran inside the "user doesn't exist yet" branch, so a user seeded by an
     * EARLIER build — one that predates a group this build wants it in, e.g. {@code tenant:t1}
     * (added by D1, above) — would never get that membership: {@code alice} from an older
     * database would 403 forever with "no tenant assignment", and nothing short of dropping the
     * database would fix it. Membership repair now runs unconditionally, for both a brand-new
     * user and a pre-existing one, and only adds whichever of {@code groups} the user is not
     * already in — {@code createMembership} is not itself idempotent (a duplicate call violates
     * {@code ACT_ID_MEMBERSHIP}'s composite primary key), so the existing set has to be read
     * first rather than re-adding everything unconditionally.
     */
    private void createUser(IdentityService identity, String id, String password, List<String> groups) {
        if (identity.createUserQuery().userId(id).count() == 0) {
            var user = identity.newUser(id);
            user.setPassword(password);
            user.setFirstName(id);
            identity.saveUser(user);
        }
        Set<String> currentGroups = identity.createGroupQuery().groupMember(id).list().stream()
                .map(Group::getId).collect(Collectors.toSet());
        groups.stream().filter(g -> !currentGroups.contains(g))
                .forEach(g -> identity.createMembership(id, g));
    }

    private void seedProcesses(RepositoryService repository) {
        repository.createDeployment()
                .addClasspathResource("processes/decision-letter.bpmn")
                .enableDuplicateFiltering(true)
                .name("poc-processes")
                .deploy();
    }

    /**
     * Fix round 1, Minor (review): this method used to guard its ENTIRE body on one check
     * ({@code calendarIdOf("sla-complaint") != null}, i.e. "does the policy row already exist").
     * A crash between {@code insertCalendar} and {@code insertPolicy} — plausible on any real
     * restart, not a contrived scenario — left {@code nl-business} inserted but {@code sla-complaint}
     * not, and on the NEXT startup that single guard read as "not seeded yet", so it tried {@code
     * insertCalendar("nl-business", ...)} again and hit {@code CM_BUSINESS_CALENDAR}'s primary key.
     * Each of the four rows is now guarded independently, so seeding can resume from wherever a
     * previous run actually stopped rather than only ever from "nothing" or "everything".
     */
    private void seedSla(SlaRepository sla) {
        Map<String, Object> workday = Map.of("from", "09:00", "to", "17:00");
        Map<String, Object> calendarDefinition = Map.of(
                "timezone", "Europe/Amsterdam",
                "workingHours", Map.of(
                        "MONDAY", List.of(workday), "TUESDAY", List.of(workday),
                        "WEDNESDAY", List.of(workday), "THURSDAY", List.of(workday),
                        "FRIDAY", List.of(workday)),
                "holidays", List.of("2026-12-25", "2026-12-26"));
        if (sla.calendarDefinition("nl-business").isEmpty()) {
            sla.insertCalendar("nl-business", calendarDefinition);
        }
        sla.insertCalendarRevision(TENANT_ID, "nl-business", 1,
                "NL business", calendarDefinition);

        if (sla.calendarIdOf("sla-complaint") == null) {
            sla.insertPolicy("sla-complaint", "Complaint SLA", null, "nl-business");
        }

        Set<String> existingTargets = sla.targetsFor("sla-complaint").stream()
                .map(SlaRepository.TargetRow::id).collect(Collectors.toSet());
        if (!existingTargets.contains("sla-first-response")) {
            sla.insertTarget("sla-first-response", "sla-complaint", "firstResponse",
                    "First response", "PT4H", "PT3H",
                    List.of("WAITING_ON_CUSTOMER"), List.of("EMIT_EVENT"));
        }
        if (!existingTargets.contains("sla-resolution")) {
            sla.insertTarget("sla-resolution", "sla-complaint", "resolution",
                    "Resolution", "P5D", "P4D",
                    List.of("WAITING_ON_CUSTOMER"), List.of("EMIT_EVENT", "ESCALATE"));
        }
    }

    /** Seeds the PoC's sole runnable complaint type through the BPMN release-binding path. */
    private void seedBpmnDefinition(CaseDefinitionRepository definitions,
                                    CaseDefinitionReleaseService releases,
                                    CaseDefinitionVersionService versions) throws Exception {
        String key = "complaint";
        if (definitions.findLatest(key, TENANT_ID).isPresent()) {
            return;
        }
        byte[] bpmn = resource("processes/complaint-bpmn.bpmn");
        byte[] contract = resource("definitions/complaint-bpmn-contract.json");
        byte[] presentation = resource("definitions/complaint-bpmn-presentation.json");
        var orchestrationRelease = releases.publish(key, TENANT_ID, ReleaseKind.ORCHESTRATION,
                "application/bpmn+xml", bpmn, "system");
        var contractRelease = releases.publish(key, TENANT_ID, ReleaseKind.CONTRACT,
                "application/json", contract, "system");
        var presentationRelease = releases.publish(key, TENANT_ID, ReleaseKind.PRESENTATION,
                "application/json", presentation, "system");
        versions.bind(key, TENANT_ID, orchestrationRelease.id(), contractRelease.id(),
                presentationRelease.id(), "system");
    }

    private static byte[] resource(String path) throws Exception {
        return new ClassPathResource(path).getInputStream().readAllBytes();
    }
}
