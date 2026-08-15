package org.casemgmt.rest.controller;

import org.casemgmt.domain.CaseDefinition;
import org.casemgmt.error.NotFoundException;
import org.casemgmt.repo.CaseDefinitionRepository;
import org.casemgmt.repo.JsonCodec;
import org.casemgmt.rest.CallerResolver;
import org.casemgmt.rest.policy.ActionPolicy;
import org.casemgmt.rest.policy.AvailableAction;
import org.casemgmt.service.Actor;
import org.casemgmt.service.CaseDefinitionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deployment and discovery of case definitions.
 *
 * <p>Response bodies are built with {@link LinkedHashMap}, not {@code Map.of}: several of the
 * fields exposed here are genuinely nullable ({@code tenantId}, {@code name} on a hand-written
 * definition), and {@code Map.of} throws {@link NullPointerException} on a null value — turning
 * an optional field into a 500. A null field serialises as JSON {@code null}, which is the
 * honest representation and what a generic consumer can actually handle.
 */
@RestController
@RequestMapping("/case-api/v2/case-definitions")
public class CaseDefinitionController {

    private final CaseDefinitionService service;
    private final CaseDefinitionRepository repo;
    private final ActionPolicy policy;
    private final CallerResolver callers;

    public CaseDefinitionController(CaseDefinitionService service, CaseDefinitionRepository repo,
                                    ActionPolicy policy, CallerResolver callers) {
        this.service = service;
        this.repo = repo;
        this.policy = policy;
        this.callers = callers;
    }

    /**
     * Deploying rewrites how every future case of a type behaves, so it is gated on
     * {@code ActionPolicy}'s administration rule (fix round 1, Critical 1 — this endpoint was
     * previously reachable by any authenticated caller, who could therefore publish a new version
     * of ANY case definition in the deployment). It is not case-scoped, so there is no
     * participant row to consult: the gate is an identity group, which is the only vocabulary
     * that exists above a single case.
     *
     * <p><b>The tenant comes from the principal</b> (fix round 2, review finding Important 2).
     * This was the one endpoint on fix round 1's list where that ruling was not applied:
     * {@code CaseDefinitionService} read {@code tenantId} out of the submitted document, so any
     * holder of the global {@code admin} group — including a tenant t2 administrator — could
     * publish a new version of another tenant's case definition, which every future case of that
     * key in that tenant then instantiates. A {@code tenantId} in the document is now validated
     * against the caller's own rather than trusted (403 if it names another), and a document that
     * omits it deploys under the caller's tenant instead of silently landing untenanted where no
     * tenant-scoped listing would ever find it.
     *
     * <p>Takes the raw request body as a {@code String} — {@code CaseDefinitionService.deploy}
     * parses it itself with core's Jackson 2 {@code JsonCodec}, and handing it a string keeps
     * the two Jackson generations on the classpath from meeting: nothing here binds the
     * definition document through Spring's Jackson 3 converters and back. Reading the document's
     * {@code tenantId} here parses it a second time, with the same Jackson 2 codec; that is a
     * deliberate trade for keeping the service's contract self-contained (it takes the tenant it
     * must use, and consults the document for nothing else).
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> deploy(@RequestBody String definitionJson,
                                                      Authentication authentication) {
        Actor actor = callers.actor(authentication);
        policy.assertMayAdminister(callers.groups(actor), "deploy-case-definition");
        String tenantId = callers.requireTenant(actor,
                (String) JsonCodec.toMap(definitionJson).get("tenantId"));

        CaseDefinition deployed = service.deploy(definitionJson, actor.userId(), tenantId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", deployed.id());
        body.put("key", deployed.key());
        body.put("version", deployed.versionNo());
        body.put("tenantId", deployed.tenantId());
        body.put("planItems", deployed.planItems().size());
        body.put("availableActions", policy.listForAdministration(callers.groups(actor)));
        return ResponseEntity.status(HttpStatus.CREATED)
                .location(URI.create("/case-api/v2/case-definitions/" + deployed.key()))
                .body(body);
    }

    /**
     * The listing. A consumer with no prior knowledge starts here: it discovers which case types
     * exist rather than being told (spec §2.1, and what makes a generic consumer possible
     * without case-type constants anywhere in this module).
     */
    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(required = false) String tenantId,
                                          Authentication authentication) {
        Actor actor = callers.actor(authentication);
        String tenant = callers.requireTenant(actor, tenantId);
        List<AvailableAction> actions = policy.listForAdministration(callers.groups(actor));
        return repo.listLatest(tenant).stream().map(def -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", def.id());
            row.put("key", def.key());
            row.put("version", def.versionNo());
            row.put("name", def.name());
            row.put("tenantId", def.tenantId());
            row.put("availableActions", actions);
            return row;
        }).toList();
    }

    @GetMapping("/{key}")
    public Map<String, Object> get(@PathVariable String key,
                                   @RequestParam(required = false) String tenantId,
                                   Authentication authentication) {
        Actor actor = callers.actor(authentication);
        String tenant = callers.requireTenant(actor, tenantId);
        CaseDefinition def = repo.findLatest(key, tenant)
                .orElseThrow(() -> new NotFoundException("CaseDefinition", key));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", def.id());
        body.put("key", def.key());
        body.put("version", def.versionNo());
        body.put("name", def.name());
        body.put("tenantId", def.tenantId());
        body.put("roles", def.roles());
        body.put("formKeys", List.copyOf(def.forms().keySet()));
        body.put("availableActions", policy.listForAdministration(callers.groups(actor)));
        body.put("planItems", def.planItems().stream().map(p -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("defKey", p.defKey());
            item.put("type", p.type().name());
            item.put("name", p.name());
            item.put("parentStageKey", p.parentStageKey());
            item.put("required", p.required());
            item.put("manualActivation", p.manualActivation());
            item.put("repetition", p.repetition());
            item.put("formKey", p.formKey());
            item.put("candidateGroups", p.candidateGroups());
            return item;
        }).toList());
        return body;
    }

    @GetMapping(value = "/{key}/forms/{formKey}", produces = "application/schema+json")
    public Map<String, Object> form(@PathVariable String key, @PathVariable String formKey,
                                    @RequestParam(required = false) String tenantId,
                                    Authentication authentication) {
        String tenant = callers.requireTenant(callers.actor(authentication), tenantId);
        return repo.formSchema(key, formKey, tenant)
                .orElseThrow(() -> new NotFoundException("Form", key + "/" + formKey));
    }
}
