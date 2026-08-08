package org.casemgmt.repo;

import org.casemgmt.domain.CaseTask;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;

public class LinkedProcessRepository {

    public record LinkedProcessRow(String id, String caseId, String planItemId,
                                   String processInstanceId, String processDefinitionKey, String state,
                                   CaseTask.EngineSync engineSync) {}

    private final JdbcClient jdbc;

    public LinkedProcessRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /**
     * {@code engineSync} mirrors {@code CaseTaskRepository.insert}'s own column: {@code PENDING}
     * when {@code procInstId} is a locally-minted placeholder awaiting the outbox dispatcher's
     * confirmation (Task 12/13 remote mode — see {@code LinkedProcessService#start}), {@code
     * SYNCED} when it is already the engine's real id (embedded/remote-synchronous mode, where
     * the caller learns the real id on the same call and there is nothing left to reconcile).
     */
    public void insert(String id, String caseId, String planItemId, String procInstId,
                       String procDefKey, CaseTask.EngineSync engineSync) {
        jdbc.sql("""
                INSERT INTO CM_LINKED_PROCESS (ID_, CASE_ID_, PLAN_ITEM_ID_, PROC_INST_ID_,
                    PROC_DEF_KEY_, STATE_, ENGINE_SYNC_)
                VALUES (:id, :caseId, :planItemId, :procInstId, :procDefKey, 'ACTIVE', :engineSync)""")
            .param("id", id).param("caseId", caseId).param("planItemId", planItemId)
            .param("procInstId", procInstId).param("procDefKey", procDefKey)
            .param("engineSync", engineSync.name()).update();
    }

    public void markState(String procInstId, String state) {
        jdbc.sql("""
                UPDATE CM_LINKED_PROCESS SET STATE_ = :state,
                    ENDED_AT_ = CASE WHEN :state IN ('COMPLETED','TERMINATED') THEN SYSTIMESTAMP ELSE ENDED_AT_ END
                WHERE PROC_INST_ID_ = :procInstId""")
            .param("state", state).param("procInstId", procInstId).update();
    }

    /**
     * Records the outbox dispatcher's confirmation of a linked process's real engine id (Task
     * 13's {@code EngineCommandDispatcher}, reporting against the {@code correlationId} Task 18's
     * review fixed {@code LinkedProcessService#start} to pass through — see that class's Javadoc
     * for why {@code planItemId} could never serve as this key). Overwrites the locally-minted
     * placeholder {@code PROC_INST_ID_} with the real one; on a {@code FAILED} report {@code
     * processInstanceId} is {@code null} and {@code COALESCE} leaves the placeholder in place
     * while still recording the failure in {@code ENGINE_SYNC_}.
     *
     * <p>{@code WHERE ENGINE_SYNC_ != 'SYNCED'} makes this idempotent the same way {@code
     * CaseTaskRepository.markSync}'s {@code COALESCE(CAMUNDA_TASK_ID_, ...)} is: at-least-once
     * command redelivery (a crash between the engine call succeeding and the command being
     * marked {@code DONE}) can report a confirmation twice, potentially with two different real
     * process instance ids if the engine call itself ran twice. {@code PROC_INST_ID_} is NOT
     * NULL from insert (unlike {@code CAMUNDA_TASK_ID_}), so it cannot serve as its own
     * first-writer-wins sentinel the way {@code CaseTaskRepository} uses {@code
     * COALESCE(CAMUNDA_TASK_ID_, ...)} — {@code ENGINE_SYNC_} plays that role instead: once a
     * report has flipped it to {@code SYNCED}, this WHERE clause makes every later call a no-op.
     */
    public void markSync(String id, CaseTask.EngineSync sync, String processInstanceId) {
        jdbc.sql("""
                UPDATE CM_LINKED_PROCESS SET ENGINE_SYNC_ = :sync,
                    PROC_INST_ID_ = COALESCE(:processInstanceId, PROC_INST_ID_)
                WHERE ID_ = :id AND ENGINE_SYNC_ != 'SYNCED'""")
            .param("sync", sync.name()).param("processInstanceId", processInstanceId)
            .param("id", id).update();
    }

    public List<LinkedProcessRow> findByCase(String caseId) {
        return jdbc.sql("""
                SELECT ID_, CASE_ID_, PLAN_ITEM_ID_, PROC_INST_ID_, PROC_DEF_KEY_, STATE_, ENGINE_SYNC_
                FROM CM_LINKED_PROCESS WHERE CASE_ID_ = :caseId ORDER BY STARTED_AT_""")
            .param("caseId", caseId)
            .query((rs, n) -> new LinkedProcessRow(rs.getString("ID_"), rs.getString("CASE_ID_"),
                    rs.getString("PLAN_ITEM_ID_"), rs.getString("PROC_INST_ID_"),
                    rs.getString("PROC_DEF_KEY_"), rs.getString("STATE_"),
                    CaseTask.EngineSync.valueOf(rs.getString("ENGINE_SYNC_"))))
            .list();
    }
}
