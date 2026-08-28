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
    public void assertEntityOwnership(ProjectionEntityIdentity identity) {
        if (identity.kind() == ProjectionEntityIdentity.Kind.USER_TASK) {
            assertOwner("CM_TASK", "CAMUNDA_TASK_ID_", identity.entityId(), identity);
            assertOwner("CM_PLAN_ITEM", "ENGINE_ACTIVITY_ID_",
                    identity.relatedActivityInstanceId(), identity);
            return;
        }
        assertOwner("CM_PLAN_ITEM", "ENGINE_ACTIVITY_ID_", identity.entityId(), identity);
    }

    private void assertOwner(String table, String idColumn, String engineEntityId,
                             ProjectionEntityIdentity expected) {
        if (engineEntityId == null) return;
        java.util.List<EntityOwner> owners = jdbc.sql("SELECT CASE_ID_, PROC_INST_ID_ FROM "
                        + table + " WHERE " + idColumn + " = :entityId")
                .param("entityId", engineEntityId)
                .query((rs, row) -> new EntityOwner(
                        rs.getString("CASE_ID_"), rs.getString("PROC_INST_ID_")))
                .list();
        boolean mismatch = owners.stream().anyMatch(owner ->
                !java.util.Objects.equals(owner.caseId(), expected.caseId())
                        || processOwnershipMismatch(owner.processInstanceId(),
                                expected.processInstanceId()));
        if (mismatch) {
            throw new SecurityException("Engine entity '" + engineEntityId
                    + "' is owned by another case or process");
        }
    }

    private static boolean processOwnershipMismatch(String actual, String expected) {
        return actual != null && expected != null && !actual.equals(expected);
    }

    @Override
    public void observe(TaskObservation observation) {
        assertEntityOwnership(new ProjectionEntityIdentity(observation.caseId(),
                observation.processInstanceId(), ProjectionEntityIdentity.Kind.USER_TASK,
                observation.engineTaskId(), observation.activityInstanceId()));
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
                ON (target.ENGINE_ACTIVITY_ID_ = source.ENGINE_ACTIVITY_ID_
                    AND target.CASE_ID_ = :caseId
                    AND (:processInstanceId IS NULL
                      OR target.PROC_INST_ID_ IS NULL
                      OR target.PROC_INST_ID_ = :processInstanceId))
                WHEN MATCHED THEN UPDATE SET target.STATE_ = :state, target.NAME_ = :name,
                    target.PROC_INST_ID_ = COALESCE(target.PROC_INST_ID_, :processInstanceId),
                    target.UPDATED_AT_ = :projectedAt, target.LAST_ENGINE_UPDATE_AT_ = :engineAt,
                    target.LAST_PROJECTED_AT_ = :projectedAt, target.PROJECTION_STATUS_ = 'CURRENT',
                    target.ENDED_AT_ = CASE WHEN :state IN ('COMPLETED','TERMINATED')
                                           THEN :projectedAt ELSE target.ENDED_AT_ END
                WHEN NOT MATCHED THEN INSERT
                    (ID_, CASE_ID_, PI_DEF_ID_, TYPE_, NAME_, STATE_, AD_HOC_, REPETITION_NO_,
                     ENGINE_ACTIVITY_ID_, PROC_INST_ID_, VERSION_, CREATED_AT_, UPDATED_AT_,
                     PROJECTION_STATUS_, LAST_ENGINE_UPDATE_AT_, LAST_PROJECTED_AT_)
                VALUES (:id, :caseId, NULL, 'HUMAN_TASK', :name, :state, 0, 1,
                        :activityInstanceId, :processInstanceId, 0, :projectedAt, :projectedAt,
                        'CURRENT', :engineAt, :projectedAt)""")
                .param("activityInstanceId", observation.activityInstanceId())
                .param("processInstanceId", observation.processInstanceId())
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
                ON (target.CAMUNDA_TASK_ID_ = source.CAMUNDA_TASK_ID_
                    AND target.CASE_ID_ = :caseId
                    AND (:processInstanceId IS NULL
                      OR target.PROC_INST_ID_ IS NULL
                      OR target.PROC_INST_ID_ = :processInstanceId))
                WHEN MATCHED THEN UPDATE SET target.STATE_ = :state, target.ASSIGNEE_ = :assignee,
                    target.PROC_INST_ID_ = COALESCE(target.PROC_INST_ID_, :processInstanceId),
                    target.UPDATED_AT_ = :projectedAt, target.LAST_ENGINE_UPDATE_AT_ = :engineAt,
                    target.LAST_PROJECTED_AT_ = :projectedAt, target.PROJECTION_STATUS_ = 'CURRENT',
                    target.COMPLETED_AT_ = CASE WHEN :state IN ('COMPLETED','TERMINATED')
                                               THEN :projectedAt ELSE target.COMPLETED_AT_ END
                WHEN NOT MATCHED THEN INSERT
                    (ID_, CASE_ID_, PLAN_ITEM_ID_, CAMUNDA_TASK_ID_, NAME_, STATE_, ASSIGNEE_,
                     CAND_GROUPS_JSON_, FORM_KEY_, PRIORITY_, DUE_AT_, ENGINE_SYNC_, PROC_INST_ID_, VERSION_,
                     CREATED_AT_, UPDATED_AT_, PROJECTION_STATUS_, LAST_ENGINE_UPDATE_AT_, LAST_PROJECTED_AT_)
                VALUES (:id, :caseId,
                        (SELECT ID_ FROM CM_PLAN_ITEM WHERE ENGINE_ACTIVITY_ID_ = :activityInstanceId
                           AND CASE_ID_ = :caseId
                           AND (:processInstanceId IS NULL OR PROC_INST_ID_ = :processInstanceId)),
                        :engineTaskId, :name, :state, :assignee, :groups, :formKey, :priority,
                        :dueAt, 'SYNCED', :processInstanceId, 0, :projectedAt, :projectedAt, 'CURRENT', :engineAt,
                        :projectedAt)""")
                .param("engineTaskId", observation.engineTaskId())
                .param("processInstanceId", observation.processInstanceId())
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
        assertEntityOwnership(new ProjectionEntityIdentity(observation.caseId(),
                observation.processInstanceId(),
                observation.kind() == ActivityObservation.Kind.MILESTONE
                        ? ProjectionEntityIdentity.Kind.MILESTONE
                        : ProjectionEntityIdentity.Kind.ACTIVITY,
                observation.activityInstanceId(), null));
        String event = observation.eventName() == null ? ""
                : observation.eventName().toLowerCase(Locale.ROOT);
        String state = event.equals("end") ? "COMPLETED"
                : event.equals("delete") ? "TERMINATED" : "ACTIVE";
        String type = observation.kind() == ActivityObservation.Kind.MILESTONE ? "MILESTONE" : "STAGE";
        jdbc.sql("""
                MERGE INTO CM_PLAN_ITEM target
                USING (SELECT :activityInstanceId AS ENGINE_ACTIVITY_ID_ FROM dual
                       WHERE EXISTS (
                           SELECT 1 FROM CM_CASE c
                           JOIN CM_CASE_DEF d ON d.ID_ = c.CASE_DEF_ID_
                           WHERE c.ID_ = :caseId AND d.ORCHESTRATION_MODE_ = 'BPMN')) source
                ON (target.ENGINE_ACTIVITY_ID_ = source.ENGINE_ACTIVITY_ID_
                    AND target.CASE_ID_ = :caseId
                    AND (:processInstanceId IS NULL
                      OR target.PROC_INST_ID_ IS NULL
                      OR target.PROC_INST_ID_ = :processInstanceId))
                WHEN MATCHED THEN UPDATE SET target.STATE_ = :state, target.NAME_ = :name,
                    target.PROC_INST_ID_ = COALESCE(target.PROC_INST_ID_, :processInstanceId),
                    target.UPDATED_AT_ = :projectedAt, target.LAST_ENGINE_UPDATE_AT_ = :engineAt,
                    target.LAST_PROJECTED_AT_ = :projectedAt, target.PROJECTION_STATUS_ = 'CURRENT',
                    target.ENDED_AT_ = CASE WHEN :state IN ('COMPLETED','TERMINATED')
                                           THEN :projectedAt ELSE NULL END
                WHEN NOT MATCHED THEN INSERT
                    (ID_, CASE_ID_, PI_DEF_ID_, TYPE_, NAME_, STATE_, AD_HOC_, REPETITION_NO_,
                     ENGINE_ACTIVITY_ID_, PROC_INST_ID_, VERSION_, CREATED_AT_, UPDATED_AT_, ENDED_AT_,
                     PROJECTION_STATUS_, LAST_ENGINE_UPDATE_AT_, LAST_PROJECTED_AT_)
                VALUES (:id, :caseId, :activityId, :type, :name, :state, 0, 1,
                        :activityInstanceId, :processInstanceId, 0, :projectedAt, :projectedAt,
                        CASE WHEN :state IN ('COMPLETED','TERMINATED') THEN :projectedAt END,
                        'CURRENT', :engineAt, :projectedAt)""")
                .param("activityInstanceId", observation.activityInstanceId())
                .param("processInstanceId", observation.processInstanceId())
                .param("activityId", observation.activityId()).param("type", type)
                .param("state", state).param("name", observation.name())
                .param("projectedAt", observation.observedAt())
                .param("engineAt", observation.engineUpdatedAt())
                .param("id", CaseIds.newId()).param("caseId", observation.caseId()).update();

        if (observation.kind() == ActivityObservation.Kind.MILESTONE) {
            int achieved = event.equals("end") ? 1 : 0;
            jdbc.sql("""
                    MERGE INTO CM_MILESTONE target
                    USING (SELECT ID_, CASE_ID_, NAME_ FROM CM_PLAN_ITEM
                           WHERE ENGINE_ACTIVITY_ID_ = :activityInstanceId
                             AND CASE_ID_ = :caseId
                             AND (:processInstanceId IS NULL
                               OR PROC_INST_ID_ = :processInstanceId)) source
                    ON (target.PLAN_ITEM_ID_ = source.ID_)
                    WHEN MATCHED THEN UPDATE SET target.ACHIEVED_ = :achieved,
                        target.ACHIEVED_AT_ = CASE WHEN :achieved = 1
                            THEN COALESCE(target.ACHIEVED_AT_, :projectedAt) ELSE NULL END,
                        target.ACHIEVED_BY_ = CASE WHEN :achieved = 1
                            THEN COALESCE(target.ACHIEVED_BY_, 'engine') ELSE NULL END
                    WHEN NOT MATCHED THEN INSERT
                        (ID_, CASE_ID_, PLAN_ITEM_ID_, NAME_, ACHIEVED_, ACHIEVED_AT_, ACHIEVED_BY_)
                    VALUES (:id, source.CASE_ID_, source.ID_, source.NAME_, :achieved,
                        CASE WHEN :achieved = 1 THEN :projectedAt END,
                        CASE WHEN :achieved = 1 THEN 'engine' END)""")
                    .param("activityInstanceId", observation.activityInstanceId())
                    .param("caseId", observation.caseId())
                    .param("processInstanceId", observation.processInstanceId())
                    .param("achieved", achieved)
                    .param("projectedAt", observation.observedAt())
                    .param("id", CaseIds.newId()).update();
        }
    }

    @Override
    public void observe(ProcessCompletionObservation observation) {
        observeProcess(observation, true);
    }

    @Override
    public ProcessProjectionResult observeFromHandler(ProcessCompletionObservation observation) {
        return observeProcess(observation, false);
    }

    private ProcessProjectionResult observeProcess(ProcessCompletionObservation observation,
                                                   boolean publishLegacyCompletion) {
        String terminalState = "cancelled".equalsIgnoreCase(observation.endState())
                ? "CANCELLED" : "CLOSED";
        jdbc.sql("""
                UPDATE CM_LINKED_PROCESS SET STATE_ = :processState, ENDED_AT_ = :projectedAt,
                    PROJECTION_STATUS_ = 'CURRENT', LAST_ENGINE_UPDATE_AT_ = :engineAt,
                    LAST_PROJECTED_AT_ = :projectedAt
                WHERE CASE_ID_ = :caseId AND PROC_INST_ID_ = :processInstanceId""")
                .param("processState", terminalState.equals("CLOSED") ? "COMPLETED" : "TERMINATED")
                .param("projectedAt", observation.observedAt())
                .param("engineAt", observation.engineUpdatedAt())
                .param("caseId", observation.caseId())
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
        long caseVersion = jdbc.sql("SELECT VERSION_ FROM CM_CASE WHERE ID_ = :caseId")
                .param("caseId", observation.caseId())
                .query(Long.class)
                .single();
        if (closed == 1 && publishLegacyCompletion) {
            completionPublisher.publish(observation.caseId(), terminalState,
                    observation.observedAt());
        }
        return new ProcessProjectionResult(closed == 1, caseVersion);
    }

    private record EntityOwner(String caseId, String processInstanceId) { }
}
