package org.casemgmt.repo;

import org.casemgmt.event.CaseEvent;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Append-only log backing CM_EVENT (spec §6.1/§6.2). SEQ_ (from CM_EVENT_SEQ) is the
 * monotonic cursor {@link #after} and {@link #forCase} paginate on.
 */
public class EventRepository {

    public record StoredEvent(long seq, CaseEvent event) {}

    private final JdbcClient jdbc;

    public EventRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public long append(CaseEvent e) {
        long seq = jdbc.sql("SELECT CM_EVENT_SEQ.NEXTVAL FROM DUAL").query(Long.class).single();
        jdbc.sql("""
                INSERT INTO CM_EVENT (SEQ_, ID_, SOURCE_, TYPE_, SUBJECT_, TENANT_ID_, TIME_, DATA_JSON_)
                VALUES (:seq, :id, :source, :type, :subject, :tenant, :time, :data)""")
            .param("seq", seq).param("id", e.id()).param("source", e.source())
            .param("type", e.type()).param("subject", e.subject()).param("tenant", e.tenantId())
            .param("time", e.time()).param("data", JsonCodec.toJson(e.data()))
            .update();
        return seq;
    }

    public List<StoredEvent> after(long cursor, int limit) {
        return jdbc.sql("""
                SELECT SEQ_, ID_, SOURCE_, TYPE_, SUBJECT_, TENANT_ID_, TIME_, DATA_JSON_
                FROM CM_EVENT WHERE SEQ_ > :cursor ORDER BY SEQ_ FETCH FIRST :limit ROWS ONLY""")
            .param("cursor", cursor).param("limit", limit)
            .query(EventRepository::map).list();
    }

    public List<StoredEvent> forCase(String caseId, long cursor, int limit) {
        return jdbc.sql("""
                SELECT SEQ_, ID_, SOURCE_, TYPE_, SUBJECT_, TENANT_ID_, TIME_, DATA_JSON_
                FROM CM_EVENT WHERE SUBJECT_ = :caseId AND SEQ_ > :cursor
                ORDER BY SEQ_ FETCH FIRST :limit ROWS ONLY""")
            .param("caseId", caseId).param("cursor", cursor).param("limit", limit)
            .query(EventRepository::map).list();
    }

    private static StoredEvent map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new StoredEvent(rs.getLong("SEQ_"), new CaseEvent(
                rs.getString("ID_"), rs.getString("SOURCE_"), rs.getString("TYPE_"),
                rs.getString("SUBJECT_"), rs.getString("TENANT_ID_"),
                rs.getObject("TIME_", OffsetDateTime.class),
                JsonCodec.toMap(rs.getString("DATA_JSON_"))));
    }
}
