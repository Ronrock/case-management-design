package org.casemgmt.repo;

import org.casemgmt.error.NotFoundException;
import org.casemgmt.error.OptimisticLockException;
import org.casemgmt.sla.SlaRecord;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.Collection;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;

/** Persistence for {@code CM_BUSINESS_CALENDAR}, {@code CM_SLA_POLICY}, {@code CM_SLA_TARGET}
 * and {@code CM_SLA_RECORD} (spec §7). Policy/target/calendar writers here are test-and-PoC-seeding
 * only — no admin API creates them yet. */
public class SlaRepository {

    public record TargetRow(String id, String policyId, String targetKey, String name,
                            String durationIso, String warningIso, List<String> pauseReasons,
                            List<String> breachActions) {}

    public record ClaimedRecord(SlaRecord record, String claimToken) {}

    /** Immutable evidence captured from a published contract when one clock is started. */
    public record ContractOccurrence(String id, String caseId, String targetId, String targetKey,
                                     int targetVersion, String scope, String occurrenceKey,
                                     String contractReleaseId, String contractSha256,
                                     String calendarId, int calendarRevision,
                                     String meetAnchor, String cancelAnchor,
                                     OffsetDateTime startedAt, OffsetDateTime dueAt,
                                     OffsetDateTime warnAt, String transitionEvidence) { }

    private static final String RECORD_COLUMNS = """
            ID_, CASE_ID_, TARGET_ID_, STATUS_, STARTED_AT_, DUE_AT_, WARN_AT_, PAUSED_AT_,
            PAUSED_REASON_, PAUSED_TOTAL_SECS_, VERSION_,
            COALESCE(MET_AT_, CANCELLED_AT_, BREACHED_AT_) AS TERMINAL_AT_""";

    private static final String TARGET_COLUMNS = """
            ID_, POLICY_ID_, TARGET_KEY_, NAME_, DURATION_ISO_, WARNING_ISO_,
            PAUSED_STATES_JSON_, BREACH_ACTIONS_JSON_""";

    private static final int MAX_TARGET_LOOKUP_IN_LIST = 500;

    private final JdbcClient jdbc;

    public SlaRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public void insertCalendar(String id, Map<String, Object> definition) {
        jdbc.sql("INSERT INTO CM_BUSINESS_CALENDAR (ID_, NAME_, DEFINITION_JSON_) VALUES (:id, :id, :def)")
            .param("id", id).param("def", JsonCodec.toJson(definition)).update();
    }

    public void insertPolicy(String id, String name, String selector, String calendarId) {
        jdbc.sql("""
                INSERT INTO CM_SLA_POLICY (ID_, NAME_, SELECTOR_, CALENDAR_ID_)
                VALUES (:id, :name, :selector, :calendarId)""")
            .param("id", id).param("name", name).param("selector", selector)
            .param("calendarId", calendarId).update();
    }

    public void insertTarget(String id, String policyId, String targetKey, String name,
                             String durationIso, String warningIso,
                             List<String> pauseReasons, List<String> breachActions) {
        jdbc.sql("""
                INSERT INTO CM_SLA_TARGET (ID_, POLICY_ID_, TARGET_KEY_, NAME_, DURATION_ISO_,
                    WARNING_ISO_, PAUSED_STATES_JSON_, BREACH_ACTIONS_JSON_)
                VALUES (:id, :policyId, :key, :name, :duration, :warning, :paused, :actions)""")
            .param("id", id).param("policyId", policyId).param("key", targetKey).param("name", name)
            .param("duration", durationIso).param("warning", warningIso)
            .param("paused", JsonCodec.toJson(pauseReasons))
            .param("actions", JsonCodec.toJson(breachActions))
            .update();
    }

    public String calendarIdOf(String policyId) {
        return jdbc.sql("SELECT CALENDAR_ID_ FROM CM_SLA_POLICY WHERE ID_ = :id")
                .param("id", policyId).query(String.class).optional().orElse(null);
    }

    public Map<String, Object> calendarDefinition(String calendarId) {
        return jdbc.sql("SELECT DEFINITION_JSON_ FROM CM_BUSINESS_CALENDAR WHERE ID_ = :id")
                .param("id", calendarId).query(String.class).optional()
                .map(JsonCodec::toMap).orElse(Map.of());
    }

    public boolean calendarExists(String calendarId) {
        return jdbc.sql("SELECT COUNT(*) FROM CM_BUSINESS_CALENDAR WHERE ID_ = :id")
                .param("id", calendarId).query(Integer.class).single() == 1;
    }

    public List<TargetRow> targetsFor(String policyId) {
        return jdbc.sql("SELECT " + TARGET_COLUMNS + " FROM CM_SLA_TARGET WHERE POLICY_ID_ = :id")
            .param("id", policyId)
            .query(SlaRepository::mapTarget)
            .list();
    }

    /**
     * Single-target lookup by id — added for pause/resume/sweep (Task 21 fix round 1): those
     * only know a {@code CM_SLA_RECORD}'s {@code TARGET_ID_}, not its policy, and need the
     * target's own legacy {@code PAUSED_STATES_JSON_} column (interpreted as pause reasons) and
     * {@code BREACH_ACTIONS_JSON_} to make {@code SlaService.pause}'s reason and
     * {@code SlaSweeper}'s breach-event emission actually mean something (review findings S3)
     * instead of being read from the database and never consulted.
     */
    public TargetRow target(String id) {
        return jdbc.sql("SELECT " + TARGET_COLUMNS + " FROM CM_SLA_TARGET WHERE ID_ = :id")
                .param("id", id).query(SlaRepository::mapTarget).optional()
                .orElseThrow(() -> new NotFoundException("SlaTarget", id));
    }

    /**
     * Batch target lookup for the SLA sweeper. A sweep can claim many records for the same
     * target, and fetching the target row per record turns every backlog into an avoidable N+1.
     */
    public Map<String, TargetRow> targetsById(Collection<String> ids) {
        List<String> all = ids.stream().filter(Objects::nonNull).distinct().toList();
        if (all.isEmpty()) {
            return Map.of();
        }

        Map<String, TargetRow> found = new LinkedHashMap<>();
        for (int from = 0; from < all.size(); from += MAX_TARGET_LOOKUP_IN_LIST) {
            jdbc.sql("SELECT " + TARGET_COLUMNS + " FROM CM_SLA_TARGET WHERE ID_ IN (:ids)")
                    .param("ids", all.subList(from, Math.min(from + MAX_TARGET_LOOKUP_IN_LIST, all.size())))
                    .query(SlaRepository::mapTarget)
                    .list()
                    .forEach(t -> found.put(t.id(), t));
        }
        return found;
    }

    private static TargetRow mapTarget(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new TargetRow(rs.getString("ID_"), rs.getString("POLICY_ID_"),
                rs.getString("TARGET_KEY_"), rs.getString("NAME_"), rs.getString("DURATION_ISO_"),
                rs.getString("WARNING_ISO_"),
                JsonCodec.toList(rs.getString("PAUSED_STATES_JSON_")),
                JsonCodec.toList(rs.getString("BREACH_ACTIONS_JSON_")));
    }

    public void insertRecord(SlaRecord r) {
        jdbc.sql("""
                INSERT INTO CM_SLA_RECORD (ID_, CASE_ID_, TARGET_ID_, STATUS_, STARTED_AT_, DUE_AT_,
                    WARN_AT_, PAUSED_AT_, PAUSED_REASON_, PAUSED_TOTAL_SECS_, VERSION_)
                VALUES (:id, :caseId, :targetId, :status, :startedAt, :dueAt, :warnAt,
                    :pausedAt, :reason, :pausedTotal, :version)""")
            .param("id", r.id()).param("caseId", r.caseId()).param("targetId", r.targetId())
            .param("status", r.status()).param("startedAt", r.startedAt())
            .param("dueAt", r.dueAt()).param("warnAt", r.warnAt())
            .param("pausedAt", r.pausedAt()).param("reason", r.pausedReason())
            .param("pausedTotal", r.pausedTotalSeconds()).param("version", r.version())
            .update();
    }

    /**
     * Creates a contract-derived clock exactly once.  The unique occurrence identity is the
     * durable idempotency boundary for replayed engine observations; its policy/target rows are
     * only compatibility metadata for the existing sweeper and are not the behavioural source.
     */
    public boolean insertContractOccurrenceIfAbsent(ContractOccurrence occurrence) {
        try {
            jdbc.sql("""
                    INSERT INTO CM_SLA_RECORD
                      (ID_, CASE_ID_, TARGET_ID_, STATUS_, STARTED_AT_, DUE_AT_, WARN_AT_,
                       PAUSED_TOTAL_SECS_, VERSION_, CONTRACT_RELEASE_ID_, CONTRACT_SHA256_,
                       TARGET_KEY_, TARGET_VERSION_, SLA_SCOPE_, OCCURRENCE_KEY_, CALENDAR_ID_,
                       CALENDAR_REVISION_, MEET_ANCHOR_, CANCEL_ANCHOR_, TRANSITION_EVIDENCE_JSON_)
                    VALUES
                      (:id, :caseId, :targetId, 'RUNNING', :startedAt, :dueAt, :warnAt,
                       0, 0, :releaseId, :sha256, :targetKey, :targetVersion, :scope,
                       :occurrenceKey, :calendarId, :calendarRevision, :meetAnchor,
                       :cancelAnchor, :evidence)""")
                    .param("id", occurrence.id()).param("caseId", occurrence.caseId())
                    .param("targetId", occurrence.targetId()).param("startedAt", occurrence.startedAt())
                    .param("dueAt", occurrence.dueAt()).param("warnAt", occurrence.warnAt())
                    .param("releaseId", occurrence.contractReleaseId()).param("sha256", occurrence.contractSha256())
                    .param("targetKey", occurrence.targetKey()).param("targetVersion", occurrence.targetVersion())
                    .param("scope", occurrence.scope()).param("occurrenceKey", occurrence.occurrenceKey())
                    .param("calendarId", occurrence.calendarId()).param("calendarRevision", occurrence.calendarRevision())
                    .param("meetAnchor", occurrence.meetAnchor()).param("cancelAnchor", occurrence.cancelAnchor())
                    .param("evidence", occurrence.transitionEvidence()).update();
            return true;
        } catch (DuplicateKeyException duplicate) {
            return false;
        }
    }

    /** Ensures the legacy scheduler can locate a contract occurrence without owning its rules. */
    public void ensureContractTarget(String policyId, String targetId, String targetKey,
                                     String calendarId, String duration, String warning,
                                     List<String> breachActions) {
        try {
            insertPolicy(policyId, "Published contract " + policyId, null, calendarId);
        } catch (DuplicateKeyException ignored) {
            // The immutable release-derived key already owns this compatibility row.
        }
        try {
            insertTarget(targetId, policyId, targetKey, targetKey, duration,
                    warning, List.of(), breachActions);
        } catch (DuplicateKeyException ignored) {
            // Same release/binding replay; the occurrence unique key remains the authority.
        }
    }

    public List<SlaRecord> findByCase(String caseId) {
        return jdbc.sql("SELECT " + RECORD_COLUMNS + " FROM CM_SLA_RECORD WHERE CASE_ID_ = :caseId")
                .param("caseId", caseId).query(SlaRepository::mapRecord).list();
    }

    /**
     * Changes a currently open occurrence to the root case's declared terminal outcome.  The
     * status predicate is the concurrency boundary: a sweeper that already proved a breach wins
     * its earlier fact, while a clock still RUNNING or PAUSED can never be claimed afterwards.
     * Returning only rows whose update won lets callers emit one audit/event pair per durable
     * transition and makes a duplicate root observation a true no-op.
     */
    public List<SlaRecord> terminalizeNonterminalForCase(String caseId, String terminalStatus,
                                                           OffsetDateTime terminalAt) {
        if (!"MET".equals(terminalStatus) && !"CANCELLED".equals(terminalStatus)) {
            throw new IllegalArgumentException("unsupported SLA terminal status: " + terminalStatus);
        }
        List<SlaRecord> candidates = jdbc.sql("SELECT " + RECORD_COLUMNS + " FROM CM_SLA_RECORD "
                        + "WHERE CASE_ID_ = :caseId AND STATUS_ IN ('RUNNING', 'PAUSED') ORDER BY ID_")
                .param("caseId", caseId).query(SlaRepository::mapRecord).list();
        List<SlaRecord> terminalized = new java.util.ArrayList<>();
        for (SlaRecord candidate : candidates) {
            int updated = jdbc.sql("""
                    UPDATE CM_SLA_RECORD
                    SET STATUS_ = :status, WARN_AT_ = NULL, PAUSED_AT_ = NULL,
                        PAUSED_REASON_ = NULL, CLAIM_TOKEN_ = NULL, CLAIMED_AT_ = NULL,
                        MET_AT_ = CASE WHEN :status = 'MET' THEN :terminalAt ELSE MET_AT_ END,
                        CANCELLED_AT_ = CASE WHEN :status = 'CANCELLED' THEN :terminalAt ELSE CANCELLED_AT_ END,
                        VERSION_ = VERSION_ + 1
                    WHERE ID_ = :id AND STATUS_ IN ('RUNNING', 'PAUSED')""")
                    .param("status", terminalStatus).param("terminalAt", terminalAt)
                    .param("id", candidate.id()).update();
            if (updated == 1) {
                terminalized.add(new SlaRecord(candidate.id(), candidate.caseId(), candidate.targetId(),
                        terminalStatus, candidate.startedAt(), candidate.dueAt(), null, null, null,
                        candidate.pausedTotalSeconds(), candidate.version() + 1, terminalAt));
            }
        }
        return List.copyOf(terminalized);
    }

    /**
     * Atomically terminalises contract-derived clocks whose published binding declares the root
     * anchor as its outcome.  A contract clock with no matching anchor is deliberately left
     * alone; the runtime never guesses an SLA outcome from a case state.
     */
    public List<SlaRecord> terminalizeContractOccurrencesForRoot(String caseId, String rootAnchor,
                                                                   OffsetDateTime terminalAt) {
        List<ContractTerminalCandidate> candidates = jdbc.sql("""
                SELECT ID_, MEET_ANCHOR_, CANCEL_ANCHOR_
                FROM CM_SLA_RECORD
                WHERE CASE_ID_ = :caseId AND CONTRACT_RELEASE_ID_ IS NOT NULL
                  AND STATUS_ IN ('RUNNING', 'PAUSED')
                ORDER BY ID_""")
                .param("caseId", caseId)
                .query((rs, n) -> new ContractTerminalCandidate(rs.getString("ID_"),
                        rs.getString("MEET_ANCHOR_"), rs.getString("CANCEL_ANCHOR_")))
                .list();
        List<SlaRecord> result = new java.util.ArrayList<>();
        for (ContractTerminalCandidate candidate : candidates) {
            String status = rootAnchor.equals(candidate.meetAnchor()) ? "MET"
                    : rootAnchor.equals(candidate.cancelAnchor()) ? "CANCELLED" : null;
            if (status == null) continue;
            int changed = jdbc.sql("""
                    UPDATE CM_SLA_RECORD
                    SET STATUS_ = :status, WARN_AT_ = NULL, PAUSED_AT_ = NULL,
                        PAUSED_REASON_ = NULL, CLAIM_TOKEN_ = NULL, CLAIMED_AT_ = NULL,
                        MET_AT_ = CASE WHEN :status = 'MET' THEN :terminalAt ELSE MET_AT_ END,
                        CANCELLED_AT_ = CASE WHEN :status = 'CANCELLED' THEN :terminalAt ELSE CANCELLED_AT_ END,
                        TRANSITION_EVIDENCE_JSON_ = :evidence, VERSION_ = VERSION_ + 1
                    WHERE ID_ = :id AND STATUS_ IN ('RUNNING', 'PAUSED')""")
                    .param("id", candidate.id()).param("status", status).param("terminalAt", terminalAt)
                    .param("evidence", JsonCodec.toJson(Map.of("anchor", rootAnchor,
                            "outcome", status, "occurredAt", terminalAt.toInstant().toString())))
                    .update();
            if (changed == 1) result.add(require(candidate.id()));
        }
        return List.copyOf(result);
    }

    public SlaRecord require(String id) {
        return jdbc.sql("SELECT " + RECORD_COLUMNS + " FROM CM_SLA_RECORD WHERE ID_ = :id")
                .param("id", id).query(SlaRepository::mapRecord).optional()
                .orElseThrow(() -> new NotFoundException("SlaRecord", id));
    }

    /**
     * Optimistic update, same shape as {@link CaseRepository#update}: {@code UPDATE ... WHERE
     * ID_ = :id AND VERSION_ = :expected}, zero rows means a conflict.
     *
     * <p>Deliberately does NOT re-read the row after the UPDATE to build its return value, for
     * the same reason {@code CaseRepository.update} does not: on this module's autocommit-pooled
     * connections a follow-up SELECT is a second, independent statement, and a concurrent
     * writer's UPDATE could land and commit in the gap between the two — the SELECT would then
     * silently hand back THAT writer's state (and version) as if it confirmed this call's own
     * write. Since the WHERE clause already proves this UPDATE matched exactly one row at
     * {@code expectedVersion}, the post-state is fully known without asking again: the caller's
     * own field values, version incremented by exactly one.
     */
    public SlaRecord update(SlaRecord r, long expectedVersion) {
        int rows = jdbc.sql("""
                UPDATE CM_SLA_RECORD SET STATUS_ = :status, DUE_AT_ = :dueAt, WARN_AT_ = :warnAt,
                    PAUSED_AT_ = :pausedAt, PAUSED_REASON_ = :reason,
                    PAUSED_TOTAL_SECS_ = :pausedTotal,
                    BREACHED_AT_ = CASE WHEN :status = 'BREACHED' THEN SYSTIMESTAMP ELSE BREACHED_AT_ END,
                    CLAIM_TOKEN_ = NULL, CLAIMED_AT_ = NULL,
                    VERSION_ = VERSION_ + 1
                WHERE ID_ = :id AND VERSION_ = :expected""")
            .param("status", r.status()).param("dueAt", r.dueAt()).param("warnAt", r.warnAt())
            .param("pausedAt", r.pausedAt()).param("reason", r.pausedReason())
            .param("pausedTotal", r.pausedTotalSeconds())
            .param("id", r.id()).param("expected", expectedVersion)
            .update();
        if (rows == 0) throw new OptimisticLockException("SlaRecord", r.id(), expectedVersion);
        return new SlaRecord(r.id(), r.caseId(), r.targetId(), r.status(), r.startedAt(), r.dueAt(),
                r.warnAt(), r.pausedAt(), r.pausedReason(), r.pausedTotalSeconds(), expectedVersion + 1,
                r.terminalAt());
    }

    public SlaRecord updateClaimed(SlaRecord r, long expectedVersion, String claimToken) {
        int rows = jdbc.sql("""
                UPDATE CM_SLA_RECORD SET STATUS_ = :status, DUE_AT_ = :dueAt, WARN_AT_ = :warnAt,
                    PAUSED_AT_ = :pausedAt, PAUSED_REASON_ = :reason,
                    PAUSED_TOTAL_SECS_ = :pausedTotal,
                    BREACHED_AT_ = CASE WHEN :status = 'BREACHED' THEN SYSTIMESTAMP ELSE BREACHED_AT_ END,
                    CLAIM_TOKEN_ = NULL, CLAIMED_AT_ = NULL,
                    VERSION_ = VERSION_ + 1
                WHERE ID_ = :id AND VERSION_ = :expected AND CLAIM_TOKEN_ = :claimToken""")
            .param("status", r.status()).param("dueAt", r.dueAt()).param("warnAt", r.warnAt())
            .param("pausedAt", r.pausedAt()).param("reason", r.pausedReason())
            .param("pausedTotal", r.pausedTotalSeconds())
            .param("id", r.id()).param("expected", expectedVersion)
            .param("claimToken", claimToken)
            .update();
        if (rows == 0) throw new OptimisticLockException("SlaRecord", r.id(), expectedVersion);
        return new SlaRecord(r.id(), r.caseId(), r.targetId(), r.status(), r.startedAt(), r.dueAt(),
                r.warnAt(), r.pausedAt(), r.pausedReason(), r.pausedTotalSeconds(), expectedVersion + 1,
                r.terminalAt());
    }

    /**
     * Largest work list one {@link #claimDueRecords} call will hand back — the bounded-batch
     * invariant {@link WebhookRepository#MAX_CLAIM_BATCH} already establishes for the webhook
     * outbox, applied here for the same reason (final whole-branch review, Important 8).
     *
     * <p>{@code SlaSweeper.sweep()} is {@code @Transactional} and iterates everything this
     * returns, so an unbounded result set means one transaction holding row locks across
     * {@code CM_SLA_RECORD} AND {@code CM_CASE} for the entire backlog — {@code CaseRepository}'s
     * own Javadoc already states the consequence ("the sweeper holds the row lock for its whole
     * batch, so a concurrent edit blocks on it"). On a live, user-facing table that is a lock
     * convoy waiting for the first busy day: a backlog after an outage, or the first sweep after
     * a bulk import, would block ordinary case edits for as long as the batch runs.
     *
     * <p>200 rather than the webhook outbox's 20 because the two batches cost entirely different
     * things: a webhook delivery makes an outbound HTTP call bounded by a claim lease, while one
     * SLA record is a handful of local statements. The bound that matters here is transaction
     * duration and lock-hold time. Leftover records are simply picked up by the next sweep.
     */
    public static final int MAX_SWEEP_BATCH = 200;

    public static final Duration CLAIM_LEASE = Duration.ofMinutes(5);

    public List<ClaimedRecord> claimDueRecords(OffsetDateTime now) {
        String token = UUID.randomUUID().toString();
        OffsetDateTime staleBefore = now.minus(CLAIM_LEASE);

        int claimed = jdbc.sql("""
                UPDATE CM_SLA_RECORD
                SET CLAIM_TOKEN_ = :token, CLAIMED_AT_ = SYSTIMESTAMP
                WHERE STATUS_ = 'RUNNING'
                  AND (DUE_AT_ <= :now OR WARN_AT_ <= :now)
                  AND (CLAIM_TOKEN_ IS NULL OR CLAIMED_AT_ <= :staleBefore)
                  AND ID_ IN (
                      SELECT ID_ FROM (
                          SELECT ID_ FROM CM_SLA_RECORD
                          WHERE STATUS_ = 'RUNNING'
                            AND (DUE_AT_ <= :now OR WARN_AT_ <= :now)
                            AND (CLAIM_TOKEN_ IS NULL OR CLAIMED_AT_ <= :staleBefore)
                          ORDER BY ID_
                      )
                      WHERE ROWNUM <= :batch
                  )""")
            .param("token", token)
            .param("now", now)
            .param("staleBefore", staleBefore)
            .param("batch", MAX_SWEEP_BATCH)
            .update();

        if (claimed == 0) {
            return List.of();
        }

        return jdbc.sql("SELECT " + RECORD_COLUMNS + ", CLAIM_TOKEN_ FROM CM_SLA_RECORD "
                + "WHERE CLAIM_TOKEN_ = :token ORDER BY ID_")
            .param("token", token)
            .query((rs, n) -> new ClaimedRecord(mapRecord(rs, n), rs.getString("CLAIM_TOKEN_")))
            .list();
    }

    /** Claims one previously selected due row after the caller has locked its case. */
    public java.util.Optional<ClaimedRecord> claimDueRecord(String id, OffsetDateTime now) {
        String token = UUID.randomUUID().toString();
        OffsetDateTime staleBefore = now.minus(CLAIM_LEASE);
        int claimed = jdbc.sql("""
                UPDATE CM_SLA_RECORD SET CLAIM_TOKEN_ = :token, CLAIMED_AT_ = SYSTIMESTAMP
                WHERE ID_ = :id AND STATUS_ = 'RUNNING'
                  AND (DUE_AT_ <= :now OR WARN_AT_ <= :now)
                  AND (CLAIM_TOKEN_ IS NULL OR CLAIMED_AT_ <= :staleBefore)""")
                .param("id", id).param("token", token).param("now", now)
                .param("staleBefore", staleBefore).update();
        if (claimed == 0) return java.util.Optional.empty();
        return jdbc.sql("SELECT " + RECORD_COLUMNS + ", CLAIM_TOKEN_ FROM CM_SLA_RECORD WHERE ID_ = :id")
                .param("id", id)
                .query((rs, n) -> new ClaimedRecord(mapRecord(rs, n), rs.getString("CLAIM_TOKEN_")))
                .optional();
    }

    /**
     * Running clocks past their warning or breach threshold, oldest first and bounded to
     * {@link #MAX_SWEEP_BATCH}. This is an unclaimed diagnostic/test lookup; production sweeping
     * uses {@link #claimDueRecords} so multiple application instances do not process the same row.
     *
     * <p><b>{@code ORDER BY ID_} is not cosmetic</b> (final whole-branch review, Important 8).
     * Without it two concurrent sweepers can walk the same due records in different orders and
     * deadlock (ORA-00060) — and that surfaces as a {@code DataAccessException}, which escapes
     * {@code SlaSweeper.processOne}'s per-record {@code OptimisticLockException} catch and rolls
     * back the entire batch. A total order shared by every sweeper makes the deadlock
     * structurally impossible: two callers taking the same rows in the same sequence queue on
     * the first contended row instead of each holding what the other wants. It also makes the
     * {@code FETCH FIRST} prefix deterministic, so a record can never be starved by an unstable
     * row order across sweeps.
     */
    public List<SlaRecord> dueRecords(OffsetDateTime now) {
        // Plain string concatenation, not a text block spanning the "+ RECORD_COLUMNS +": a text
        // block strips trailing whitespace from every line, including the line ending "SELECT
        // """ right before the concatenation, which silently swallowed the space and produced
        // "SELECTID_, ..." -> ORA-00900. findByCase/require below use the same plain-string
        // style for exactly this reason.
        return jdbc.sql("SELECT " + RECORD_COLUMNS + " FROM CM_SLA_RECORD "
                + "WHERE STATUS_ = 'RUNNING' AND (DUE_AT_ <= :now OR WARN_AT_ <= :now) "
                + "ORDER BY ID_ FETCH FIRST :batch ROWS ONLY")
            .param("now", now).param("batch", MAX_SWEEP_BATCH)
            .query(SlaRepository::mapRecord).list();
    }

    private record ContractTerminalCandidate(String id, String meetAnchor, String cancelAnchor) { }

    private static SlaRecord mapRecord(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new SlaRecord(rs.getString("ID_"), rs.getString("CASE_ID_"), rs.getString("TARGET_ID_"),
                rs.getString("STATUS_"),
                rs.getObject("STARTED_AT_", OffsetDateTime.class),
                rs.getObject("DUE_AT_", OffsetDateTime.class),
                rs.getObject("WARN_AT_", OffsetDateTime.class),
                rs.getObject("PAUSED_AT_", OffsetDateTime.class),
                rs.getString("PAUSED_REASON_"), rs.getLong("PAUSED_TOTAL_SECS_"),
                rs.getLong("VERSION_"), rs.getObject("TERMINAL_AT_", OffsetDateTime.class));
    }
}
