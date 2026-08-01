package org.casemgmt.repo;

import org.casemgmt.domain.*;
import org.casemgmt.error.NotFoundException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class CaseDefinitionRepository {

    private final JdbcClient jdbc;

    public CaseDefinitionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Next version number for a (key, tenant) pair. MAX() over zero matching rows returns a
     * single row whose value is SQL NULL rather than zero rows, so {@code query(Integer.class)}
     * yields a one-element list containing {@code null}; JdbcClient's {@code optional()} folds
     * that null element into {@code Optional.empty()} (not {@code Optional.of(null)}), which is
     * why {@code orElse(0)} below is sufficient and the following null-check never actually
     * triggers — kept anyway as a defensive, self-documenting guard against a JdbcClient
     * behaviour change.
     */
    public int nextVersion(String key, String tenantId) {
        Integer max = jdbc.sql("""
                SELECT MAX(VERSION_NO_) FROM CM_CASE_DEF
                WHERE KEY_ = :key AND (TENANT_ID_ = :tenant OR (:tenant IS NULL AND TENANT_ID_ IS NULL))""")
                .param("key", key).param("tenant", tenantId)
                .query(Integer.class).optional().orElse(0);
        return max == null ? 1 : max + 1;
    }

    public void insert(CaseDefinition d) {
        jdbc.sql("""
                INSERT INTO CM_CASE_DEF (ID_, KEY_, VERSION_NO_, NAME_, TENANT_ID_, DESCRIPTION_,
                    SLA_POLICY_ID_, ROLES_JSON_, ATTACH_CATS_JSON_, FORMS_JSON_, DEPLOYED_AT_, DEPLOYED_BY_)
                VALUES (:id, :key, :ver, :name, :tenant, :desc, :sla, :roles, :cats, :forms,
                    :deployedAt, :deployedBy)""")
            .param("id", d.id()).param("key", d.key()).param("ver", d.versionNo())
            .param("name", d.name()).param("tenant", d.tenantId()).param("desc", d.description())
            .param("sla", d.slaPolicyId())
            .param("roles", JsonCodec.toJson(d.roles()))
            .param("cats", JsonCodec.toJson(d.attachmentCategories()))
            .param("forms", JsonCodec.toJson(d.forms()))
            .param("deployedAt", d.deployedAt()).param("deployedBy", d.deployedBy())
            .update();

        for (PlanItemDefinition p : d.planItems()) {
            jdbc.sql("""
                    INSERT INTO CM_PLAN_ITEM_DEF (ID_, CASE_DEF_ID_, DEF_KEY_, TYPE_, NAME_,
                        PARENT_STAGE_KEY_, MANUAL_ACT_, REQUIRED_, REPETITION_,
                        ENTRY_CRIT_JSON_, EXIT_CRIT_JSON_, FORM_KEY_, PROC_DEF_KEY_,
                        CAND_GROUPS_JSON_, SORT_ORDER_)
                    VALUES (:id, :defId, :key, :type, :name, :parent, :manual, :required, :repetition,
                        :entry, :exit, :formKey, :procKey, :groups, :sort)""")
                .param("id", p.id()).param("defId", d.id()).param("key", p.defKey())
                .param("type", p.type().name()).param("name", p.name())
                .param("parent", p.parentStageKey())
                .param("manual", p.manualActivation() ? 1 : 0)
                .param("required", p.required() ? 1 : 0)
                .param("repetition", p.repetition() ? 1 : 0)
                .param("entry", JsonCodec.toJson(p.entryCriteria()))
                .param("exit", JsonCodec.toJson(p.exitCriteria()))
                .param("formKey", p.formKey()).param("procKey", p.processDefinitionKey())
                .param("groups", JsonCodec.toJson(p.candidateGroups()))
                .param("sort", p.sortOrder())
                .update();
        }
    }

    public Optional<CaseDefinition> findById(String id) {
        return jdbc.sql("""
                SELECT ID_, KEY_, VERSION_NO_, NAME_, TENANT_ID_, DESCRIPTION_, SLA_POLICY_ID_,
                       ROLES_JSON_, ATTACH_CATS_JSON_, FORMS_JSON_, DEPLOYED_AT_, DEPLOYED_BY_
                FROM CM_CASE_DEF WHERE ID_ = :id""")
                .param("id", id)
                .query((rs, n) -> mapDefinition(rs, planItems(id)))
                .optional();
    }

    public CaseDefinition require(String id) {
        return findById(id).orElseThrow(() -> new NotFoundException("CaseDefinition", id));
    }

    /** Latest version of every deployed key — backs GET /case-definitions. */
    public List<CaseDefinition> listLatest(String tenantId) {
        return jdbc.sql("""
                SELECT ID_ FROM CM_CASE_DEF d
                WHERE VERSION_NO_ = (SELECT MAX(VERSION_NO_) FROM CM_CASE_DEF x
                                     WHERE x.KEY_ = d.KEY_
                                       AND (x.TENANT_ID_ = d.TENANT_ID_
                                            OR (x.TENANT_ID_ IS NULL AND d.TENANT_ID_ IS NULL)))
                  AND (:tenant IS NULL OR TENANT_ID_ = :tenant)
                ORDER BY KEY_""")
            .param("tenant", tenantId)
            .query(String.class).list().stream()
            .map(this::findById)
            .flatMap(Optional::stream)
            .toList();
    }

    public Optional<CaseDefinition> findLatest(String key, String tenantId) {
        return jdbc.sql("""
                SELECT ID_ FROM CM_CASE_DEF
                WHERE KEY_ = :key AND (TENANT_ID_ = :tenant OR (:tenant IS NULL AND TENANT_ID_ IS NULL))
                ORDER BY VERSION_NO_ DESC FETCH FIRST 1 ROWS ONLY""")
                .param("key", key).param("tenant", tenantId)
                .query(String.class).optional()
                .flatMap(this::findById);
    }

    /**
     * Looks up a form schema by case-definition key without a tenant filter, because the
     * interface this method implements (spec Task 5) intentionally has no tenantId parameter:
     * callers such as the (Task 24) definition-listing endpoint and any future form-rendering
     * client resolve a form purely by {@code (key, formKey)}. The brief's own repository sketch
     * plugged this gap by hardcoding tenant {@code "t1"} and falling back to the untenanted
     * definition — that happens to match this task's own fixture but is not a real
     * implementation of a tenant-agnostic lookup; a definition deployed only under, say,
     * tenant "t2" would never be found. This instead picks, across ALL tenants, the row with
     * the highest VERSION_NO_ (ties broken by the most recent DEPLOYED_AT_), which is the best
     * available answer given the interface has no tenant to disambiguate with. Multi-tenant
     * deployments that reuse the same key across tenants remain genuinely ambiguous under this
     * signature — that is a gap in the interface, not something this method can paper over.
     */
    @SuppressWarnings("unchecked")
    public Optional<Map<String, Object>> formSchema(String key, String formKey) {
        return jdbc.sql("""
                SELECT FORMS_JSON_ FROM CM_CASE_DEF
                WHERE KEY_ = :key
                ORDER BY VERSION_NO_ DESC, DEPLOYED_AT_ DESC
                FETCH FIRST 1 ROWS ONLY""")
                .param("key", key)
                .query(String.class).optional()
                .map(JsonCodec::toMap)
                .map(forms -> forms.get(formKey))
                .filter(Map.class::isInstance)
                .map(o -> (Map<String, Object>) o);
    }

    private List<PlanItemDefinition> planItems(String caseDefId) {
        return jdbc.sql("""
                SELECT ID_, CASE_DEF_ID_, DEF_KEY_, TYPE_, NAME_, PARENT_STAGE_KEY_, MANUAL_ACT_,
                       REQUIRED_, REPETITION_, ENTRY_CRIT_JSON_, EXIT_CRIT_JSON_, FORM_KEY_,
                       PROC_DEF_KEY_, CAND_GROUPS_JSON_, SORT_ORDER_
                FROM CM_PLAN_ITEM_DEF WHERE CASE_DEF_ID_ = :id ORDER BY SORT_ORDER_""")
                .param("id", caseDefId)
                .query((rs, n) -> new PlanItemDefinition(
                        rs.getString("ID_"), rs.getString("CASE_DEF_ID_"), rs.getString("DEF_KEY_"),
                        PlanItemType.valueOf(rs.getString("TYPE_")), rs.getString("NAME_"),
                        rs.getString("PARENT_STAGE_KEY_"),
                        rs.getInt("MANUAL_ACT_") == 1, rs.getInt("REQUIRED_") == 1,
                        rs.getInt("REPETITION_") == 1,
                        JsonCodec.toList(rs.getString("ENTRY_CRIT_JSON_")),
                        JsonCodec.toList(rs.getString("EXIT_CRIT_JSON_")),
                        rs.getString("FORM_KEY_"), rs.getString("PROC_DEF_KEY_"),
                        JsonCodec.toList(rs.getString("CAND_GROUPS_JSON_")),
                        rs.getInt("SORT_ORDER_")))
                .list();
    }

    private static CaseDefinition mapDefinition(java.sql.ResultSet rs, List<PlanItemDefinition> items)
            throws java.sql.SQLException {
        return new CaseDefinition(
                rs.getString("ID_"), rs.getString("KEY_"), rs.getInt("VERSION_NO_"),
                rs.getString("NAME_"), rs.getString("TENANT_ID_"), rs.getString("DESCRIPTION_"),
                rs.getString("SLA_POLICY_ID_"),
                JsonCodec.toList(rs.getString("ROLES_JSON_")),
                JsonCodec.toList(rs.getString("ATTACH_CATS_JSON_")),
                JsonCodec.toMap(rs.getString("FORMS_JSON_")),
                items,
                rs.getObject("DEPLOYED_AT_", OffsetDateTime.class),
                rs.getString("DEPLOYED_BY_"));
    }
}
