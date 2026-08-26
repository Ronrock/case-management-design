package org.casemgmt.projection;

import org.casemgmt.domain.CaseIds;
import org.casemgmt.repo.JsonCodec;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.Locale;

/** Idempotent Oracle projection upserts; no engine types enter core. */
public class JdbcCaseProjectionPort implements CaseProjectionPort {

    private final JdbcClient jdbc;
    private final CaseCompletionPublisher completionPublisher;

    public JdbcCaseProjectionPort(JdbcClient jdbc) {
        this(jdbc, CaseCompletionPublisher.none());
    }

    public JdbcCaseProjectionPort(JdbcClient jdbc, CaseCompletionPublisher completionPublisher) {
        this.jdbc = jdbc;
        this.completionPublisher = completionPublisher;
    }

    @Override
    public void observe(TaskObservation observation) {
        String event = observation.eventName() == null ? "" :
                observation.eventName().toLowerCase(Locale.ROOT);
        String planState = event.equals("complete") || event.equals("delete")
                ? (event.equals("complete") ? "COMPLETED" : "TERMINATED") : "ACTIVE";
        String taskState = event.equals("complete") ? "COMPLETED"
                : event.equals("delete") ? "TERMINATED"
                : observation.assignee() == null ? "OPEN" : "CLAIMED";
        jdbc.sql("""
                MERGE INTO CM_PLAN_ITEM target
                USING (SELECT :activityInstanceId AS ENGINE_ACTIVITY_ID_ FROM dual
                       WHERE EXISTS (
                           SELECT 1 FROM CM_CASE c
                           JOIN CM_CASE_DEF d ON d.ID_ = c.CASE_DEF_ID_
                           WHERE c.ID_ = :caseId AND d.ORCHESTRATION_MODE_ = 'BPMN')) source
                ON (target.ENGINE_ACTIVITY_ID_ = source.ENGINE_ACTIVITY_ID_)
                WHEN MATCHED THEN UPDATE SET target.STATE_ = :state, target.NAME_ = :name,
                    target.UPDATED_AT_ = :projectedAt, target.LAST_ENGINE_UPDATE_AT_ = :engineAt,
                    target.LAST_PROJECTED_AT_ = :projectedAt, target.PROJECTION_STATUS_ = 'CURRENT',
                    target.ENDED_AT_ = CASE WHEN :state IN ('COMPLETED','TERMINATED')
                                           THEN :projectedAt ELSE target.ENDED_AT_ END
                WHEN NOT MATCHED THEN INSERT
                    (ID_, CASE_ID_, PI_DEF_ID_, TYPE_, NAME_, STATE_, AD_HOC_, REPETITION_NO_,
                     ENGINE_ACTIVITY_ID_, VERSION_, CREATED_AT_, UPDATED_AT_,
                     PROJECTION_STATUS_, LAST_ENGINE_UPDATE_AT_, LAST_PROJECTED_AT_)
                VALUES (:id, :caseId, NULL, 'HUMAN_TASK', :name, :state, 0, 1,
                        :activityInstanceId, 0, :projectedAt, :projectedAt,
                        'CURRENT', :engineAt, :projectedAt)""")
                .param("activityInstanceId", observation.activityInstanceId())
                .param("state", planState).param("name", observation.name())
                .param("projectedAt", observation.observedAt())
                .param("engineAt", observation.engineUpdatedAt())
                .param("id", CaseIds.newId()).param("caseId", observation.caseId()).update();

        jdbc.sql("""
                MERGE INTO CM_TASK target
                USING (SELECT :engineTaskId AS CAMUNDA_TASK_ID_ FROM dual
                       WHERE EXISTS (
                           SELECT 1 FROM CM_CASE c
                           JOIN CM_CASE_DEF d ON d.ID_ = c.CASE_DEF_ID_
                           WHERE c.ID_ = :caseId AND d.ORCHESTRATION_MODE_ = 'BPMN')) source
                ON (target.CAMUNDA_TASK_ID_ = source.CAMUNDA_TASK_ID_)
                WHEN MATCHED THEN UPDATE SET target.STATE_ = :state, target.ASSIGNEE_ = :assignee,
                    target.UPDATED_AT_ = :projectedAt, target.LAST_ENGINE_UPDATE_AT_ = :engineAt,
                    target.LAST_PROJECTED_AT_ = :projectedAt, target.PROJECTION_STATUS_ = 'CURRENT',
                    target.COMPLETED_AT_ = CASE WHEN :state IN ('COMPLETED','TERMINATED')
                                               THEN :projectedAt ELSE target.COMPLETED_AT_ END
                WHEN NOT MATCHED THEN INSERT
                    (ID_, CASE_ID_, PLAN_ITEM_ID_, CAMUNDA_TASK_ID_, NAME_, STATE_, ASSIGNEE_,
                     CAND_GROUPS_JSON_, FORM_KEY_, PRIORITY_, DUE_AT_, ENGINE_SYNC_, VERSION_,
                     CREATED_AT_, UPDATED_AT_, PROJECTION_STATUS_, LAST_ENGINE_UPDATE_AT_, LAST_PROJECTED_AT_)
                VALUES (:id, :caseId,
                        (SELECT ID_ FROM CM_PLAN_ITEM WHERE ENGINE_ACTIVITY_ID_ = :activityInstanceId),
                        :engineTaskId, :name, :state, :assignee, :groups, :formKey, :priority,
                        :dueAt, 'SYNCED', 0, :projectedAt, :projectedAt, 'CURRENT', :engineAt,
                        :projectedAt)""")
                .param("engineTaskId", observation.engineTaskId())
                .param("activityInstanceId", observation.activityInstanceId())
                .param("state", taskState).param("assignee", observation.assignee())
                .param("projectedAt", observation.observedAt())
                .param("engineAt", observation.engineUpdatedAt())
                .param("id", CaseIds.newId()).param("caseId", observation.caseId())
                .param("name", observation.name())
                .param("groups", JsonCodec.toJson(observation.candidateGroups() == null
                        ? java.util.List.of() : observation.candidateGroups()))
                .param("formKey", observation.formKey()).param("priority", observation.priority())
                .param("dueAt", observation.dueAt()).update();
    }

    @Override
    public void observe(ActivityObservation observation) {
        String event = observation.eventName() == null ? ""
                : observation.eventName().toLowerCase(Locale.ROOT);
        String state = observation.kind() == ActivityObservation.Kind.MILESTONE || event.equals("end")
                ? "COMPLETED" : event.equals("delete") ? "TERMINATED" : "ACTIVE";
        String type = observation.kind() == ActivityObservation.Kind.MILESTONE ? "MILESTONE" : "STAGE";
        jdbc.sql("""
                MERGE INTO CM_PLAN_ITEM target
                USING (SELECT :activityInstanceId AS ENGINE_ACTIVITY_ID_ FROM dual
                       WHERE EXISTS (
                           SELECT 1 FROM CM_CASE c
                           JOIN CM_CASE_DEF d ON d.ID_ = c.CASE_DEF_ID_
                           WHERE c.ID_ = :caseId AND d.ORCHESTRATION_MODE_ = 'BPMN')) source
                ON (target.ENGINE_ACTIVITY_ID_ = source.ENGINE_ACTIVITY_ID_)
                WHEN MATCHED THEN UPDATE SET target.STATE_ = :state, target.NAME_ = :name,
                    target.UPDATED_AT_ = :projectedAt, target.LAST_ENGINE_UPDATE_AT_ = :engineAt,
                    target.LAST_PROJECTED_AT_ = :projectedAt, target.PROJECTION_STATUS_ = 'CURRENT',
                    target.ENDED_AT_ = CASE WHEN :state IN ('COMPLETED','TERMINATED')
                                           THEN :projectedAt ELSE target.ENDED_AT_ END
                WHEN NOT MATCHED THEN INSERT
                    (ID_, CASE_ID_, PI_DEF_ID_, TYPE_, NAME_, STATE_, AD_HOC_, REPETITION_NO_,
                     ENGINE_ACTIVITY_ID_, VERSION_, CREATED_AT_, UPDATED_AT_, ENDED_AT_,
                     PROJECTION_STATUS_, LAST_ENGINE_UPDATE_AT_, LAST_PROJECTED_AT_)
                VALUES (:id, :caseId, :activityId, :type, :name, :state, 0, 1,
                        :activityInstanceId, 0, :projectedAt, :projectedAt,
                        CASE WHEN :state IN ('COMPLETED','TERMINATED') THEN :projectedAt END,
                        'CURRENT', :engineAt, :projectedAt)""")
                .param("activityInstanceId", observation.activityInstanceId())
                .param("activityId", observation.activityId()).param("type", type)
                .param("state", state).param("name", observation.name())
                .param("projectedAt", observation.observedAt())
                .param("engineAt", observation.engineUpdatedAt())
                .param("id", CaseIds.newId()).param("caseId", observation.caseId()).update();

        if (observation.kind() == ActivityObservation.Kind.MILESTONE) {
            jdbc.sql("""
                    MERGE INTO CM_MILESTONE target
                    USING (SELECT ID_, CASE_ID_, NAME_ FROM CM_PLAN_ITEM
                           WHERE ENGINE_ACTIVITY_ID_ = :activityInstanceId) source
                    ON (target.PLAN_ITEM_ID_ = source.ID_)
                    WHEN MATCHED THEN UPDATE SET target.ACHIEVED_ = 1,
                        target.ACHIEVED_AT_ = COALESCE(target.ACHIEVED_AT_, :projectedAt),
                        target.ACHIEVED_BY_ = COALESCE(target.ACHIEVED_BY_, 'engine')
                    WHEN NOT MATCHED THEN INSERT
                        (ID_, CASE_ID_, PLAN_ITEM_ID_, NAME_, ACHIEVED_, ACHIEVED_AT_, ACHIEVED_BY_)
                    VALUES (:id, source.CASE_ID_, source.ID_, source.NAME_, 1, :projectedAt, 'engine')""")
                    .param("activityInstanceId", observation.activityInstanceId())
                    .param("projectedAt", observation.observedAt())
                    .param("id", CaseIds.newId()).update();
        }
    }

    @Override
    public void observe(ProcessCompletionObservation observation) {
        String terminalState = "cancelled".equalsIgnoreCase(observation.endState())
                ? "CANCELLED" : "CLOSED";
        jdbc.sql("""
                UPDATE CM_LINKED_PROCESS SET STATE_ = :processState, ENDED_AT_ = :projectedAt,
                    PROJECTION_STATUS_ = 'CURRENT', LAST_ENGINE_UPDATE_AT_ = :engineAt,
                    LAST_PROJECTED_AT_ = :projectedAt
                WHERE PROC_INST_ID_ = :processInstanceId""")
                .param("processState", terminalState.equals("CLOSED") ? "COMPLETED" : "TERMINATED")
                .param("projectedAt", observation.observedAt())
                .param("engineAt", observation.engineUpdatedAt())
                .param("processInstanceId", observation.processInstanceId()).update();

        // A child/called/ad-hoc process ending must never close its case. Only the process
        // pinned as ROOT_PROC_INST_ID_ owns case completion and the discretionary-work sweep.
        String rootGuard = " " + """
                EXISTS (SELECT 1 FROM CM_CASE root_case
                        WHERE root_case.ID_ = :caseId
                          AND root_case.ROOT_PROC_INST_ID_ = :processInstanceId
                          AND root_case.STATE_ = 'ACTIVE')""";
        jdbc.sql("""
                UPDATE CM_TASK SET STATE_ = 'TERMINATED', COMPLETED_AT_ = :projectedAt,
                    UPDATED_AT_ = :projectedAt, VERSION_ = VERSION_ + 1,
                    PROJECTION_STATUS_ = 'CURRENT', LAST_ENGINE_UPDATE_AT_ = :engineAt,
                    LAST_PROJECTED_AT_ = :projectedAt
                WHERE CASE_ID_ = :caseId AND STATE_ IN ('OPEN','CLAIMED') AND """ + rootGuard)
                .param("projectedAt", observation.observedAt())
                .param("engineAt", observation.engineUpdatedAt())
                .param("caseId", observation.caseId())
                .param("processInstanceId", observation.processInstanceId()).update();
        jdbc.sql("""
                UPDATE CM_PLAN_ITEM SET STATE_ = 'TERMINATED',
                    TERM_REASON_ = 'root process completed', ENDED_AT_ = :projectedAt,
                    UPDATED_AT_ = :projectedAt, VERSION_ = VERSION_ + 1,
                    PROJECTION_STATUS_ = 'CURRENT', LAST_ENGINE_UPDATE_AT_ = :engineAt,
                    LAST_PROJECTED_AT_ = :projectedAt
                WHERE CASE_ID_ = :caseId
                  AND STATE_ IN ('AVAILABLE','ENABLED','ACTIVE') AND """ + rootGuard)
                .param("projectedAt", observation.observedAt())
                .param("engineAt", observation.engineUpdatedAt())
                .param("caseId", observation.caseId())
                .param("processInstanceId", observation.processInstanceId()).update();
        jdbc.sql("""
                UPDATE CM_LINKED_PROCESS SET STATE_ = 'TERMINATED', ENDED_AT_ = :projectedAt,
                    PROJECTION_STATUS_ = 'CURRENT', LAST_ENGINE_UPDATE_AT_ = :engineAt,
                    LAST_PROJECTED_AT_ = :projectedAt
                WHERE CASE_ID_ = :caseId AND STATE_ = 'ACTIVE'
                  AND (PROC_INST_ID_ IS NULL OR PROC_INST_ID_ <> :processInstanceId) AND """ + rootGuard)
                .param("projectedAt", observation.observedAt())
                .param("engineAt", observation.engineUpdatedAt())
                .param("caseId", observation.caseId())
                .param("processInstanceId", observation.processInstanceId()).update();
        int closed = jdbc.sql("""
                UPDATE CM_CASE SET STATE_ = :state, CLOSED_AT_ = :projectedAt,
                    UPDATED_AT_ = :projectedAt, VERSION_ = VERSION_ + 1,
                    PROJECTION_STATUS_ = 'CURRENT', LAST_ENGINE_UPDATE_AT_ = :engineAt,
                    LAST_PROJECTED_AT_ = :projectedAt
                WHERE ID_ = :caseId AND ROOT_PROC_INST_ID_ = :processInstanceId
                    AND STATE_ = 'ACTIVE'""")
                .param("state", terminalState).param("projectedAt", observation.observedAt())
                .param("engineAt", observation.engineUpdatedAt())
                .param("processInstanceId", observation.processInstanceId())
                .param("caseId", observation.caseId()).update();
        if (closed == 1) {
            completionPublisher.publish(observation.caseId(), terminalState,
                    observation.observedAt());
        }
    }
}
