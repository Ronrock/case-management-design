package org.casemgmt.service;

import org.casemgmt.domain.*;
import org.casemgmt.repo.CaseDefinitionRepository;
import org.casemgmt.repo.JsonCodec;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class CaseDefinitionService {

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
        Map<String, Object> doc = JsonCodec.toMap(json);
        String key = required(doc, "key");
        int version = repo.nextVersion(key, tenantId);
        String id = key + ":" + version;

        List<PlanItemDefinition> items = new ArrayList<>();
        List<Map<String, Object>> raw = (List<Map<String, Object>>) doc.getOrDefault("planItems", List.of());
        for (Map<String, Object> p : raw) {
            items.add(new PlanItemDefinition(
                    CaseIds.newId(), id, required(p, "defKey"),
                    PlanItemType.valueOf(required(p, "type")),
                    (String) p.getOrDefault("name", p.get("defKey")),
                    (String) p.get("parentStageKey"),
                    bool(p, "manualActivation"), bool(p, "required"), bool(p, "repetition"),
                    strings(p, "entryCriteria"), strings(p, "exitCriteria"),
                    (String) p.get("formKey"), (String) p.get("processDefinitionKey"),
                    strings(p, "candidateGroups"),
                    p.get("sortOrder") instanceof Number n ? n.intValue() : 0));
        }
        validatePlanItems(key, items);

        CaseDefinition def = new CaseDefinition(id, key, version,
                (String) doc.getOrDefault("name", key), tenantId,
                (String) doc.get("description"), (String) doc.get("slaPolicyId"),
                strings(doc, "roles"), strings(doc, "attachmentCategories"),
                (Map<String, Object>) doc.getOrDefault("forms", Map.of()),
                items, OffsetDateTime.now(), deployedBy);

        repo.insert(def);
        return def;
    }

    /**
     * Rejects a malformed definition before any database write is attempted, in two ways:
     *
     * <ol>
     *   <li>Duplicate {@code defKey} across plan items. Left unchecked, this reaches
     *   {@code CaseDefinitionRepository.insert} and fails midway through the plan-item
     *   INSERT loop on the {@code UQ_CM_PI_DEF UNIQUE (CASE_DEF_ID_, DEF_KEY_)} constraint —
     *   which {@code insert} now handles atomically (rolls back the whole definition, see its
     *   Javadoc), but a clear pre-write {@link IllegalArgumentException} naming the offending
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
    private static void validatePlanItems(String key, List<PlanItemDefinition> items) {
        Set<String> defKeys = new HashSet<>();
        for (PlanItemDefinition p : items) {
            if (!defKeys.add(p.defKey())) {
                throw new IllegalArgumentException("Case definition '" + key
                        + "': duplicate plan item defKey '" + p.defKey()
                        + "' — defKeys must be unique within a definition");
            }
        }
        for (PlanItemDefinition p : items) {
            if (p.parentStageKey() != null && !defKeys.contains(p.parentStageKey())) {
                throw new IllegalArgumentException("Case definition '" + key + "': plan item '"
                        + p.defKey() + "' declares parentStageKey '" + p.parentStageKey()
                        + "', but no plan item with that defKey exists in this definition");
            }
        }
    }

    private static String required(Map<String, Object> m, String field) {
        Object v = m.get(field);
        if (v == null) throw new IllegalArgumentException("Definition is missing required field: " + field);
        return v.toString();
    }

    private static boolean bool(Map<String, Object> m, String field) {
        return m.get(field) instanceof Boolean b && b;
    }

    private static List<String> strings(Map<String, Object> m, String field) {
        Object v = m.get(field);
        return v instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of();
    }
}
