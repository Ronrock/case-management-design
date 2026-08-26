package org.casemgmt.service;

import org.casemgmt.domain.*;
import org.casemgmt.error.CaseConflictException;
import org.casemgmt.error.InvalidCaseDefinitionException;
import org.casemgmt.repo.CaseDefinitionRepository;
import org.casemgmt.repo.JsonCodec;
import org.casemgmt.orchestration.OrchestrationMode;
import org.springframework.dao.DuplicateKeyException;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public class CaseDefinitionService {

    private static final Set<String> RESERVED_CASE_ROLE_NAMES =
            Set.of("owner", "handler", "reviewer", "watcher");
    private static final Pattern DOT_ITEM_REFERENCE =
            Pattern.compile("\\bitems\\.([A-Za-z_][A-Za-z0-9_-]*)\\.");
    private static final Pattern BRACKET_ITEM_REFERENCE =
            Pattern.compile("\\bitems\\[['\"]([^'\"]+)['\"]\\]");

    private final CaseDefinitionRepository repo;

    public CaseDefinitionService(CaseDefinitionRepository repo) {
        this.repo = repo;
    }

    /**
     * Parses the definition JSON, assigns the next version for its key and stores it.
     *
     * <p><b>{@code tenantId} is a parameter, not a field of the document</b> (Task 24 fix round 2,
     * review finding Important 2). It used to be read straight from the submitted JSON, which made
     * the deploy endpoint the one place a caller could still choose the tenant they wrote into —
     * contradicting the invariant fix round 1 established everywhere else ("the tenant comes from
     * the principal and from nothing else") and letting any holder of the global {@code admin}
     * group publish a new version of another tenant's case definition. Every future case of that
     * key in that tenant then instantiates the attacker's plan model, because
     * {@code CaseService.create} resolves the definition through {@code findLatest(key, tenant)}.
     *
     * <p>A {@code tenantId} in the document is now ignored here. The REST layer validates it
     * against the caller's own tenant before calling this, so the two can never disagree
     * silently; an internal caller that constructs both is trivially consistent by construction.
     */
    @SuppressWarnings("unchecked")
    public CaseDefinition deploy(String json, String deployedBy, String tenantId) {
        return deploy(JsonCodec.toMap(json), deployedBy, tenantId, OrchestrationMode.PLAN_MODEL);
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
        List<Map<String, Object>> raw = (List<Map<String, Object>>) doc.getOrDefault("planItems", List.of());
        List<String> roles = strings(doc, "roles");

        for (int attempt = 0; attempt < 3; attempt++) {
            int version = repo.nextVersion(key, tenantId);
            String id = definitionId(tenantId, key, version);

            List<PlanItemDefinition> items = new ArrayList<>();
            for (Map<String, Object> p : raw) {
                items.add(new PlanItemDefinition(
                        CaseIds.newId(), id, required(p, "defKey"),
                        planItemType(key, p),
                        (String) p.getOrDefault("name", p.get("defKey")),
                        (String) p.get("parentStageKey"),
                        bool(p, "manualActivation"), bool(p, "required"), bool(p, "repetition"),
                        strings(p, "entryCriteria"), strings(p, "exitCriteria"),
                        (String) p.get("formKey"), (String) p.get("processDefinitionKey"),
                        strings(p, "candidateGroups"),
                        p.get("sortOrder") instanceof Number n ? n.intValue() : 0));
            }
            validatePlanItems(key, roles, items);

            CaseDefinition def = new CaseDefinition(id, key, version,
                    (String) doc.getOrDefault("name", key), tenantId,
                    (String) doc.get("description"), (String) doc.get("slaPolicyId"),
                    roles, strings(doc, "attachmentCategories"),
                    (Map<String, Object>) doc.getOrDefault("forms", Map.of()),
                    items, orchestrationMode, OffsetDateTime.now(), deployedBy);

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
     * Rejects a malformed definition before any database write is attempted, in two ways:
     *
     * <ol>
     *   <li>Duplicate {@code defKey} across plan items. Left unchecked, this reaches
     *   {@code CaseDefinitionRepository.insert} and fails midway through the plan-item
     *   INSERT loop on the {@code UQ_CM_PI_DEF UNIQUE (CASE_DEF_ID_, DEF_KEY_)} constraint —
     *   which {@code insert} now handles atomically (rolls back the whole definition, see its
     *   Javadoc), but a clear pre-write {@link InvalidCaseDefinitionException} naming the offending
     *   key is a far better signal to the caller than a raw
     *   {@code DataIntegrityViolationException} surfacing from deep inside the repository.
     *   <li>A {@code parentStageKey} that names no {@code defKey} in the same definition.
     *   Without this, a bad definition would deploy successfully and then blow up the first
     *   time anyone starts a case from it: {@link org.casemgmt.rules.PlanModelInstantiator}
     *   (Task 9) throws {@link IllegalArgumentException} from {@code initialItems} for exactly
     *   this condition, specifically because a case definition arriving over the API from
     *   another team is realistic malformed input. Deploy time is the right place to catch
     *   it — the caller who submitted the bad definition gets an immediate, specific error
     *   instead of a case-creation failure that names a plan model they may not even have
     *   written.
     * </ol>
     *
     * <p>Validation alone does not make {@code insert} safe against every failure mode — a
     * lost connection or a constraint this method doesn't know to check for can still fail
     * mid-write — which is why {@code insert} is also atomic in its own right. The two are
     * complementary, not alternatives: this method turns the single most likely trigger into
     * a cheap, clear, pre-write rejection; the repository's transaction is the backstop for
     * everything else.
     */
    private static void validatePlanItems(String key, List<String> roles, List<PlanItemDefinition> items) {
        Set<String> defKeys = new HashSet<>();
        Set<String> roleNames = new HashSet<>(RESERVED_CASE_ROLE_NAMES);
        roleNames.addAll(roles == null ? List.of() : roles);
        for (PlanItemDefinition p : items) {
            if (!defKeys.add(p.defKey())) {
                throw invalid(key, "Case definition '" + key
                        + "': duplicate plan item defKey '" + p.defKey()
                        + "' — defKeys must be unique within a definition");
            }
            for (String group : p.candidateGroups()) {
                if (roleNames.contains(group)) {
                    throw invalid(key, "Case definition '" + key
                            + "': plan item '" + p.defKey() + "' uses candidateGroup '" + group
                            + "', which conflicts with a case participant role name; use a distinct "
                            + "identity-group namespace for task assignment");
                }
            }
        }
        for (PlanItemDefinition p : items) {
            if (p.parentStageKey() != null && !defKeys.contains(p.parentStageKey())) {
                throw invalid(key, "Case definition '" + key + "': plan item '"
                        + p.defKey() + "' declares parentStageKey '" + p.parentStageKey()
                        + "', but no plan item with that defKey exists in this definition");
            }
            validateCriteriaReferences(key, p.defKey(), "entryCriteria", p.entryCriteria(), defKeys);
            validateCriteriaReferences(key, p.defKey(), "exitCriteria", p.exitCriteria(), defKeys);
        }
    }

    private static void validateCriteriaReferences(String key, String itemKey, String field,
                                                   List<String> expressions, Set<String> defKeys) {
        for (String expression : expressions) {
            for (String referenced : referencedItemKeys(expression)) {
                if (!defKeys.contains(referenced)) {
                    throw invalid(key, "Case definition '" + key + "': plan item '"
                            + itemKey + "' " + field + " references unknown plan item defKey '"
                            + referenced + "'");
                }
            }
        }
    }

    private static Set<String> referencedItemKeys(String expression) {
        Set<String> refs = new HashSet<>();
        var dot = DOT_ITEM_REFERENCE.matcher(expression);
        while (dot.find()) {
            refs.add(dot.group(1));
        }
        var bracket = BRACKET_ITEM_REFERENCE.matcher(expression);
        while (bracket.find()) {
            refs.add(bracket.group(1));
        }
        return refs;
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

    private static PlanItemType planItemType(String key, Map<String, Object> item) {
        String raw = required(item, "type");
        try {
            return PlanItemType.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw invalid(key, "Case definition '" + key + "': plan item '"
                    + item.getOrDefault("defKey", "<unknown>") + "' has unsupported type '" + raw + "'");
        }
    }

    private static InvalidCaseDefinitionException invalid(String key, String message) {
        return new InvalidCaseDefinitionException(key, message);
    }

    private static boolean bool(Map<String, Object> m, String field) {
        return m.get(field) instanceof Boolean b && b;
    }

    private static List<String> strings(Map<String, Object> m, String field) {
        Object v = m.get(field);
        return v instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of();
    }
}
