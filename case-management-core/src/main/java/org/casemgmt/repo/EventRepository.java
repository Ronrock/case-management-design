package org.casemgmt.repo;

import org.casemgmt.event.CaseEvent;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Append-only log backing CM_EVENT (spec §6.1/§6.2). SEQ_ (from CM_EVENT_SEQ) is the
 * monotonic cursor {@link #after} and {@link #forCase} paginate on.
 *
 * <p>Sequence assignment is serialized through {@code CM_EVENT_APPEND_LOCK}. The lock is held
 * by the caller's transaction until commit, so two transactions cannot assign event sequence
 * numbers and then commit them in the opposite order. That makes the pull cursor safe for
 * recovery consumers while keeping the public cursor shape unchanged.
 */
public class EventRepository {

    public record StoredEvent(long seq, CaseEvent event) {}

    private final JdbcClient jdbc;
    private static final String APPEND_LOCK_NAME = "default";

    public EventRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public long append(CaseEvent e) {
        lockAppendOrder();
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

    private void lockAppendOrder() {
        jdbc.sql("""
                SELECT LOCK_NAME_ FROM CM_EVENT_APPEND_LOCK
                WHERE LOCK_NAME_ = :name FOR UPDATE""")
            .param("name", APPEND_LOCK_NAME)
            .query(String.class)
            .single();
    }

    /**
     * Pull recovery cursor over commit-ordered event sequence numbers. {@link #append} serializes
     * sequence assignment with a transaction-held append lock, so a committed event with a lower
     * {@code SEQ_} cannot appear after a consumer has already advanced past a higher one.
     */
    public List<StoredEvent> after(long cursor, int limit) {
        return jdbc.sql("""
                SELECT SEQ_, ID_, SOURCE_, TYPE_, SUBJECT_, TENANT_ID_, TIME_, DATA_JSON_
                FROM CM_EVENT WHERE SEQ_ > :cursor ORDER BY SEQ_ FETCH FIRST :limit ROWS ONLY""")
            .param("cursor", cursor).param("limit", limit)
            .query(EventRepository::map).list();
    }

    /**
     * {@link #after}, restricted to one tenant.
     *
     * <p>Added by Task 24 fix round 1 (review finding, Critical): {@code GET /case-api/v2/events}
     * used {@link #after} and therefore streamed every CloudEvent in the deployment to any
     * authenticated caller, across every tenant. The filter belongs in the query, not in the
     * controller — filtering after the fetch would still read other tenants' rows and would
     * silently under-fill each page.
     *
     * <p>Global events ({@code TENANT_ID_ IS NULL}) are included for every tenant-scoped feed:
     * those rows are intentionally not owned by one tenant, and hiding them from every caller
     * would make pull recovery impossible for platform-level events.
     */
    public List<StoredEvent> afterForTenant(String tenantId, long cursor, int limit) {
        return jdbc.sql("""
                SELECT SEQ_, ID_, SOURCE_, TYPE_, SUBJECT_, TENANT_ID_, TIME_, DATA_JSON_
                FROM CM_EVENT
                WHERE SEQ_ > :cursor
                  AND (TENANT_ID_ IS NULL OR TENANT_ID_ = :tenant)
                ORDER BY SEQ_ FETCH FIRST :limit ROWS ONLY""")
            .param("tenant", tenantId).param("cursor", cursor).param("limit", limit)
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

    /**
     * Largest {@code IN} list this repository will put in one statement.
     *
     * <p><b>The famous "Oracle caps an IN list at 1000 expressions (ORA-01795)" is NOT the limit
     * this build runs against, and the number here was checked rather than assumed.</b> Oracle
     * 23ai raised that ceiling to 65,535, and measurement against the real container agrees: an
     * unchunked 1,500-bind list succeeds, and a 70,000-bind one fails (as a
     * {@code BadSqlGrammarException} out of the driver, not a recognisable ORA-01795). 500 is
     * therefore conservative by two orders of magnitude, which is deliberate — it keeps each
     * statement's bind count and parse cost modest, and it stays correct if this ever runs
     * against a pre-23ai database where the 1000 limit does apply.
     *
     * <p>Recorded this precisely because the first version of this constant asserted the 1000
     * figure from memory, and the test written to prove the chunking used 1,500 ids — which
     * passes with the chunking stripped out. That test proved nothing until it was resized to
     * cross the real threshold. See
     * {@code WebhookDispatcherTest.bySeqsChunksSoAnOverLongInListCannotOverflowTheStatement}.
     */
    private static final int MAX_IN_LIST = 500;

    /**
     * Events by exact sequence number, for callers holding a set of {@code EVENT_SEQ_} references
     * rather than a cursor — today the dead-letter listing
     * ({@code GET /webhooks/{webhookId}/dead-letters}), which the published contract requires to
     * embed each undeliverable event's full CloudEvent.
     *
     * <p>One query per {@link #MAX_IN_LIST} ids, not a lookup per row: the natural alternative is
     * the {@code after(seq - 1, 1)} trick {@code WebhookDispatcher.deliver} uses for its single
     * row, and repeating that per dead letter would be a plain N+1 on a listing endpoint.
     *
     * <p><b>Chunked, and the analogy this Javadoc used to draw was wrong</b> (corrective round 2).
     * It cited {@code PlanItemRepository.findByCases} as precedent for an unbounded
     * {@code IN (:seqs)} — but that method is fed one listing PAGE and is bounded by page size,
     * while this one was fed a whole dead-letter queue with no cap anywhere in its path. The
     * failure is worst in precisely the scenario this endpoint exists for: a restart dead-letters
     * every pending delivery of every subscription at once, which is exactly how a queue grows
     * without bound and turns the endpoint into a driver-level SQL failure instead of the
     * diagnostic it was added to be (the exact threshold is measured on {@link #MAX_IN_LIST}). The caller is capped too (see {@code WebhookRepository.deadLetters}), so today this
     * never chunks in practice — it is chunked anyway so the METHOD is correct for any caller,
     * rather than depending on every future one remembering a limit that lives somewhere else.
     *
     * <p>Returns whatever exists; a caller asking for a seq that is not there simply gets fewer
     * rows back, and must handle that rather than assume a 1:1 result — see
     * {@code EventController.deadLetterBody}, whose {@code event} field is nullable for exactly
     * this reason.
     *
     * <p>Unaffected by {@link #after}'s cursor-gap limitation — this addresses rows by primary
     * key rather than walking a cursor, so there is no "did I skip one" question to answer.
     */
    public List<StoredEvent> bySeqs(java.util.Collection<Long> seqs) {
        List<Long> all = List.copyOf(seqs);
        if (all.isEmpty()) {
            return List.of();
        }
        List<StoredEvent> found = new java.util.ArrayList<>(all.size());
        for (int from = 0; from < all.size(); from += MAX_IN_LIST) {
            found.addAll(jdbc.sql("""
                    SELECT SEQ_, ID_, SOURCE_, TYPE_, SUBJECT_, TENANT_ID_, TIME_, DATA_JSON_
                    FROM CM_EVENT WHERE SEQ_ IN (:seqs) ORDER BY SEQ_""")
                .param("seqs", all.subList(from, Math.min(from + MAX_IN_LIST, all.size())))
                .query(EventRepository::map).list());
        }
        return found.stream()
                .sorted(java.util.Comparator.comparingLong(StoredEvent::seq))
                .toList();
    }

    private static StoredEvent map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new StoredEvent(rs.getLong("SEQ_"), new CaseEvent(
                rs.getString("ID_"), rs.getString("SOURCE_"), rs.getString("TYPE_"),
                rs.getString("SUBJECT_"), rs.getString("TENANT_ID_"),
                rs.getObject("TIME_", OffsetDateTime.class),
                JsonCodec.toMap(rs.getString("DATA_JSON_"))));
    }
}
