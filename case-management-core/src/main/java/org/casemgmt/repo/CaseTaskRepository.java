package org.casemgmt.repo;

import org.casemgmt.domain.CaseTask;
import org.casemgmt.domain.TaskState;
import org.casemgmt.error.NotFoundException;
import org.casemgmt.error.OptimisticLockException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.simple.JdbcClient.StatementSpec;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public class CaseTaskRepository {

    private static final String COLUMNS = """
            ID_, CASE_ID_, PLAN_ITEM_ID_, CAMUNDA_TASK_ID_, NAME_, DESCRIPTION_, STATE_,
            ASSIGNEE_, DELEGATED_BY_, CAND_GROUPS_JSON_, FORM_KEY_, PRIORITY_, DUE_AT_,
            OUTCOME_, ENGINE_SYNC_, VERSION_, CREATED_AT_, UPDATED_AT_, COMPLETED_AT_,
            PROJECTION_STATUS_, LAST_ENGINE_UPDATE_AT_, LAST_PROJECTED_AT_""";

    private final JdbcClient jdbc;

    public CaseTaskRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(CaseTask t) {
        jdbc.sql("""
                INSERT INTO CM_TASK (ID_, CASE_ID_, PLAN_ITEM_ID_, CAMUNDA_TASK_ID_, NAME_,
                    DESCRIPTION_, STATE_, ASSIGNEE_, DELEGATED_BY_, CAND_GROUPS_JSON_, FORM_KEY_,
                    PRIORITY_, DUE_AT_, OUTCOME_, ENGINE_SYNC_, VERSION_, CREATED_AT_, UPDATED_AT_,
                    COMPLETED_AT_)
                VALUES (:id, :caseId, :planItemId, :engineTaskId, :name, :description, :state,
                    :assignee, :delegatedBy, :groups, :formKey, :priority, :dueAt, :outcome,
                    :sync, :version, :createdAt, :updatedAt, :completedAt)""")
            .param("id", t.id()).param("caseId", t.caseId()).param("planItemId", t.planItemId())
            .param("engineTaskId", t.engineTaskId()).param("name", t.name())
            .param("description", t.description()).param("state", t.state().name())
            .param("assignee", t.assignee()).param("delegatedBy", t.delegatedBy())
            .param("groups", JsonCodec.toJson(t.candidateGroups())).param("formKey", t.formKey())
            .param("priority", t.priority()).param("dueAt", t.dueAt()).param("outcome", t.outcome())
            .param("sync", t.engineSync().name()).param("version", t.version())
            .param("createdAt", t.createdAt()).param("updatedAt", t.updatedAt())
            .param("completedAt", t.completedAt())
            .update();
    }

    public Optional<CaseTask> findById(String id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM CM_TASK WHERE ID_ = :id")
                .param("id", id).query(CaseTaskRepository::map).optional();
    }

    public CaseTask require(String id) {
        return findById(id).orElseThrow(() -> new NotFoundException("Task", id));
    }

    /**
     * Locks the confirmed task row while a remote mutation command is selected or created.
     * The version predicate makes a stale HTTP request fail before it can compete for an active
     * command; {@code FOR UPDATE} serializes distinct idempotency keys for the same task.
     */
    public CaseTask lockForOperation(String id, long expectedVersion) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM CM_TASK "
                        + "WHERE ID_ = :id AND VERSION_ = :expected FOR UPDATE")
                .param("id", id).param("expected", expectedVersion)
                .query(CaseTaskRepository::map).optional()
                .orElseThrow(() -> new OptimisticLockException("Task", id, expectedVersion));
    }

    public Optional<CaseTask> findByEngineTaskId(String engineTaskId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM CM_TASK WHERE CAMUNDA_TASK_ID_ = :tid")
                .param("tid", engineTaskId).query(CaseTaskRepository::map).optional();
    }

    /**
     * Looks up the (at most one) task backing a plan item — the correlation key
     * {@code EngineCommandDispatcher.SyncReporter} reports {@code CREATE_TASK} confirmations
     * against (Task 25's starter wiring: {@code CaseTaskRepository.findByCase} takes a case id,
     * not a plan item id, so using it here silently matched nothing).
     */
    public Optional<CaseTask> findByPlanItemId(String planItemId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM CM_TASK WHERE PLAN_ITEM_ID_ = :pid")
                .param("pid", planItemId).query(CaseTaskRepository::map).optional();
    }

    public List<CaseTask> findByCase(String caseId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM CM_TASK WHERE CASE_ID_ = :caseId ORDER BY CREATED_AT_")
                .param("caseId", caseId).query(CaseTaskRepository::map).list();
    }

    /**
     * Worklist: open/claimed tasks that are either assigned to the caller or reachable
     * through the caller's candidate-group membership — "my work OR work I could pick up",
     * the same semantics {@code ActionPolicy.mayActOnTask} (Task 23) already established for
     * "may this caller act on this task at all".
     *
     * <p>Three rules make this query more than a plain filter:
     *
     * <ul>
     * <li>Tasks whose {@code ENGINE_SYNC_} is not {@code SYNCED} are excluded outright. In
     * remote mode the Camunda task cannot be created inside the same local transaction as the
     * CM_TASK row (spec §3.5); until the engine confirms the task exists, claiming or
     * completing it locally would succeed while the corresponding engine action fails. Hiding
     * unsynced tasks from the worklist makes that eventual-consistency window invisible to the
     * caller rather than surfacing as a confusing failure on claim.</li>
     * <li>Candidate-group matching needs a set-overlap test between the caller's groups and
     * each task's {@code CAND_GROUPS_JSON_} array. The brief's sketch built this with a
     * {@code JSON_TABLE(...) MEMBER OF (SELECT * FROM JSON_TABLE(:groupsJson, ...))}
     * construct — passing the caller's group list into the query as a second JSON document.
     * That form does not compile on Oracle 23ai's optimizer in this shape (ORA-00907 from the
     * nested JSON_TABLE used as a bare subquery operand of MEMBER OF). It also isn't needed:
     * this codebase already has a working pattern for "column value is one of a caller-side
     * list" — {@code ParticipantRepository.rolesOf} binds a Java {@code List<String>} straight
     * to an {@code IN (:groups)} parameter and lets Spring's {@code NamedParameterJdbcTemplate}
     * expand it. Applying that same pattern here needed only ONE JSON_TABLE, on the stored
     * column: explode {@code CAND_GROUPS_JSON_} into rows and test each exploded value with
     * plain {@code IN (:groups)}. That is the form used below.</li>
     * <li><b>No assignee and no groups means no visibility, not full visibility.</b> A caller
     * who is nobody's assignee and belongs to no candidate group is not entitled to see any
     * task by either predicate, so the two clauses cannot simply be omitted when their input
     * is empty — omitting both would leave the WHERE clause with no visibility restriction at
     * all, returning every task in the system to the least-privileged caller (the defect a
     * live review caught: {@code worklist(null, List.of(), n)} returned every OPEN/CLAIMED
     * SYNCED task, system-wide). This method short-circuits to an empty list before touching
     * the database in that case, and otherwise ORs whichever of the two predicates actually
     * has input rather than ANDing them (ANDing was the second defect: it hid a task assigned
     * to the caller under a non-matching group, and a group-matched task assigned to nobody
     * relevant, from the same caller).</li>
     * </ul>
     */
    public List<CaseTask> worklist(String tenantId, String assignee, List<String> groups, int limit) {
        boolean hasAssignee = assignee != null;
        boolean hasGroups = groups != null && !groups.isEmpty();

        if (!hasAssignee && !hasGroups) {
            return List.of();
        }

        String groupPredicate = """
                EXISTS (
                    SELECT 1 FROM JSON_TABLE(t.CAND_GROUPS_JSON_, '$[*]' COLUMNS (g VARCHAR2(255) PATH '$')) jt
                    WHERE jt.g IN (:groups))""";

        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM CM_TASK t\n"
                + "WHERE STATE_ IN ('OPEN','CLAIMED') AND ENGINE_SYNC_ = 'SYNCED'\n");
        // Tenant predicate (Task 24 fix round 1, review finding Critical: no tenant scoping
        // anywhere). CM_TASK carries no TENANT_ID_ of its own, so the tenant has to come from
        // the owning case. Without this, identity groups are global: a user of tenant B who
        // happens to be in a group named "reviewers" saw — and could claim — tenant A's tasks.
        // A null tenantId means "do not filter", which is what the repository-level tests and
        // any cross-tenant tooling pass; nothing reachable from HTTP may pass null.
        if (tenantId != null) {
            sql.append("  AND EXISTS (SELECT 1 FROM CM_CASE c"
                    + " WHERE c.ID_ = t.CASE_ID_ AND c.TENANT_ID_ = :tenantId)\n");
        }
        sql.append("  AND ");
        if (hasAssignee && hasGroups) {
            sql.append("(ASSIGNEE_ = :assignee OR ").append(groupPredicate).append(")\n");
        } else if (hasAssignee) {
            sql.append("ASSIGNEE_ = :assignee\n");
        } else {
            sql.append(groupPredicate).append("\n");
        }
        sql.append("ORDER BY CREATED_AT_ FETCH FIRST :limit ROWS ONLY");

        StatementSpec spec = jdbc.sql(sql.toString());
        if (tenantId != null) spec = spec.param("tenantId", tenantId);
        if (hasAssignee) spec = spec.param("assignee", assignee);
        if (hasGroups) spec = spec.param("groups", groups);
        return spec.param("limit", limit).query(CaseTaskRepository::map).list();
    }

    /**
     * Optimistic update. See {@code PlanItemRepository.updateState} / {@code CaseRepository.update}
     * for why the returned object is constructed locally rather than re-read after the UPDATE,
     * and why UPDATED_AT_/COMPLETED_AT_ are bound from a single Java-captured
     * {@code OffsetDateTime} instead of SYSTIMESTAMP.
     */
    public CaseTask update(CaseTask t, long expectedVersion) {
        OffsetDateTime updatedAt = OffsetDateTime.now();
        OffsetDateTime completedAt = t.state() == TaskState.COMPLETED
                ? (t.completedAt() != null ? t.completedAt() : updatedAt)
                : t.completedAt();

        int rows = jdbc.sql("""
                UPDATE CM_TASK SET STATE_ = :state, ASSIGNEE_ = :assignee, DELEGATED_BY_ = :delegatedBy,
                    OUTCOME_ = :outcome, DUE_AT_ = :dueAt, COMPLETED_AT_ = :completedAt,
                    UPDATED_AT_ = :updatedAt, VERSION_ = VERSION_ + 1
                WHERE ID_ = :id AND VERSION_ = :expected""")
            .param("state", t.state().name()).param("assignee", t.assignee())
            .param("delegatedBy", t.delegatedBy()).param("outcome", t.outcome())
            .param("dueAt", t.dueAt()).param("completedAt", completedAt).param("updatedAt", updatedAt)
            .param("id", t.id()).param("expected", expectedVersion)
            .update();
        if (rows == 0) throw new OptimisticLockException("Task", t.id(), expectedVersion);

        return new CaseTask(t.id(), t.caseId(), t.planItemId(), t.engineTaskId(), t.name(), t.description(),
                t.state(), t.assignee(), t.delegatedBy(), t.candidateGroups(), t.formKey(), t.priority(),
                t.dueAt(), t.outcome(), t.engineSync(), expectedVersion + 1, t.createdAt(), updatedAt,
                completedAt, t.projectionStatus(), t.lastEngineUpdateAt(), t.lastProjectedAt());
    }

    /**
     * Records the engine's confirmation of a task, either directly (embedded mode) or via the
     * command dispatcher (remote mode, Task 13's outbox). First-writer-wins on
     * {@code CAMUNDA_TASK_ID_}: once bound to a non-null engine id, later calls never change it,
     * even when {@code engineTaskId} is a different non-null value.
     *
     * <p>This closes a gap the Task 6 review carried forward: the outbox dispatcher delivers
     * commands at-least-once (see {@code EngineCommandDispatcher}) and there is no expected-value
     * to compare against at an async callback site (unlike {@link #update}, which has a version
     * number to guard with) — a crash between the remote engine call succeeding and the command
     * being marked {@code DONE} makes the SAME command eligible to be claimed and executed again,
     * calling {@code createHumanTask} a second time against the real engine and reporting a
     * SECOND engine id here. Without a guard, {@code COALESCE(:engineTaskId, CAMUNDA_TASK_ID_)}
     * would happily overwrite the first (correct) engine id with the second, orphaning the first
     * engine task with nothing in CM_TASK pointing at it any more — exactly the failure the
     * reviewer flagged. Reversing the COALESCE arguments makes the column idempotent: the first
     * engine id this task is ever told about is the one it keeps.
     *
     * <p>This does NOT prevent the duplicate engine task from being created remotely in the first
     * place — that needs an idempotency key on the engine call itself (e.g. a business key derived
     * from the command id), which is a bigger change to {@code EngineGateway.createHumanTask}'s
     * contract than this task's scope. What this guard guarantees is narrower but still real:
     * CM_TASK never loses track of the engine task it is actually meant to represent, so the
     * worklist and claim/complete paths stay pointed at a consistent engine id even if a
     * duplicate command execution happens.
     */
    public void markSync(String taskId, CaseTask.EngineSync sync, String engineTaskId) {
        jdbc.sql("""
                UPDATE CM_TASK SET ENGINE_SYNC_ = :sync,
                    CAMUNDA_TASK_ID_ = COALESCE(CAMUNDA_TASK_ID_, :engineTaskId),
                    UPDATED_AT_ = SYSTIMESTAMP
                WHERE ID_ = :id""")
            .param("sync", sync.name()).param("engineTaskId", engineTaskId).param("id", taskId)
            .update();
    }

    private static CaseTask map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new CaseTask(rs.getString("ID_"), rs.getString("CASE_ID_"), rs.getString("PLAN_ITEM_ID_"),
                rs.getString("CAMUNDA_TASK_ID_"), rs.getString("NAME_"), rs.getString("DESCRIPTION_"),
                TaskState.valueOf(rs.getString("STATE_")), rs.getString("ASSIGNEE_"),
                rs.getString("DELEGATED_BY_"), JsonCodec.toList(rs.getString("CAND_GROUPS_JSON_")),
                rs.getString("FORM_KEY_"), rs.getInt("PRIORITY_"),
                rs.getObject("DUE_AT_", OffsetDateTime.class), rs.getString("OUTCOME_"),
                CaseTask.EngineSync.valueOf(rs.getString("ENGINE_SYNC_")),
                rs.getLong("VERSION_"),
                rs.getObject("CREATED_AT_", OffsetDateTime.class),
                rs.getObject("UPDATED_AT_", OffsetDateTime.class),
                rs.getObject("COMPLETED_AT_", OffsetDateTime.class),
                org.casemgmt.projection.ProjectionStatus.valueOf(rs.getString("PROJECTION_STATUS_")),
                rs.getObject("LAST_ENGINE_UPDATE_AT_", OffsetDateTime.class),
                rs.getObject("LAST_PROJECTED_AT_", OffsetDateTime.class));
    }
}
