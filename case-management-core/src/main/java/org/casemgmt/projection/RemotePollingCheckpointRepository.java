package org.casemgmt.projection;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.OffsetDateTime;
import java.util.Optional;

/** Durable checkpoint and health state for overlapping remote observation polls. */
public final class RemotePollingCheckpointRepository {

    public record Checkpoint(String name, OffsetDateTime watermark, ProjectionStatus status,
                             String lastError, OffsetDateTime lastSuccessAt) { }

    private final JdbcClient jdbc;

    public RemotePollingCheckpointRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Checkpoint> find(String name) {
        return jdbc.sql("""
                SELECT NAME_, WATERMARK_, STATUS_, LAST_ERROR_, LAST_SUCCESS_AT_
                FROM CM_ENGINE_POLL_CHECKPOINT WHERE NAME_ = :name""")
                .param("name", name)
                .query((rs, n) -> new Checkpoint(rs.getString("NAME_"),
                        rs.getObject("WATERMARK_", OffsetDateTime.class),
                        ProjectionStatus.valueOf(rs.getString("STATUS_")),
                        rs.getString("LAST_ERROR_"),
                        rs.getObject("LAST_SUCCESS_AT_", OffsetDateTime.class)))
                .optional();
    }

    public void succeeded(String name, OffsetDateTime watermark) {
        merge(name, watermark, ProjectionStatus.CURRENT, null, watermark);
        jdbc.sql("""
                UPDATE CM_CASE SET PROJECTION_STATUS_ = 'CURRENT'
                WHERE STATE_ = 'ACTIVE' AND PROJECTION_STATUS_ = 'STALE'""").update();
    }

    public void failed(String name, String error) {
        OffsetDateTime current = find(name).map(Checkpoint::watermark).orElse(OffsetDateTime.now());
        merge(name, current, ProjectionStatus.STALE, truncate(error), null);
        jdbc.sql("""
                UPDATE CM_CASE SET PROJECTION_STATUS_ = 'STALE'
                WHERE STATE_ = 'ACTIVE' AND ROOT_PROC_INST_ID_ IS NOT NULL""").update();
        jdbc.sql("""
                UPDATE CM_PLAN_ITEM SET PROJECTION_STATUS_ = 'STALE'
                WHERE CASE_ID_ IN (SELECT ID_ FROM CM_CASE WHERE PROJECTION_STATUS_ = 'STALE')
                  AND STATE_ IN ('AVAILABLE','ENABLED','ACTIVE')""").update();
        jdbc.sql("""
                UPDATE CM_TASK SET PROJECTION_STATUS_ = 'STALE'
                WHERE CASE_ID_ IN (SELECT ID_ FROM CM_CASE WHERE PROJECTION_STATUS_ = 'STALE')
                  AND STATE_ IN ('OPEN','CLAIMED')""").update();
    }

    private void merge(String name, OffsetDateTime watermark, ProjectionStatus status,
                       String error, OffsetDateTime successAt) {
        jdbc.sql("""
                MERGE INTO CM_ENGINE_POLL_CHECKPOINT target
                USING (SELECT :name AS NAME_ FROM dual) source ON (target.NAME_ = source.NAME_)
                WHEN MATCHED THEN UPDATE SET WATERMARK_ = :watermark, STATUS_ = :status,
                    LAST_ERROR_ = :error,
                    LAST_SUCCESS_AT_ = COALESCE(
                        CAST(:successAt AS TIMESTAMP WITH TIME ZONE), target.LAST_SUCCESS_AT_),
                    UPDATED_AT_ = SYSTIMESTAMP
                WHEN NOT MATCHED THEN INSERT
                    (NAME_, WATERMARK_, STATUS_, LAST_ERROR_, LAST_SUCCESS_AT_, UPDATED_AT_)
                VALUES (:name, :watermark, :status, :error,
                    CAST(:successAt AS TIMESTAMP WITH TIME ZONE), SYSTIMESTAMP)""")
                .param("name", name).param("watermark", watermark)
                .param("status", status.name()).param("error", error)
                .param("successAt", successAt).update();
    }

    private static String truncate(String value) {
        return value == null || value.length() <= 1990 ? value : value.substring(0, 1990);
    }
}
