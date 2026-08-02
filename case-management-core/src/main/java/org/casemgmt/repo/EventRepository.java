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

    /**
     * KNOWN, ACCEPTED LIMITATION (Task 14 review, human-ruled DOCUMENT-DON'T-FIX): under
     * concurrent writers, this cursor can permanently skip events, and a consumer polling it
     * has no way to detect that a gap occurred.
     *
     * <p>{@code SEQ_} comes from {@code CM_EVENT_SEQ.NEXTVAL} (see {@link #append}), which hands
     * out numbers the instant it is called — <em>before</em> the row's INSERT commits. Two
     * concurrent transactions can therefore commit their {@code CM_EVENT} rows in the opposite
     * order to the sequence values they were assigned. Concrete interleaving, verified against
     * real Oracle:
     * <pre>
     *   T1: NEXTVAL -&gt; 5, INSERT SEQ_=5 ... (slow: still uncommitted)
     *   T2: NEXTVAL -&gt; 6, INSERT SEQ_=6 ..., COMMIT                 (6 now visible)
     *   Consumer: after(cursor=0, ...) sees only SEQ_=6 (5 not yet committed), advances cursor to 6
     *   T1: COMMIT                                                  (5 now visible, but too late)
     *   Consumer: after(cursor=6, ...) -&gt; WHERE SEQ_ &gt; 6 -&gt; never returns 5, ever
     * </pre>
     * Event 5 is not late — it is gone. There is no gap marker, no missing-sequence exception, no
     * signal of any kind in the result set the consumer can check; {@code after(6, ...)} returning
     * zero or more rows all {@code > 6} is indistinguishable from "nothing new happened yet."
     * {@code CM_EVENT_SEQ} values also never roll back on an aborted transaction, so an ABORTed
     * T1 above leaves the exact same permanent, unobservable hole in the sequence — a consumer
     * cannot tell a lost-commit gap from a rolled-back-transaction gap from "no event was ever
     * assigned that number," because all three look identical from here.
     *
     * <p>This directly undermines design-principles.md Appendix A's "push for speed, pull for
     * correctness" argument: the pull path (this method) is supposed to be the reliable recovery
     * mechanism for a consumer that missed a webhook delivery, precisely because polling is
     * assumed to eventually see everything a push might have dropped. Under concurrent writers it
     * does not — it can drop events itself, silently, with no way for the consumer to know.
     *
     * <p>Deliberately NOT fixed in this PoC (human-ruled): the real fix — a commit-order
     * watermark (e.g. poll {@code WHERE TIME_ < now() - safety_margin} instead of/alongside
     * {@code SEQ_}), a gap-tolerant cursor design, or serializing appends so assignment order
     * matches commit order — is an architectural decision for the production design, not
     * something to improvise inside a repository method. Surfacing this precisely is the PoC
     * doing its job; do not silently "fix" the symptom (e.g. by widening the WHERE clause) without
     * first understanding this whole shape of the problem.
     */
    public List<StoredEvent> after(long cursor, int limit) {
        return jdbc.sql("""
                SELECT SEQ_, ID_, SOURCE_, TYPE_, SUBJECT_, TENANT_ID_, TIME_, DATA_JSON_
                FROM CM_EVENT WHERE SEQ_ > :cursor ORDER BY SEQ_ FETCH FIRST :limit ROWS ONLY""")
            .param("cursor", cursor).param("limit", limit)
            .query(EventRepository::map).list();
    }

    /** Same cursor-gap limitation as {@link #after} — see its Javadoc. */
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
