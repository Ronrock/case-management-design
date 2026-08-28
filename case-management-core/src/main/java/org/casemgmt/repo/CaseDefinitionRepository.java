package org.casemgmt.repo;

import org.casemgmt.domain.*;
import org.casemgmt.error.NotFoundException;
import org.casemgmt.orchestration.OrchestrationMode;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class CaseDefinitionRepository {

    private final JdbcClient jdbc;
    private final DataSource dataSource;

    /**
     * Takes the {@link DataSource} directly (not a pre-built {@link JdbcClient}) because
     * {@link #insert} needs to run the CM_CASE_DEF row and every CM_PLAN_ITEM_DEF row it
     * explodes into as one atomic unit on a single physical connection — see that method's
     * Javadoc for why.
     */
    public CaseDefinitionRepository(DataSource dataSource) {
        this.dataSource = dataSource;
        this.jdbc = JdbcClient.create(dataSource);
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

    /**
     * Writes the CM_CASE_DEF row and every CM_PLAN_ITEM_DEF row it explodes into as one
     * atomic unit.
     *
     * <p>A caller may reach this method with no surrounding transaction at all — {@link #jdbc}
     * and {@code dataSource} are a plain pooled {@code DataSource} (HikariCP), which is exactly
     * how {@code OracleTestBase} and {@code TestServices} build it, and {@code
     * CaseDefinitionService.deploy} carries no {@code @Transactional} today. Before this fix,
     * each of {@code insert}'s N+1 statements
     * (one CM_CASE_DEF row, N CM_PLAN_ITEM_DEF rows) ran as its own independently
     * autocommitted call, each on whatever connection Hikari happened to hand out. A failure
     * partway through the plan-item loop — a constraint violation, a lost connection, a bad
     * CLOB write — left the CM_CASE_DEF row and however many CM_PLAN_ITEM_DEF rows had
     * already committed sitting in the database as a "successfully deployed" definition:
     * {@link #findLatest} and {@link #listLatest} would serve it with no indication anything
     * was wrong, a case started from it would silently never create the missing plan
     * item(s), and any {@code entryCriteria} referencing the missing defKey would either
     * throw a confusing {@code CriterionEvaluationException} far from the real cause, or
     * evaluate against a null with no error at all.
     *
     * <p>Fixed by taking one physical {@link Connection}, disabling autocommit
     * on it directly, running every INSERT against that single connection through a
     * throwaway {@link SingleConnectionDataSource} wrapper (constructed with
     * {@code suppressClose=true} so that JdbcClient's normal per-statement
     * connection-release doesn't actually close the shared connection out from under the
     * loop — without that flag the second INSERT would fail with "connection closed" after
     * the first), then committing once at the end or rolling back on any exception.
     *
     * <p><b>{@link DataSourceUtils#getConnection}, not {@code dataSource.getConnection()}</b>
     * (final whole-branch review, Important 7). This method used to take a raw pooled
     * connection, and an earlier version of this Javadoc asserted that was safe because "this
     * module deliberately has neither [an {@code ApplicationContext} nor a
     * {@code PlatformTransactionManager}]". That has been false since Task 5:
     * {@code org.casemgmt.config.TransactionManagerConfig} lives in THIS module, the starter
     * imports it, and half the services here are genuinely proxied. The raw
     * {@code getConnection()} was safe only by the accident that {@code
     * CaseDefinitionService.deploy} happens not to be {@code @Transactional} — and the moment
     * someone adds that annotation (an entirely reasonable next change; deploy writes no audit
     * row today and someone will want one), a raw connection would put these INSERTs on a
     * SECOND physical connection and commit them outside the enclosing transaction, so a
     * rollback of the caller's work would leave the definition behind. The Javadoc that should
     * have warned them said the opposite.
     *
     * <p>{@code DataSourceUtils.getConnection}/{@code releaseConnection} is the participating
     * pair: inside a Spring-managed transaction it returns THAT transaction's connection, and
     * the {@code setAutoCommit(false)}/{@code commit()} below are then skipped (see the guard in
     * the code — driving a synchronized connection's commit directly would commit the enclosing
     * transaction's work early, which is worse than the bug being fixed). With no transaction
     * in progress it behaves exactly as before: a fresh pooled connection this method owns,
     * commits and releases itself. {@code releaseConnection} likewise only really closes a
     * connection this method actually owns.
     */
    public void insert(CaseDefinition d) {
        Connection conn = DataSourceUtils.getConnection(dataSource);
        // True when DataSourceUtils handed back a connection bound to an enclosing Spring
        // transaction. That transaction owns the commit/rollback and the autocommit setting;
        // this method must not touch any of them, and it does not need to — its whole purpose,
        // "these N+1 statements land or do not land together", is already guaranteed by the
        // enclosing transaction, on the very same connection.
        boolean enlisted = DataSourceUtils.isConnectionTransactional(conn, dataSource);
        try {
            boolean priorAutoCommit = enlisted || conn.getAutoCommit();
            if (!enlisted) {
                conn.setAutoCommit(false);
            }
            JdbcClient txJdbc = JdbcClient.create(new SingleConnectionDataSource(conn, true));
            try {
                insertCaseDefRow(txJdbc, d);
                for (PlanItemDefinition p : d.planItems()) {
                    insertPlanItemDefRow(txJdbc, d.id(), p);
                }
                if (!enlisted) {
                    conn.commit();
                }
            } catch (RuntimeException e) {
                if (!enlisted) {
                    rollbackQuietly(conn);
                }
                throw e;
            } finally {
                if (!enlisted) {
                    conn.setAutoCommit(priorAutoCommit);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to deploy case definition " + d.id(), e);
        } finally {
            DataSourceUtils.releaseConnection(conn, dataSource);
        }
    }

    private static void rollbackQuietly(Connection conn) {
        try {
            conn.rollback();
        } catch (SQLException suppressed) {
            // Best-effort: the exception that triggered the rollback is what the caller sees;
            // a rollback failure on top of that would only obscure the real cause.
        }
    }

    private static void insertCaseDefRow(JdbcClient jdbc, CaseDefinition d) {
        jdbc.sql("""
                INSERT INTO CM_CASE_DEF (ID_, KEY_, VERSION_NO_, NAME_, TENANT_ID_, DESCRIPTION_, ORCHESTRATION_MODE_,
                    SLA_POLICY_ID_, ROLES_JSON_, ATTACH_CATS_JSON_, FORMS_JSON_, DEPLOYED_AT_, DEPLOYED_BY_)
                VALUES (:id, :key, :ver, :name, :tenant, :desc, :mode, :sla, :roles, :cats, :forms,
                    :deployedAt, :deployedBy)""")
            .param("id", d.id()).param("key", d.key()).param("ver", d.versionNo())
            .param("name", d.name()).param("tenant", d.tenantId()).param("desc", d.description())
            .param("mode", d.orchestrationMode().name())
            .param("sla", d.slaPolicyId())
            .param("roles", JsonCodec.toJson(d.roles()))
            .param("cats", JsonCodec.toJson(d.attachmentCategories()))
            .param("forms", JsonCodec.toJson(d.forms()))
            .param("deployedAt", d.deployedAt()).param("deployedBy", d.deployedBy())
            .update();
    }

    private static void insertPlanItemDefRow(JdbcClient jdbc, String caseDefId, PlanItemDefinition p) {
        jdbc.sql("""
                INSERT INTO CM_PLAN_ITEM_DEF (ID_, CASE_DEF_ID_, DEF_KEY_, TYPE_, NAME_,
                    PARENT_STAGE_KEY_, MANUAL_ACT_, REQUIRED_, REPETITION_,
                    ENTRY_CRIT_JSON_, EXIT_CRIT_JSON_, FORM_KEY_, PROC_DEF_KEY_,
                    CAND_GROUPS_JSON_, SORT_ORDER_)
                VALUES (:id, :defId, :key, :type, :name, :parent, :manual, :required, :repetition,
                    :entry, :exit, :formKey, :procKey, :groups, :sort)""")
            .param("id", p.id()).param("defId", caseDefId).param("key", p.defKey())
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

    public Optional<CaseDefinition> findById(String id) {
        return jdbc.sql("""
                SELECT ID_, KEY_, VERSION_NO_, NAME_, TENANT_ID_, DESCRIPTION_, ORCHESTRATION_MODE_, SLA_POLICY_ID_,
                       ROLES_JSON_, ATTACH_CATS_JSON_, FORMS_JSON_, DEPLOYED_AT_, DEPLOYED_BY_
                FROM CM_CASE_DEF WHERE ID_ = :id""")
                .param("id", id)
                .query((rs, n) -> mapDefinition(rs, planItems(id)))
                .optional();
    }

    public CaseDefinition require(String id) {
        return findById(id).orElseThrow(() -> new NotFoundException("CaseDefinition", id));
    }

    public Optional<CaseDefinition> findVersion(String key, int version, String tenantId) {
        return jdbc.sql("""
                SELECT ID_ FROM CM_CASE_DEF
                WHERE KEY_ = :key AND VERSION_NO_ = :version
                  AND (TENANT_ID_ = :tenant OR (:tenant IS NULL AND TENANT_ID_ IS NULL))""")
                .param("key", key).param("version", version).param("tenant", tenantId)
                .query(String.class).optional().map(this::require);
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
     * Latest definition that may start a new case. Legacy PLAN_MODEL definitions remain
     * startable exactly as before; BPMN definitions require their immutable binding to be ACTIVE.
     */
    public Optional<CaseDefinition> findLatestStartable(String key, String tenantId) {
        return jdbc.sql("""
                SELECT d.ID_ FROM CM_CASE_DEF d
                WHERE d.KEY_ = :key
                  AND (d.TENANT_ID_ = :tenant
                    OR (:tenant IS NULL AND d.TENANT_ID_ IS NULL))
                  AND (d.ORCHESTRATION_MODE_ = 'PLAN_MODEL'
                    OR (d.ORCHESTRATION_MODE_ = 'BPMN' AND EXISTS (
                      SELECT 1 FROM CM_CASE_DEF_BINDING binding
                      WHERE binding.CASE_DEF_ID_ = d.ID_
                        AND binding.STATUS_ = 'ACTIVE')))
                ORDER BY d.VERSION_NO_ DESC FETCH FIRST 1 ROWS ONLY""")
                .param("key", key).param("tenant", tenantId)
                .query(String.class).optional()
                .flatMap(this::findById);
    }

    /**
     * Looks up a form schema by case-definition key within one tenant.
     *
     * <p>This is the discovery/read-side counterpart to {@link #formSchemaOfDefinition}: a
     * generic UI first resolves the caller's latest visible definition and then asks for the
     * form schema named by that definition. The tenant parameter is mandatory because the same
     * definition key may legitimately exist in multiple tenants with different form models; a
     * cross-tenant "latest version wins" lookup would leak model metadata and could render the
     * wrong fields for a portal user.
     */
    @SuppressWarnings("unchecked")
    public Optional<Map<String, Object>> formSchema(String key, String formKey, String tenantId) {
        return jdbc.sql("""
                SELECT FORMS_JSON_ FROM CM_CASE_DEF
                WHERE KEY_ = :key
                  AND (TENANT_ID_ = :tenant OR (:tenant IS NULL AND TENANT_ID_ IS NULL))
                ORDER BY VERSION_NO_ DESC
                FETCH FIRST 1 ROWS ONLY""")
                .param("key", key)
                .param("tenant", tenantId)
                .query(String.class).optional()
                .map(JsonCodec::toMap)
                .map(forms -> forms.get(formKey))
                .filter(Map.class::isInstance)
                .map(o -> (Map<String, Object>) o);
    }

    /**
     * The same lookup against ONE exact case-definition row, identified by its primary key —
     * the form-schema resolver every write path must use (final whole-branch review,
     * Important 1). {@code CM_CASE_DEF.ID_} is minted per (tenant, key, version) by
     * {@code CaseDefinitionService.definitionId}, so resolving through it is simultaneously
     * version-pinned and tenant-scoped: no {@code ORDER BY VERSION_NO_} to drift under a later
     * deploy, and no cross-tenant row to pick by accident. Callers already hold the id —
     * {@code CM_CASE.CASE_DEF_ID_} is stamped at case creation and is what
     * {@code CaseService.snapshot} resolves the whole plan model through.
     *
     * <p>Deliberately a separate name rather than an overload of {@link #formSchema}: both take
     * two {@code String}s, so an overload would be indistinguishable at the call site — exactly
     * the confusion that produced the defect.
     */
    @SuppressWarnings("unchecked")
    public Optional<Map<String, Object>> formSchemaOfDefinition(String caseDefId, String formKey) {
        return jdbc.sql("SELECT FORMS_JSON_ FROM CM_CASE_DEF WHERE ID_ = :id")
                .param("id", caseDefId)
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
                OrchestrationMode.valueOf(rs.getString("ORCHESTRATION_MODE_")),
                rs.getObject("DEPLOYED_AT_", OffsetDateTime.class),
                rs.getString("DEPLOYED_BY_"));
    }
}
