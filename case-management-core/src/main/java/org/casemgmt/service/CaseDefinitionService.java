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

    /** Parses the definition JSON, assigns the next version for its key and stores it. */
    @SuppressWarnings("unchecked")
    public CaseDefinition deploy(String json, String deployedBy) {
        Map<String, Object> doc = JsonCodec.toMap(json);
        String key = required(doc, "key");
        String tenantId = (String) doc.get("tenantId");
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
        validateParentStageKeys(key, items);

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
     * Rejects a definition whose plan items reference a {@code parentStageKey} that names no
     * {@code defKey} in the same definition, at deploy time rather than letting it fail later.
     *
     * <p>Without this, a bad definition would deploy successfully and then blow up the first
     * time anyone starts a case from it: {@link org.casemgmt.rules.PlanModelInstantiator}
     * (Task 9) throws {@link IllegalArgumentException} from {@code initialItems} for exactly
     * this condition, specifically because a case definition arriving over the API from
     * another team is realistic malformed input. Deploy time is the right place to catch it —
     * the caller who submitted the bad definition gets an immediate, specific error instead of
     * a case-creation failure that names a plan model they may not even have written.
     */
    private static void validateParentStageKeys(String key, List<PlanItemDefinition> items) {
        Set<String> defKeys = new HashSet<>();
        for (PlanItemDefinition p : items) {
            defKeys.add(p.defKey());
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
