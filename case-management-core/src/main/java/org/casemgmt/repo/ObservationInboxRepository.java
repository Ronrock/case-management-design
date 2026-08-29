package org.casemgmt.repo;

import org.casemgmt.observation.ObservationEnvelope;
import org.casemgmt.observation.ObservationStream;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Transaction-participating durable inbox. Duplicate remote windows are harmless. */
public final class ObservationInboxRepository {
    private final JdbcClient jdbc;
    public ObservationInboxRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public boolean enqueue(String tenantId, ObservationStream stream, ObservationEnvelope envelope) {
        try {
            return jdbc.sql("""INSERT INTO CM_REMOTE_OBS_INBOX
                    (FINGERPRINT_, TENANT_ID_, STREAM_, PAYLOAD_, STATUS_, ATTEMPTS_, CREATED_AT_)
                    VALUES (:fingerprint, :tenant, :stream, :payload, 'PENDING', 0, SYSTIMESTAMP)""")
                    .param("fingerprint", envelope.observation().fingerprint())
                    .param("tenant", tenantId == null || tenantId.isBlank() ? "__default__" : tenantId)
                    .param("stream", stream.name()).param("payload", envelope.payload()).update() == 1;
        } catch (DuplicateKeyException duplicate) { return false; }
    }
}
