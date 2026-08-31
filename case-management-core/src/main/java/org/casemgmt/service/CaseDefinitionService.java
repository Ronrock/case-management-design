package org.casemgmt.service;

import org.casemgmt.domain.*;
import org.casemgmt.error.CaseConflictException;
import org.casemgmt.error.InvalidCaseDefinitionException;
import org.casemgmt.repo.CaseDefinitionRepository;
import org.casemgmt.repo.JsonCodec;
import org.casemgmt.orchestration.OrchestrationMode;
import org.springframework.dao.DuplicateKeyException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

public class CaseDefinitionService {

    private final CaseDefinitionRepository repo;

    public CaseDefinitionService(CaseDefinitionRepository repo) {
        this.repo = repo;
    }

    public CaseDefinition deployBpmn(String key, String contractJson, String deployedBy,
                                     String tenantId) {
        Map<String, Object> contract = new LinkedHashMap<>(JsonCodec.toMap(contractJson));
        Object declaredKey = contract.get("key");
        if (declaredKey != null && !key.equals(declaredKey.toString())) {
            throw invalid(key, "Contract release key '" + declaredKey
                    + "' does not match definition key '" + key + "'");
        }
        contract.put("key", key);
        contract.put("planItems", List.of());
        // Contract v1 keeps uiSchema beside each JSON Schema. The legacy runtime definition
        // stores only schemas because task completion validates against this map; presentation
        // metadata remains available from the independently pinned contract release.
        if (contract.get("forms") instanceof Map<?, ?> forms) {
            Map<String, Object> schemas = new LinkedHashMap<>();
            forms.forEach((formId, value) -> {
                Object schema = value instanceof Map<?, ?> form && form.get("schema") != null
                        ? form.get("schema") : value;
                schemas.put(String.valueOf(formId), schema);
            });
            contract.put("forms", schemas);
        }
        return deploy(contract, deployedBy, tenantId, OrchestrationMode.BPMN);
    }

    @SuppressWarnings("unchecked")
    private CaseDefinition deploy(Map<String, Object> doc, String deployedBy, String tenantId,
                                  OrchestrationMode orchestrationMode) {
        String key = required(doc, "key");
        List<String> roles = strings(doc, "roles");

        for (int attempt = 0; attempt < 3; attempt++) {
            int version = repo.nextVersion(key, tenantId);
            String id = definitionId(tenantId, key, version);

            CaseDefinition def = new CaseDefinition(id, key, version,
                    (String) doc.getOrDefault("name", key), tenantId,
                    (String) doc.get("description"), (String) doc.get("slaPolicyId"),
                    roles, strings(doc, "attachmentCategories"),
                    (Map<String, Object>) doc.getOrDefault("forms", Map.of()),
                    List.of(), orchestrationMode, OffsetDateTime.now(), deployedBy);

            try {
                repo.insert(def);
                return def;
            } catch (DuplicateKeyException e) {
                // Another deployment won the nextVersion -> insert race. Recompute and retry.
            }
        }

        throw new CaseConflictException("case-definition-version-conflict",
                "Could not allocate a new version for case definition '" + key
                        + "' after concurrent deploy retries; retry the request", List.of());
    }

    /**
     * The {@code CM_CASE_DEF.ID_} primary key: {@code {tenant}:{key}:{version}}.
     *
     * <p><b>Tenant-qualified since Task 24 fix round 3.</b> It used to be {@code {key}:{version}},
     * which contradicted the schema's own {@code UQ_CM_CASE_DEF UNIQUE (KEY_, VERSION_NO_,
     * TENANT_ID_)}: that constraint says two tenants may each hold version 1 of one key, but
     * {@link CaseDefinitionRepository#nextVersion} counts within a tenant, so both tenants'
     * first deploy of {@code widget-review} minted the same id and the second collided on the
     * primary key (reproduced against real Oracle as {@code ORA-00001 ... PK_CM_CASE_DEF ...
     * row with column values (ID_:'widget-review:1') already exists}). The effect was that a
     * multi-tenant deployment could host any given case-definition key in exactly one tenant.
     *
     * <p>Per-tenant version numbering is kept, deliberately, over the alternative of making the
     * id unique by counting versions globally per key: that would make a tenant's first-ever
     * deploy land at, say, v7 because another tenant had deployed six times — confusing for
     * operators, and it leaks one tenant's activity level to another.
     *
     * <p>A null tenant (an untenanted definition — {@code TENANT_ID_} is nullable, and
     * {@code findLatest(key, null)} exists to read one back) yields an empty first segment,
     * e.g. {@code :widget-review:1}. That cannot collide with a real tenant's id because real
     * tenant ids are non-empty and therefore always produce a different prefix such as
     * {@code t1:widget-review:1}. Two null-tenant deploys of the same key are safe for the same
     * reason as tenant-scoped deploys: {@link CaseDefinitionRepository#nextVersion} uses a
     * null-aware tenant predicate, so the second deploy receives version 2 rather than reusing
     * {@code :widget-review:1}.
     *
     * <p>The id is opaque to every consumer — nothing in this codebase parses or reconstructs it,
     * verified by grep before the change — so widening the format needed no migration. Note
     * that {@code ID_} is {@code VARCHAR2(64)} while {@code KEY_} is {@code VARCHAR2(255)}: a
     * long key could already overflow the id column before this change, and the tenant prefix
     * consumes a little more of that budget. Not newly broken, and not widened here, but worth
     * knowing.
     */
    private static String definitionId(String tenantId, String key, int version) {
        return (tenantId == null ? "" : tenantId) + ":" + key + ":" + version;
    }

    private static String required(Map<String, Object> m, String field) {
        Object v = m.get(field);
        if (v == null) throw invalid(String.valueOf(m.getOrDefault("key", "<unknown>")),
                "Definition is missing required field: " + field);
        return v.toString();
    }

    private static InvalidCaseDefinitionException invalid(String key, String message) {
        return new InvalidCaseDefinitionException(key, message);
    }

    private static List<String> strings(Map<String, Object> m, String field) {
        Object v = m.get(field);
        return v instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of();
    }
}
