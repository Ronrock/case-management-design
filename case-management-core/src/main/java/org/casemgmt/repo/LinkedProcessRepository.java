package org.casemgmt.repo;

import org.casemgmt.domain.CaseTask;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public class LinkedProcessRepository {

    public record LinkedProcessRow(String id, String caseId, String planItemId,
                                   String correlationId, String processInstanceId,
                                   String processDefinitionId,
                                   String processDefinitionKey, String state,
                                   CaseTask.EngineSync engineSync, boolean caseRoot) {
        /** Source-compatible row with unknown exact definition identity. */
        public LinkedProcessRow(String id, String caseId, String planItemId,
                                String correlationId, String processInstanceId,
                                String processDefinitionKey, String state,
                                CaseTask.EngineSync engineSync, boolean caseRoot) {
            this(id, caseId, planItemId, correlationId, processInstanceId, null,
                    processDefinitionKey, state, engineSync, caseRoot);
        }

        /** Source-compatible constructor for callers compiled against the pre-correlation row. */
        public LinkedProcessRow(String id, String caseId, String planItemId,
                                String processInstanceId, String processDefinitionKey, String state,
                                CaseTask.EngineSync engineSync) {
            this(id, caseId, planItemId, id, processInstanceId, null, processDefinitionKey, state,
                    engineSync, false);
        }
    }

    private final JdbcClient jdbc;

    public LinkedProcessRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /**
     * {@code engineSync} mirrors {@code CaseTaskRepository.insert}'s own column: {@code PENDING}
     * when {@code procInstId} is not known yet and the outbox dispatcher's confirmation is still
     * outstanding, {@code SYNCED} when it is already the engine's real id. The caller-owned row
     * id is persisted separately as the correlation identity in both cases.
     */
    public void insert(String id, String caseId, String planItemId, String procInstId,
                       String procDefKey, CaseTask.EngineSync engineSync) {
        insert(id, caseId, planItemId, procInstId, null, procDefKey, engineSync);
    }

    public void insert(String id, String caseId, String planItemId, String procInstId,
                       String procDefId, String procDefKey, CaseTask.EngineSync engineSync) {
        jdbc.sql("""
                INSERT INTO CM_LINKED_PROCESS (ID_, CASE_ID_, PLAN_ITEM_ID_, CORRELATION_ID_,
                    PROC_INST_ID_, PROC_DEF_ID_, PROC_DEF_KEY_, STATE_, ENGINE_SYNC_, IS_CASE_ROOT_)
                VALUES (:id, :caseId, :planItemId, :id, :procInstId, :procDefId, :procDefKey,
                    'ACTIVE', :engineSync, 0)""")
            .param("id", id).param("caseId", caseId).param("planItemId", planItemId)
            .param("procInstId", procInstId).param("procDefId", procDefId)
            .param("procDefKey", procDefKey)
            .param("engineSync", engineSync.name()).update();
    }

    @Transactional
    public void insertRoot(String id, String caseId, String procInstId, String procDefKey,
                           CaseTask.EngineSync engineSync) {
        insertRoot(id, caseId, procInstId, null, procDefKey, engineSync);
    }

    @Transactional
    public void insertRoot(String id, String caseId, String procInstId, String procDefId,
                           String procDefKey, CaseTask.EngineSync engineSync) {
        jdbc.sql("""
                INSERT INTO CM_LINKED_PROCESS (ID_, CASE_ID_, PLAN_ITEM_ID_, CORRELATION_ID_,
                    PROC_INST_ID_, PROC_DEF_ID_, PROC_DEF_KEY_, STATE_, ENGINE_SYNC_, IS_CASE_ROOT_)
                VALUES (:id, :caseId, NULL, :id, :procInstId, :procDefId, :procDefKey,
                    'ACTIVE', :engineSync, 1)""")
                .param("id", id).param("caseId", caseId).param("procInstId", procInstId)
                .param("procDefId", procDefId).param("procDefKey", procDefKey)
                .param("engineSync", engineSync.name()).update();
        int updated = jdbc.sql("""
                UPDATE CM_CASE SET ROOT_CORRELATION_ID_ = :correlationId,
                    ROOT_PROC_INST_ID_ = :processInstanceId,
                    PROJECTION_STATUS_ = :status, LAST_PROJECTED_AT_ = SYSTIMESTAMP
                WHERE ID_ = :caseId
                  AND (ROOT_CORRELATION_ID_ IS NULL OR ROOT_CORRELATION_ID_ = :correlationId)
                  AND (ROOT_PROC_INST_ID_ IS NULL OR ROOT_PROC_INST_ID_ = :processInstanceId)""")
                .param("correlationId", id)
                .param("processInstanceId", procInstId)
                .param("status", engineSync == CaseTask.EngineSync.PENDING ? "PENDING" : "CURRENT")
                .param("caseId", caseId).update();
        if (updated != 1) {
            throw new IllegalStateException("Case " + caseId
                    + " already has a different root process correlation or identity");
        }
    }

    public void markState(String procInstId, String state) {
        jdbc.sql("""
                UPDATE CM_LINKED_PROCESS SET STATE_ = :state,
                    ENDED_AT_ = CASE WHEN :state IN ('COMPLETED','TERMINATED') THEN SYSTIMESTAMP ELSE ENDED_AT_ END
                WHERE PROC_INST_ID_ = :procInstId""")
            .param("state", state).param("procInstId", procInstId).update();
    }

    /**
     * Replaces a caller-owned start correlation with the engine's confirmed process-instance
     * identity. Root confirmation updates the owning case in the same transaction. A repeated
     * report of the same identity is a successful no-op; a different identity or a root whose
     * case points at another correlation is rejected.
     */
    @Transactional
    public void confirmStarted(String caseId, String correlationId,
                               String engineProcessInstanceId, OffsetDateTime confirmedAt) {
        confirmStarted(caseId, correlationId, engineProcessInstanceId, null, null, confirmedAt);
    }

    /** Confirms both runtime instance and exact deployed definition identity atomically. */
    @Transactional
    public void confirmStarted(String caseId, String correlationId,
                               String engineProcessInstanceId, String processDefinitionId,
                               String processDefinitionKey, OffsetDateTime confirmedAt) {
        requireNonBlank(caseId, "caseId");
        requireNonBlank(correlationId, "correlationId");
        requireNonBlank(engineProcessInstanceId, "engineProcessInstanceId");
        if (confirmedAt == null) {
            throw new IllegalArgumentException("confirmedAt must not be null");
        }

        int updated = jdbc.sql("""
                UPDATE CM_LINKED_PROCESS
                SET PROC_INST_ID_ = :processInstanceId,
                    PROC_DEF_ID_ = COALESCE(:processDefinitionId, PROC_DEF_ID_),
                    PROC_DEF_KEY_ = COALESCE(:processDefinitionKey, PROC_DEF_KEY_),
                    ENGINE_SYNC_ = 'SYNCED',
                    LAST_ENGINE_UPDATE_AT_ = :confirmedAt,
                    LAST_PROJECTED_AT_ = :confirmedAt
                WHERE CASE_ID_ = :caseId
                  AND CORRELATION_ID_ = :correlationId
                  AND PROC_INST_ID_ IS NULL
                  AND (:processDefinitionId IS NULL OR PROC_DEF_ID_ IS NULL
                    OR PROC_DEF_ID_ = :processDefinitionId)
                  AND (:processDefinitionKey IS NULL OR PROC_DEF_KEY_ IS NULL
                    OR PROC_DEF_KEY_ = :processDefinitionKey)
                  AND ENGINE_SYNC_ = 'PENDING'""")
                .param("processInstanceId", engineProcessInstanceId)
                .param("processDefinitionId", processDefinitionId)
                .param("processDefinitionKey", processDefinitionKey)
                .param("confirmedAt", confirmedAt)
                .param("caseId", caseId)
                .param("correlationId", correlationId)
                .update();

        LinkedProcessRow link = findByCorrelation(caseId, correlationId).orElseThrow(() ->
                new IllegalStateException("No pending linked process for case " + caseId
                        + " and correlation " + correlationId));
        if (updated == 0) {
            assertSameConfirmation(link, engineProcessInstanceId, processDefinitionId,
                    processDefinitionKey);
            if (link.caseRoot()) {
                assertRootConfirmation(caseId, correlationId, engineProcessInstanceId);
            }
            return;
        }
        if (updated != 1) {
            throw new IllegalStateException("Confirmation matched " + updated
                    + " linked processes for correlation " + correlationId);
        }

        if (link.caseRoot()) {
            int caseUpdates = jdbc.sql("""
                    UPDATE CM_CASE
                    SET ROOT_PROC_INST_ID_ = :processInstanceId,
                        PROJECTION_STATUS_ = 'CURRENT',
                        LAST_ENGINE_UPDATE_AT_ = :confirmedAt,
                        LAST_PROJECTED_AT_ = :confirmedAt
                    WHERE ID_ = :caseId
                      AND ROOT_CORRELATION_ID_ = :correlationId
                      AND ROOT_PROC_INST_ID_ IS NULL""")
                    .param("processInstanceId", engineProcessInstanceId)
                    .param("confirmedAt", confirmedAt)
                    .param("caseId", caseId)
                    .param("correlationId", correlationId)
                    .update();
            if (caseUpdates != 1) {
                throw new IllegalStateException("Case " + caseId
                        + " does not have pending root correlation " + correlationId);
            }
        }
    }

    public Optional<LinkedProcessRow> findByCorrelation(String caseId, String correlationId) {
        return jdbc.sql("""
                SELECT ID_, CASE_ID_, PLAN_ITEM_ID_, CORRELATION_ID_, PROC_INST_ID_,
                       PROC_DEF_ID_, PROC_DEF_KEY_, STATE_, ENGINE_SYNC_, IS_CASE_ROOT_
                FROM CM_LINKED_PROCESS
                WHERE CASE_ID_ = :caseId AND CORRELATION_ID_ = :correlationId""")
                .param("caseId", caseId)
                .param("correlationId", correlationId)
                .query(LinkedProcessRepository::map)
                .optional();
    }

    public Optional<LinkedProcessRow> findByCorrelation(String correlationId) {
        return jdbc.sql("""
                SELECT ID_, CASE_ID_, PLAN_ITEM_ID_, CORRELATION_ID_, PROC_INST_ID_,
                       PROC_DEF_ID_, PROC_DEF_KEY_, STATE_, ENGINE_SYNC_, IS_CASE_ROOT_
                FROM CM_LINKED_PROCESS
                WHERE CORRELATION_ID_ = :correlationId""")
                .param("correlationId", correlationId)
                .query(LinkedProcessRepository::map)
                .optional();
    }

    public Optional<LinkedProcessRow> findByProcessInstanceId(String processInstanceId) {
        requireNonBlank(processInstanceId, "processInstanceId");
        return jdbc.sql("""
                SELECT ID_, CASE_ID_, PLAN_ITEM_ID_, CORRELATION_ID_, PROC_INST_ID_,
                       PROC_DEF_ID_, PROC_DEF_KEY_, STATE_, ENGINE_SYNC_, IS_CASE_ROOT_
                FROM CM_LINKED_PROCESS
                WHERE PROC_INST_ID_ = :processInstanceId""")
                .param("processInstanceId", processInstanceId)
                .query(LinkedProcessRepository::map)
                .optional();
    }

    private void assertRootConfirmation(String caseId, String correlationId,
                                        String engineProcessInstanceId) {
        int matches = jdbc.sql("""
                SELECT COUNT(*) FROM CM_CASE
                WHERE ID_ = :caseId
                  AND ROOT_CORRELATION_ID_ = :correlationId
                  AND ROOT_PROC_INST_ID_ = :processInstanceId""")
                .param("caseId", caseId)
                .param("correlationId", correlationId)
                .param("processInstanceId", engineProcessInstanceId)
                .query(Integer.class).single();
        if (matches != 1) {
            throw new IllegalStateException("Case " + caseId
                    + " has a different confirmed root process identity");
        }
    }

    private static void assertSameConfirmation(LinkedProcessRow link,
                                               String engineProcessInstanceId,
                                               String processDefinitionId,
                                               String processDefinitionKey) {
        if (link.engineSync() != CaseTask.EngineSync.SYNCED
                || !engineProcessInstanceId.equals(link.processInstanceId())
                || (processDefinitionId != null
                    && !processDefinitionId.equals(link.processDefinitionId()))
                || (processDefinitionKey != null
                    && !processDefinitionKey.equals(link.processDefinitionKey()))) {
            throw new IllegalStateException("Linked process correlation " + link.correlationId()
                    + " is already associated with a different state or engine identity");
        }
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    /**
     * Backward-compatible sync callback. New successful dispatches should call
     * {@link #confirmStarted}; this method derives the case from the unique correlation for older
     * callers and enters the same transactional confirmation path.
     */
    @Transactional
    public void markSync(String id, CaseTask.EngineSync sync, String processInstanceId) {
        if (sync == CaseTask.EngineSync.SYNCED) {
            requireNonBlank(processInstanceId, "processInstanceId");
            findByCorrelation(id).ifPresent(link -> confirmStarted(
                    link.caseId(), id, processInstanceId, OffsetDateTime.now()));
            return;
        }
        if (processInstanceId != null) {
            throw new IllegalArgumentException("A failed start cannot carry an engine identity");
        }
        jdbc.sql("""
                UPDATE CM_LINKED_PROCESS SET ENGINE_SYNC_ = :sync
                WHERE CORRELATION_ID_ = :id AND ENGINE_SYNC_ != 'SYNCED'""")
            .param("sync", sync.name())
            .param("id", id).update();
    }

    public List<LinkedProcessRow> findByCase(String caseId) {
        return jdbc.sql("""
                SELECT ID_, CASE_ID_, PLAN_ITEM_ID_, CORRELATION_ID_, PROC_INST_ID_,
                       PROC_DEF_ID_, PROC_DEF_KEY_, STATE_, ENGINE_SYNC_, IS_CASE_ROOT_
                FROM CM_LINKED_PROCESS WHERE CASE_ID_ = :caseId ORDER BY STARTED_AT_""")
            .param("caseId", caseId)
            .query(LinkedProcessRepository::map)
            .list();
    }

    private static LinkedProcessRow map(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        return new LinkedProcessRow(rs.getString("ID_"), rs.getString("CASE_ID_"),
                rs.getString("PLAN_ITEM_ID_"), rs.getString("CORRELATION_ID_"),
                rs.getString("PROC_INST_ID_"), rs.getString("PROC_DEF_ID_"),
                rs.getString("PROC_DEF_KEY_"),
                rs.getString("STATE_"),
                CaseTask.EngineSync.valueOf(rs.getString("ENGINE_SYNC_")),
                rs.getInt("IS_CASE_ROOT_") == 1);
    }
}
