package org.casemgmt.rest.controller;

import org.casemgmt.domain.CaseDefinition;
import org.casemgmt.error.NotFoundException;
import org.casemgmt.repo.CaseDefinitionRepository;
import org.casemgmt.repo.CaseDefinitionVersionBindingRepository;
import org.casemgmt.rest.CallerResolver;
import org.casemgmt.rest.dto.BindingResponseFields;
import org.casemgmt.rest.policy.ActionPolicy;
import org.casemgmt.rest.policy.AvailableAction;
import org.casemgmt.service.Actor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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

    private final CaseDefinitionRepository repo;
    private final CaseDefinitionVersionBindingRepository bindings;
    private final ActionPolicy policy;
    private final CallerResolver callers;

    public CaseDefinitionController(CaseDefinitionRepository repo,
                                    CaseDefinitionVersionBindingRepository bindings,
                                    ActionPolicy policy, CallerResolver callers) {
        this.repo = repo;
        this.bindings = bindings;
        this.policy = policy;
        this.callers = callers;
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
            row.put("orchestrationMode", def.orchestrationMode().name());
            bindings.find(def.id()).ifPresent(binding ->
                    BindingResponseFields.put(row, binding, false));
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
        body.put("orchestrationMode", def.orchestrationMode().name());
        bindings.find(def.id()).ifPresent(binding ->
                BindingResponseFields.put(body, binding, false));
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

    @GetMapping("/{key}/versions/{version}")
    public Map<String, Object> getVersion(@PathVariable String key, @PathVariable int version,
                                          @RequestParam(required = false) String tenantId,
                                          Authentication authentication) {
        Actor actor = callers.actor(authentication);
        String tenant = callers.requireTenant(actor, tenantId);
        CaseDefinition def = repo.findVersion(key, version, tenant)
                .orElseThrow(() -> new NotFoundException("CaseDefinition", key + ":" + version));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", def.id());
        body.put("key", def.key());
        body.put("version", def.versionNo());
        body.put("orchestrationMode", def.orchestrationMode().name());
        bindings.find(def.id()).ifPresent(binding ->
                BindingResponseFields.put(body, binding, false));
        body.put("name", def.name());
        body.put("tenantId", def.tenantId());
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
