package org.casemgmt.repo;

import org.casemgmt.observation.ObservationCursor;
import org.casemgmt.observation.ObservationStream;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

/** Durable, monotonic cursor per tenant and remote history stream. */
public final class ObservationCheckpointRepository {
    private final JdbcClient jdbc;

    public ObservationCheckpointRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ObservationCursor> find(String tenantId, ObservationStream stream) {
        return jdbc.sql("""
                SELECT EVENT_AT_, EVENT_ID_ FROM CM_REMOTE_OBS_CHECKPOINT
                WHERE TENANT_ID_ = :tenantId AND STREAM_ = :stream""")
                .param("tenantId", tenant(tenantId)).param("stream", stream.name())
                .query((rs, row) -> new ObservationCursor(
                        rs.getObject("EVENT_AT_", OffsetDateTime.class).toInstant(),
                        rs.getString("EVENT_ID_")))
                .optional();
    }

    /** Oldest cursor is the safe lower bound when one remote history endpoint serves tenants. */
    public Optional<ObservationCursor> findOldest(ObservationStream stream) {
        return jdbc.sql("""
                SELECT EVENT_AT_, EVENT_ID_ FROM (
                    SELECT EVENT_AT_, EVENT_ID_ FROM CM_REMOTE_OBS_CHECKPOINT
                    WHERE STREAM_ = :stream ORDER BY EVENT_AT_, EVENT_ID_
                ) WHERE ROWNUM = 1""")
                .param("stream", stream.name())
                .query((rs, row) -> new ObservationCursor(
                        rs.getObject("EVENT_AT_", OffsetDateTime.class).toInstant(),
                        rs.getString("EVENT_ID_")))
                .optional();
    }

    /** Advances only after the caller has durably stored the complete page. */
    public void advance(String tenantId, ObservationStream stream, ObservationCursor cursor) {
        OffsetDateTime timestamp = OffsetDateTime.ofInstant(cursor.timestamp(), ZoneOffset.UTC);
        jdbc.sql("""
                MERGE INTO CM_REMOTE_OBS_CHECKPOINT target
                USING (SELECT :tenantId AS TENANT_ID_, :stream AS STREAM_ FROM dual) source
                ON (target.TENANT_ID_ = source.TENANT_ID_ AND target.STREAM_ = source.STREAM_)
                WHEN MATCHED THEN UPDATE SET EVENT_AT_ = :eventAt, EVENT_ID_ = :eventId,
                    UPDATED_AT_ = SYSTIMESTAMP
                  WHERE target.EVENT_AT_ < CAST(:eventAt AS TIMESTAMP WITH TIME ZONE)
                     OR (target.EVENT_AT_ = CAST(:eventAt AS TIMESTAMP WITH TIME ZONE)
                         AND target.EVENT_ID_ < :eventId)
                WHEN NOT MATCHED THEN INSERT (TENANT_ID_, STREAM_, EVENT_AT_, EVENT_ID_, UPDATED_AT_)
                VALUES (:tenantId, :stream, CAST(:eventAt AS TIMESTAMP WITH TIME ZONE),
                        :eventId, SYSTIMESTAMP)""")
                .param("tenantId", tenant(tenantId)).param("stream", stream.name())
                .param("eventAt", timestamp).param("eventId", cursor.id()).update();
    }

    private static String tenant(String tenantId) {
        return tenantId == null || tenantId.isBlank() ? "__default__" : tenantId;
    }
}
