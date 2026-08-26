package org.casemgmt.repo;

import org.casemgmt.domain.*;
import org.casemgmt.error.NotFoundException;
import org.casemgmt.error.OptimisticLockException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class PlanItemRepository {

    private static final String COLUMNS = """
            ID_, CASE_ID_, PI_DEF_ID_, TYPE_, NAME_, STATE_, PARENT_STAGE_ID_, AD_HOC_,
            REPETITION_NO_, CAMUNDA_TASK_ID_, PROC_INST_ID_, TERM_REASON_, VERSION_,
            CREATED_AT_, UPDATED_AT_, ENDED_AT_, ENGINE_ACTIVITY_ID_, PROJECTION_STATUS_,
            LAST_ENGINE_UPDATE_AT_, LAST_PROJECTED_AT_""";

    private final JdbcClient jdbc;

    public PlanItemRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(PlanItem p) {
        jdbc.sql("""
                INSERT INTO CM_PLAN_ITEM (ID_, CASE_ID_, PI_DEF_ID_, TYPE_, NAME_, STATE_,
                    PARENT_STAGE_ID_, AD_HOC_, REPETITION_NO_, CAMUNDA_TASK_ID_, PROC_INST_ID_,
                    TERM_REASON_, VERSION_, CREATED_AT_, UPDATED_AT_, ENDED_AT_)
                VALUES (:id, :caseId, :defId, :type, :name, :state, :parent, :adHoc, :rep,
                    :taskId, :procId, :reason, :version, :createdAt, :updatedAt, :endedAt)""")
            .param("id", p.id()).param("caseId", p.caseId()).param("defId", p.planItemDefId())
            .param("type", p.type().name()).param("name", p.name()).param("state", p.state().name())
            .param("parent", p.parentStageId()).param("adHoc", p.adHoc() ? 1 : 0)
            .param("rep", p.repetitionNo()).param("taskId", p.engineTaskId())
            .param("procId", p.processInstanceId()).param("reason", p.terminationReason())
            .param("version", p.version()).param("createdAt", p.createdAt())
            .param("updatedAt", p.updatedAt()).param("endedAt", p.endedAt())
            .update();
    }

    public Optional<PlanItem> findById(String id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM CM_PLAN_ITEM WHERE ID_ = :id")
                .param("id", id).query(PlanItemRepository::map).optional();
    }

    public PlanItem require(String id) {
        return findById(id).orElseThrow(() -> new NotFoundException("PlanItem", id));
    }

    public List<PlanItem> findByCase(String caseId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM CM_PLAN_ITEM WHERE CASE_ID_ = :caseId ORDER BY CREATED_AT_")
                .param("caseId", caseId).query(PlanItemRepository::map).list();
    }

    /**
     * {@link #findByCase} for many cases at once — one statement instead of one per case.
     *
     * <p>Added by Task 24 fix round 1 (review finding I8): a case-listing page derives
     * {@code availableActions[]} per row, which needs each case's plan items, so the single-case
     * method turned a 50-row page into 50 queries. Ordering within each case is unchanged
     * ({@code CREATED_AT_}), which matters because {@code CaseSnapshot.latest(defKey)} depends
     * on it. Every requested id gets an entry, empty where the case has no plan items.
     */
    public Map<String, List<PlanItem>> findByCases(Collection<String> caseIds) {
        Map<String, List<PlanItem>> byCase = new LinkedHashMap<>();
        for (String caseId : caseIds) {
            byCase.put(caseId, new ArrayList<>());
        }
        if (byCase.isEmpty()) {
            return byCase;
        }
        jdbc.sql("SELECT " + COLUMNS + " FROM CM_PLAN_ITEM WHERE CASE_ID_ IN (:caseIds)"
                        + " ORDER BY CASE_ID_, CREATED_AT_")
            .param("caseIds", List.copyOf(byCase.keySet()))
            .query(PlanItemRepository::map).list()
            .forEach(item -> byCase.get(item.caseId()).add(item));
        return byCase;
    }

    /**
     * Optimistic update of a plan item's lifecycle state.
     *
     * <p>Follows the pattern established by {@code CaseRepository.update} (Task 4/5): a
     * repository call may run with no surrounding transaction at all, in which case the UPDATE
     * and any follow-up read are two independently auto-committed statements with nothing tying
     * them together. (Corrected in Task 27: this used to say the MODULE has no transaction
     * manager, which stopped being true at Task 5 — {@code TransactionManagerConfig} is in this
     * module. The conclusion is unchanged, because the untransacted case is the weakest
     * environment this method must be correct in.) Re-reading
     * the row after a successful UPDATE would risk returning a concurrent writer's state as
     * if it confirmed this call's own write. Instead, since the WHERE clause already proves
     * the UPDATE matched exactly one row at {@code expectedVersion}, the post-state is
     * constructed locally: same row, version incremented by exactly one, UPDATED_AT_ (and,
     * when the new state is terminal, ENDED_AT_) bound from a single Java-captured
     * {@code OffsetDateTime} rather than left to SQL's SYSTIMESTAMP, so the row and the
     * returned object agree exactly.
     */
    public PlanItem updateState(PlanItem p, long expectedVersion) {
        OffsetDateTime updatedAt = OffsetDateTime.now();
        OffsetDateTime endedAt = p.state().isEnded()
                ? (p.endedAt() != null ? p.endedAt() : updatedAt)
                : p.endedAt();

        int rows = jdbc.sql("""
                UPDATE CM_PLAN_ITEM SET STATE_ = :state, TERM_REASON_ = :reason,
                    ENDED_AT_ = :endedAt, UPDATED_AT_ = :updatedAt, VERSION_ = VERSION_ + 1
                WHERE ID_ = :id AND VERSION_ = :expected""")
            .param("state", p.state().name()).param("reason", p.terminationReason())
            .param("endedAt", endedAt).param("updatedAt", updatedAt)
            .param("id", p.id()).param("expected", expectedVersion)
            .update();
        if (rows == 0) throw new OptimisticLockException("PlanItem", p.id(), expectedVersion);

        return new PlanItem(p.id(), p.caseId(), p.planItemDefId(), p.type(), p.name(), p.state(),
                p.parentStageId(), p.adHoc(), p.repetitionNo(), p.engineTaskId(), p.processInstanceId(),
                p.terminationReason(), expectedVersion + 1, p.createdAt(), updatedAt, endedAt,
                p.engineActivityId(), p.projectionStatus(), p.lastEngineUpdateAt(), p.lastProjectedAt());
    }

    public void bindEngineTask(String planItemId, String engineTaskId) {
        jdbc.sql("UPDATE CM_PLAN_ITEM SET CAMUNDA_TASK_ID_ = :taskId, UPDATED_AT_ = SYSTIMESTAMP WHERE ID_ = :id")
            .param("taskId", engineTaskId).param("id", planItemId).update();
    }

    public void bindProcessInstance(String planItemId, String procInstId) {
        jdbc.sql("UPDATE CM_PLAN_ITEM SET PROC_INST_ID_ = :procId, UPDATED_AT_ = SYSTIMESTAMP WHERE ID_ = :id")
            .param("procId", procInstId).param("id", planItemId).update();
    }

    private static PlanItem map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new PlanItem(rs.getString("ID_"), rs.getString("CASE_ID_"), rs.getString("PI_DEF_ID_"),
                PlanItemType.valueOf(rs.getString("TYPE_")), rs.getString("NAME_"),
                PlanItemState.valueOf(rs.getString("STATE_")), rs.getString("PARENT_STAGE_ID_"),
                rs.getInt("AD_HOC_") == 1, rs.getInt("REPETITION_NO_"),
                rs.getString("CAMUNDA_TASK_ID_"), rs.getString("PROC_INST_ID_"),
                rs.getString("TERM_REASON_"), rs.getLong("VERSION_"),
                rs.getObject("CREATED_AT_", OffsetDateTime.class),
                rs.getObject("UPDATED_AT_", OffsetDateTime.class),
                rs.getObject("ENDED_AT_", OffsetDateTime.class),
                rs.getString("ENGINE_ACTIVITY_ID_"),
                org.casemgmt.projection.ProjectionStatus.valueOf(rs.getString("PROJECTION_STATUS_")),
                rs.getObject("LAST_ENGINE_UPDATE_AT_", OffsetDateTime.class),
                rs.getObject("LAST_PROJECTED_AT_", OffsetDateTime.class));
    }
}
