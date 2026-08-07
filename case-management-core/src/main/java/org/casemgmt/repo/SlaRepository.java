package org.casemgmt.repo;

import org.casemgmt.error.NotFoundException;
import org.casemgmt.error.OptimisticLockException;
import org.casemgmt.sla.SlaRecord;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** Persistence for {@code CM_BUSINESS_CALENDAR}, {@code CM_SLA_POLICY}, {@code CM_SLA_TARGET}
 * and {@code CM_SLA_RECORD} (spec §7). Policy/target/calendar writers here are test-and-PoC-seeding
 * only — no admin API creates them yet. */
public class SlaRepository {

    public record TargetRow(String id, String policyId, String targetKey, String name,
                            String durationIso, String warningIso, List<String> pausedStates,
                            List<String> breachActions) {}

    private static final String RECORD_COLUMNS = """
            ID_, CASE_ID_, TARGET_ID_, STATUS_, STARTED_AT_, DUE_AT_, WARN_AT_, PAUSED_AT_,
            PAUSED_REASON_, PAUSED_TOTAL_SECS_, VERSION_""";

    private static final String TARGET_COLUMNS = """
            ID_, POLICY_ID_, TARGET_KEY_, NAME_, DURATION_ISO_, WARNING_ISO_,
            PAUSED_STATES_JSON_, BREACH_ACTIONS_JSON_""";

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
                             List<String> pausedStates, List<String> breachActions) {
        jdbc.sql("""
                INSERT INTO CM_SLA_TARGET (ID_, POLICY_ID_, TARGET_KEY_, NAME_, DURATION_ISO_,
                    WARNING_ISO_, PAUSED_STATES_JSON_, BREACH_ACTIONS_JSON_)
                VALUES (:id, :policyId, :key, :name, :duration, :warning, :paused, :actions)""")
            .param("id", id).param("policyId", policyId).param("key", targetKey).param("name", name)
            .param("duration", durationIso).param("warning", warningIso)
            .param("paused", JsonCodec.toJson(pausedStates))
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

    public List<TargetRow> targetsFor(String policyId) {
        return jdbc.sql("SELECT " + TARGET_COLUMNS + " FROM CM_SLA_TARGET WHERE POLICY_ID_ = :id")
            .param("id", policyId)
            .query(SlaRepository::mapTarget)
            .list();
    }

    /**
     * Single-target lookup by id — added for pause/resume/sweep (Task 21 fix round 1): those
     * only know a {@code CM_SLA_RECORD}'s {@code TARGET_ID_}, not its policy, and need the
     * target's own {@code PAUSED_STATES_JSON_}/{@code BREACH_ACTIONS_JSON_} to make {@code
     * SlaService.pause}'s reason and {@code SlaSweeper}'s breach-event emission actually mean
     * something (review findings S3) instead of being read from the database and never consulted.
     */
    public TargetRow target(String id) {
        return jdbc.sql("SELECT " + TARGET_COLUMNS + " FROM CM_SLA_TARGET WHERE ID_ = :id")
                .param("id", id).query(SlaRepository::mapTarget).optional()
                .orElseThrow(() -> new NotFoundException("SlaTarget", id));
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
                    WARN_AT_, PAUSED_TOTAL_SECS_, VERSION_)
                VALUES (:id, :caseId, :targetId, :status, :startedAt, :dueAt, :warnAt, 0, 0)""")
            .param("id", r.id()).param("caseId", r.caseId()).param("targetId", r.targetId())
            .param("status", r.status()).param("startedAt", r.startedAt())
            .param("dueAt", r.dueAt()).param("warnAt", r.warnAt())
            .update();
    }

    public List<SlaRecord> findByCase(String caseId) {
        return jdbc.sql("SELECT " + RECORD_COLUMNS + " FROM CM_SLA_RECORD WHERE CASE_ID_ = :caseId")
                .param("caseId", caseId).query(SlaRepository::mapRecord).list();
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
                    MET_AT_ = CASE WHEN :status = 'MET' THEN SYSTIMESTAMP ELSE MET_AT_ END,
                    BREACHED_AT_ = CASE WHEN :status = 'BREACHED' THEN SYSTIMESTAMP ELSE BREACHED_AT_ END,
                    VERSION_ = VERSION_ + 1
                WHERE ID_ = :id AND VERSION_ = :expected""")
            .param("status", r.status()).param("dueAt", r.dueAt()).param("warnAt", r.warnAt())
            .param("pausedAt", r.pausedAt()).param("reason", r.pausedReason())
            .param("pausedTotal", r.pausedTotalSeconds())
            .param("id", r.id()).param("expected", expectedVersion)
            .update();
        if (rows == 0) throw new OptimisticLockException("SlaRecord", r.id(), expectedVersion);
        return new SlaRecord(r.id(), r.caseId(), r.targetId(), r.status(), r.startedAt(), r.dueAt(),
                r.warnAt(), r.pausedAt(), r.pausedReason(), r.pausedTotalSeconds(), expectedVersion + 1);
    }

    /** Running clocks past their warning or breach threshold — the sweeper's work list. */
    public List<SlaRecord> dueRecords(OffsetDateTime now) {
        // Plain string concatenation, not a text block spanning the "+ RECORD_COLUMNS +": a text
        // block strips trailing whitespace from every line, including the line ending "SELECT
        // """ right before the concatenation, which silently swallowed the space and produced
        // "SELECTID_, ..." -> ORA-00900. findByCase/require below use the same plain-string
        // style for exactly this reason.
        return jdbc.sql("SELECT " + RECORD_COLUMNS + " FROM CM_SLA_RECORD "
                + "WHERE STATUS_ = 'RUNNING' AND (DUE_AT_ <= :now OR WARN_AT_ <= :now)")
            .param("now", now).query(SlaRepository::mapRecord).list();
    }

    private static SlaRecord mapRecord(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new SlaRecord(rs.getString("ID_"), rs.getString("CASE_ID_"), rs.getString("TARGET_ID_"),
                rs.getString("STATUS_"),
                rs.getObject("STARTED_AT_", OffsetDateTime.class),
                rs.getObject("DUE_AT_", OffsetDateTime.class),
                rs.getObject("WARN_AT_", OffsetDateTime.class),
                rs.getObject("PAUSED_AT_", OffsetDateTime.class),
                rs.getString("PAUSED_REASON_"), rs.getLong("PAUSED_TOTAL_SECS_"),
                rs.getLong("VERSION_"));
    }
}
