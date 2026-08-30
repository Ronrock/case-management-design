package org.casemgmt.repo;

import org.casemgmt.domain.*;
import org.casemgmt.error.NotFoundException;
import org.casemgmt.error.OptimisticLockException;
import org.casemgmt.service.CanonicalPatch;
import org.casemgmt.service.CaseDataMappingService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public class CaseRepository {

    private static final String COLUMNS = """
            ID_, ENGINE_ID_, TENANT_ID_, CASE_DEF_ID_, CASE_DEF_KEY_, CASE_DEF_VER_,
            BUSINESS_KEY_, TITLE_, STATE_, PRIORITY_, ASSIGNEE_, QUEUE_ID_, INITIATOR_,
            SLA_STATUS_, OUTCOME_, CANCEL_REASON_, VARIABLES_JSON_, VERSION_,
            CREATED_AT_, UPDATED_AT_, CLOSED_AT_, ROOT_PROC_INST_ID_, PROJECTION_STATUS_,
            LAST_ENGINE_UPDATE_AT_, LAST_PROJECTED_AT_""";

    private final JdbcClient jdbc;
    private final TransactionTemplate mandatoryCanonicalTransaction;

    /**
     * Compatibility constructor for ordinary repository operations. Canonical compare-and-apply
     * is unavailable because this form cannot verify which {@link DataSource} owns the caller's
     * transaction; use {@link #CaseRepository(DataSource)} for mapping support.
     */
    public CaseRepository(JdbcClient jdbc) {
        this(jdbc, null);
    }

    /** Creates the {@link JdbcClient} and mandatory transaction participant from one DataSource. */
    public CaseRepository(DataSource transactionDataSource) {
        this(JdbcClient.create(transactionDataSource), mandatoryTransaction(transactionDataSource));
    }

    private CaseRepository(JdbcClient jdbc, TransactionTemplate mandatoryCanonicalTransaction) {
        this.jdbc = jdbc;
        this.mandatoryCanonicalTransaction = mandatoryCanonicalTransaction;
    }

    public void insert(CaseInstance c) {
        jdbc.sql("""
                INSERT INTO CM_CASE (ID_, ENGINE_ID_, TENANT_ID_, CASE_DEF_ID_, CASE_DEF_KEY_,
                    CASE_DEF_VER_, BUSINESS_KEY_, TITLE_, STATE_, PRIORITY_, ASSIGNEE_, QUEUE_ID_,
                    INITIATOR_, SLA_STATUS_, OUTCOME_, CANCEL_REASON_, VARIABLES_JSON_, VERSION_,
                    CREATED_AT_, UPDATED_AT_, CLOSED_AT_)
                VALUES (:id, :engineId, :tenantId, :caseDefId, :caseDefKey, :caseDefVer,
                    :businessKey, :title, :state, :priority, :assignee, :queueId, :initiator,
                    :slaStatus, :outcome, :cancelReason, :variables, :version,
                    :createdAt, :updatedAt, :closedAt)""")
            .param("id", c.id()).param("engineId", c.engineId()).param("tenantId", c.tenantId())
            .param("caseDefId", c.caseDefId()).param("caseDefKey", c.caseDefKey())
            .param("caseDefVer", c.caseDefVersion()).param("businessKey", c.businessKey())
            .param("title", c.title()).param("state", c.state().name())
            .param("priority", c.priority().name()).param("assignee", c.assignee())
            .param("queueId", c.queueId()).param("initiator", c.initiator())
            .param("slaStatus", c.slaStatus() == null ? "NONE" : c.slaStatus())
            .param("outcome", c.outcome()).param("cancelReason", c.cancelReason())
            .param("variables", JsonCodec.toJson(c.variables()))
            .param("version", c.version())
            .param("createdAt", c.createdAt()).param("updatedAt", c.updatedAt())
            .param("closedAt", c.closedAt())
            .update();
    }

    public Optional<CaseInstance> findById(String id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM CM_CASE WHERE ID_ = :id")
                .param("id", id)
                .query(CaseRepository::map)
                .optional();
    }

    public CaseInstance require(String id) {
        return findById(id).orElseThrow(() -> new NotFoundException("Case", id));
    }

    /**
     * Serializes lifecycle observations for one case inside the caller's transaction.
     *
     * <p>The mandatory template is a participation guard only: it cannot start or independently
     * commit a transaction. Holding this row lock through the handler's watermark read and
     * effects prevents two distinct fingerprints for the same entity from both observing an
     * obsolete watermark.
     */
    public void lockForObservation(String caseId) {
        if (caseId == null || caseId.isBlank()) {
            throw new IllegalArgumentException("caseId must not be blank");
        }
        if (mandatoryCanonicalTransaction == null) {
            throw new IllegalStateException("Observation locking requires an active caller "
                    + "transaction and a transaction-verifiable repository DataSource");
        }
        try {
            mandatoryCanonicalTransaction.executeWithoutResult(status -> {
                boolean exists = jdbc.sql("SELECT ID_ FROM CM_CASE WHERE ID_ = :id FOR UPDATE")
                        .param("id", caseId)
                        .query(String.class)
                        .optional()
                        .isPresent();
                if (!exists) {
                    throw new NotFoundException("Case", caseId);
                }
            });
        } catch (IllegalTransactionStateException missingTransaction) {
            throw new IllegalStateException("Observation locking requires an active caller "
                    + "transaction bound to the repository DataSource", missingTransaction);
        }
    }

    /**
     * Locks the case before an SLA row is claimed.  SLA root terminalisation is called from the
     * observation path which already holds this same lock, so this establishes one global order
     * (case then SLA) and prevents an SLA sweeper from breaching a case while its completion is
     * waiting to terminalise its clocks.  The caller must own the surrounding transaction.
     */
    public void lockForSlaLifecycle(String caseId) {
        boolean exists = jdbc.sql("SELECT ID_ FROM CM_CASE WHERE ID_ = :id FOR UPDATE")
                .param("id", caseId).query(String.class).optional().isPresent();
        if (!exists) throw new NotFoundException("Case", caseId);
    }

    /**
     * Optimistic update. Zero rows affected means someone else wrote first —
     * never retried here, always surfaced as 412 by the REST layer.
     *
     * <p>Deliberately does NOT re-read the row after the UPDATE to build its return value.
     * A repository call may run with no transaction boundary at all — {@code JdbcClient} on a
     * plain pooled {@code DataSource}, which is how this module's own tests build it and how
     * any caller outside a {@code @Transactional} service reaches it — in which case the UPDATE
     * and a follow-up SELECT are two independently auto-committed statements with nothing tying
     * them together. (Corrected in Task 27: an earlier version of this paragraph said this
     * MODULE has no transaction manager. That stopped being true at Task 5 —
     * {@code org.casemgmt.config.TransactionManagerConfig} lives in this module and the starter
     * imports it, so services here really do run inside proxied transactions. The conclusion
     * below is unaffected and holds either way, which is why it survived the correction: the
     * no-transaction case is the weakest environment this method must be correct in, and
     * building the return value locally is correct in all of them.) If another
     * writer's UPDATE landed and committed in the gap between this call's UPDATE and its
     * SELECT, the SELECT would silently return THAT writer's state and version — this
     * caller would get no exception and would reasonably (but wrongly) believe the returned
     * object, including its version/ETag, confirmed its own write. Since the WHERE clause
     * already proves this call's UPDATE matched exactly one row at {@code expectedVersion},
     * the post-state is fully known without asking the database again: same row, same
     * columns this call set, version incremented by exactly one. Constructing it locally
     * is both correct (no window for another writer's commit to be misattributed) and one
     * round trip cheaper than the read-back this replaced.
     *
     * <p>UPDATED_AT_ is set from a single Java-side {@code OffsetDateTime.now()} captured
     * before the UPDATE and bound explicitly as a parameter (not left to SQL's
     * {@code SYSTIMESTAMP}), so the timestamp written to the row and the timestamp on the
     * returned object are the exact same value — no second read needed to learn what the
     * server actually stored.
     *
     * <p><b>{@code SLA_STATUS_} is deliberately absent from the SET list (fix round 2, review
     * finding "SLA_STATUS_ is now silently stompable"):</b> this method's own Javadoc above
     * explains why it never re-reads before returning — the caller's in-memory {@code
     * CaseInstance} is trusted as current for every column THIS method owns. {@code SLA_STATUS_}
     * is not one of them; {@code SlaSweeper} owns it exclusively via {@link
     * #updateSlaStatusMonotonic}. Before this fix, a full-row {@code update} call built from a
     * case read BEFORE a concurrent sweep committed a breach would carry that stale value
     * (typically {@code NONE} or {@code WARNING}) right back over the sweeper's write — the
     * optimistic {@code VERSION_} check does not protect this column at all, since the sweeper's
     * write deliberately does not bump {@code VERSION_} (see {@link #updateSlaStatusMonotonic}),
     * so the user's stale-read UPDATE still matches and silently overwrites {@code BREACHED} with
     * whatever the user last saw — permanently, since a record already {@code BREACHED} is never
     * re-selected by {@link org.casemgmt.repo.SlaRepository#dueRecords} and nothing else
     * re-derives the column. Dropping the column from this SET list closes that window
     * completely: nothing this method writes can ever race the sweeper's column again. The
     * returned {@link CaseInstance}'s {@code slaStatus()} still reflects the caller's
     * (possibly now-stale) view rather than a fresh read — consistent with this method's
     * no-re-read contract for every other column — but that staleness can no longer reach the
     * database.
     */
    public CaseInstance update(CaseInstance c, long expectedVersion) {
        OffsetDateTime updatedAt = OffsetDateTime.now();
        String slaStatus = c.slaStatus() == null ? "NONE" : c.slaStatus();

        int rows = jdbc.sql("""
                UPDATE CM_CASE SET
                    TITLE_ = :title, STATE_ = :state, PRIORITY_ = :priority,
                    ASSIGNEE_ = :assignee, QUEUE_ID_ = :queueId,
                    OUTCOME_ = :outcome, CANCEL_REASON_ = :cancelReason,
                    VARIABLES_JSON_ = :variables, CLOSED_AT_ = :closedAt,
                    UPDATED_AT_ = :updatedAt, VERSION_ = VERSION_ + 1
                WHERE ID_ = :id AND VERSION_ = :expected""")
            .param("title", c.title()).param("state", c.state().name())
            .param("priority", c.priority().name()).param("assignee", c.assignee())
            .param("queueId", c.queueId())
            .param("outcome", c.outcome()).param("cancelReason", c.cancelReason())
            .param("variables", JsonCodec.toJson(c.variables()))
            .param("closedAt", c.closedAt())
            .param("updatedAt", updatedAt)
            .param("id", c.id()).param("expected", expectedVersion)
            .update();

        if (rows == 0) {
            throw new OptimisticLockException("Case", c.id(), expectedVersion);
        }
        return new CaseInstance(c.id(), c.engineId(), c.tenantId(), c.caseDefId(), c.caseDefKey(),
                c.caseDefVersion(), c.businessKey(), c.title(), c.state(), c.priority(),
                c.assignee(), c.queueId(), c.initiator(), slaStatus, c.outcome(), c.cancelReason(),
                c.variables(), expectedVersion + 1, c.createdAt(), updatedAt, c.closedAt(),
                c.rootProcessInstanceId(), c.projectionStatus(), c.lastEngineUpdateAt(),
                c.lastProjectedAt());
    }

    /**
     * Persists the user-supplied cancellation reason after a synchronous embedded engine
     * callback has already made the authoritative ACTIVE-to-CANCELLED transition.
     *
     * <p>This deliberately owns only cancellation metadata. Reusing {@link #update} here would
     * rewrite every mutable case column from a callback-era snapshot and would obscure that the
     * engine observation, not the API service, owns the state transition and cancellation event.
     */
    public CaseInstance updateCancellationReason(
            CaseInstance cancelled, String reason, long expectedVersion) {
        if (cancelled.state() != CaseState.CANCELLED) {
            throw new IllegalArgumentException("Cancellation metadata requires a CANCELLED case");
        }
        OffsetDateTime updatedAt = OffsetDateTime.now();
        int rows = jdbc.sql("""
                UPDATE CM_CASE SET CANCEL_REASON_ = :reason, UPDATED_AT_ = :updatedAt,
                    VERSION_ = VERSION_ + 1
                WHERE ID_ = :id AND STATE_ = 'CANCELLED' AND VERSION_ = :expected""")
                .param("reason", reason)
                .param("updatedAt", updatedAt)
                .param("id", cancelled.id())
                .param("expected", expectedVersion)
                .update();
        if (rows == 0) {
            throw new OptimisticLockException("Case", cancelled.id(), expectedVersion);
        }
        return require(cancelled.id());
    }

    /**
     * Atomically applies canonical fields only when both their captured values and the case
     * version still match.
     *
     * <p>The row is selected {@code FOR UPDATE}, then compared and updated while that lock is
     * held. This method deliberately opens no transaction of its own: callers must already be
     * inside a Spring-managed transaction using the repository's {@code DataSource}. A naked or
     * auto-commit invocation is rejected because its row lock would otherwise be released after
     * the SELECT, reopening the compare/write race this method exists to close. Its internal
     * {@code PROPAGATION_MANDATORY} template is only a manager-specific participation guard: it
     * cannot start, suspend, or independently commit a transaction.
     */
    public CaseDataMappingService.PatchResult applyCanonicalPatch(CanonicalPatch patch) {
        if (patch == null) {
            throw new IllegalArgumentException("patch must not be null");
        }
        if (mandatoryCanonicalTransaction == null) {
            throw new IllegalStateException("Canonical compare-and-apply requires an active caller "
                    + "transaction and a transaction-verifiable repository DataSource");
        }
        try {
            return mandatoryCanonicalTransaction.execute(
                    status -> applyCanonicalPatchInCallerTransaction(patch));
        } catch (IllegalTransactionStateException missingTransaction) {
            throw new IllegalStateException("Canonical compare-and-apply requires an active caller "
                    + "transaction bound to the repository DataSource", missingTransaction);
        }
    }

    private CaseDataMappingService.PatchResult applyCanonicalPatchInCallerTransaction(
            CanonicalPatch patch) {
        CaseInstance current = requireForCanonicalUpdate(patch.caseId());
        if (patch.changes().isEmpty()) {
            return CaseDataMappingService.PatchResult.noChanges(current.version());
        }

        List<CaseDataMappingService.FieldConflict> valueConflicts = conflicts(patch, current);
        if (current.version() != patch.expectedCaseVersion() || !valueConflicts.isEmpty()) {
            return conflict(patch, current, valueConflicts);
        }

        Map<String, Object> variables = new LinkedHashMap<>(current.variables());
        for (CanonicalPatch.FieldChange change : patch.changes()) {
            variables.put(change.fieldId(), change.value());
        }
        OffsetDateTime updatedAt = OffsetDateTime.now();
        int rows = jdbc.sql("""
                UPDATE CM_CASE
                SET VARIABLES_JSON_ = :variables, UPDATED_AT_ = :updatedAt,
                    VERSION_ = VERSION_ + 1
                WHERE ID_ = :id AND VERSION_ = :expectedVersion""")
                .param("variables", JsonCodec.toJson(variables))
                .param("updatedAt", updatedAt)
                .param("id", patch.caseId())
                .param("expectedVersion", patch.expectedCaseVersion())
                .update();
        if (rows == 1) {
            return CaseDataMappingService.PatchResult.applied(patch.expectedCaseVersion() + 1);
        }
        throw new IllegalStateException("Locked canonical update unexpectedly affected no rows for case "
                + patch.caseId());
    }

    private CaseInstance requireForCanonicalUpdate(String id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM CM_CASE WHERE ID_ = :id FOR UPDATE")
                .param("id", id)
                .query(CaseRepository::map)
                .optional()
                .orElseThrow(() -> new NotFoundException("Case", id));
    }

    private static TransactionTemplate mandatoryTransaction(DataSource repositoryDataSource) {
        DataSource transactionResource = repositoryDataSource;
        while (transactionResource instanceof TransactionAwareDataSourceProxy proxy) {
            transactionResource = proxy.getTargetDataSource();
            if (transactionResource == null) {
                throw new IllegalArgumentException(
                        "TransactionAwareDataSourceProxy must have a target DataSource");
            }
        }
        TransactionTemplate mandatory = new TransactionTemplate(
                new DataSourceTransactionManager(transactionResource));
        mandatory.setPropagationBehavior(TransactionDefinition.PROPAGATION_MANDATORY);
        return mandatory;
    }

    private static List<CaseDataMappingService.FieldConflict> conflicts(
            CanonicalPatch patch, CaseInstance current) {
        List<CaseDataMappingService.FieldConflict> conflicts = new ArrayList<>();
        for (CanonicalPatch.FieldChange change : patch.changes()) {
            boolean present = current.variables().containsKey(change.fieldId());
            Object actual = current.variables().get(change.fieldId());
            if (present != change.expectedPresent() || !jsonEquals(actual, change.expectedValue())) {
                conflicts.add(new CaseDataMappingService.FieldConflict(change.fieldId(),
                        change.sensitive() ? CanonicalPatch.REDACTED : change.expectedValue(),
                        change.sensitive() ? CanonicalPatch.REDACTED : actual,
                        change.sensitive()));
            }
        }
        return List.copyOf(conflicts);
    }

    private static boolean jsonEquals(Object left, Object right) {
        if (left instanceof Number leftNumber && right instanceof Number rightNumber) {
            try {
                return new BigDecimal(leftNumber.toString())
                        .compareTo(new BigDecimal(rightNumber.toString())) == 0;
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
        if (left instanceof Map<?, ?> leftMap && right instanceof Map<?, ?> rightMap) {
            if (!leftMap.keySet().equals(rightMap.keySet())) return false;
            return leftMap.entrySet().stream().allMatch(entry ->
                    jsonEquals(entry.getValue(), rightMap.get(entry.getKey())));
        }
        if (left instanceof List<?> leftList && right instanceof List<?> rightList) {
            if (leftList.size() != rightList.size()) return false;
            for (int index = 0; index < leftList.size(); index++) {
                if (!jsonEquals(leftList.get(index), rightList.get(index))) return false;
            }
            return true;
        }
        return java.util.Objects.equals(left, right);
    }

    private static CaseDataMappingService.PatchResult conflict(
            CanonicalPatch patch, CaseInstance current,
            List<CaseDataMappingService.FieldConflict> valueConflicts) {
        return CaseDataMappingService.PatchResult.conflict(current.version(),
                new CaseDataMappingService.ConflictMetadata(patch.expectedCaseVersion(),
                        current.version(), valueConflicts));
    }

    /**
     * Targeted, versionless write of the denormalised {@code SLA_STATUS_} column only — added
     * for {@code SlaSweeper} (Task 21 fix round 1, review finding I2/I3).
     *
     * <p>Deliberately NOT the full-row {@link #update} above, and deliberately does not bump
     * {@code VERSION_}: {@code SlaSweeper} runs on a schedule (Task 26: every 60s) against every
     * case with a due SLA clock, and {@code update}'s optimistic {@code VERSION_} check makes it
     * collide with ANY ordinary user editing ANY unrelated field on the SAME case at the SAME
     * time — not a rare race, a routine one, on a live user-facing table. A plain user edit
     * should never fail with 412 just because the sweeper happened to run a moment earlier, and
     * the sweeper should never have to retry (or abort a whole batch, see {@code SlaSweeper}'s
     * Javadoc) just because a user saved the case's title. {@code SLA_STATUS_} is explicitly
     * documented as denormalized in {@code db-design.sql} for exactly this reason: it is owned by
     * the sweeper, not by the user's optimistic version. Since fix round 2, {@link #update} above
     * no longer writes this column at all (see its Javadoc) — this method is now the ONLY writer
     * of {@code SLA_STATUS_} after row creation, not merely the recommended one.
     *
     * <p>Monotonic against downgrade from {@code BREACHED}: {@link
     * org.casemgmt.repo.SlaRepository#dueRecords} has no stable ordering, so a case with one
     * target already breached and another only warning could otherwise have its status flip
     * depending on which record the sweeper happens to process last in a batch, or which of two
     * separate sweeps runs later — a warning must never mask a breach. The {@code WHERE} clause
     * below is the single, race-free place that rule is enforced: it reads and compares the
     * current value in the same statement as the write, so there is no read-then-write gap for a
     * concurrent sweep to land in between.
     *
     * @return true if a row was actually changed (false for an unknown case id, or a same-status
     *         no-op, or a rejected downgrade — none of which the sweeper needs to react to)
     */
    public boolean updateSlaStatusMonotonic(String caseId, String status) {
        int rows = jdbc.sql("""
                UPDATE CM_CASE SET SLA_STATUS_ = :status
                WHERE ID_ = :id AND SLA_STATUS_ <> :status
                  AND NOT (SLA_STATUS_ = 'BREACHED' AND :status = 'WARNING')""")
            .param("status", status).param("id", caseId).update();
        return rows > 0;
    }

    public List<CaseInstance> query(CaseQuery q) {
        Predicate predicate = predicate(q);
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM CM_CASE")
                .append(predicate.where());
        // CREATED_AT_ alone is not a stable sort key: rows created in the same instant (or
        // truncated to the same stored precision) would otherwise have undefined relative
        // order between paginated calls, which can skip or duplicate rows across pages in a
        // worklist. ID_ is unique, so it makes the ordering — and therefore the pagination —
        // deterministic.
        sql.append(orderBy(q));
        sql.append(" OFFSET :offset ROWS FETCH NEXT :limit ROWS ONLY");

        var spec = jdbc.sql(sql.toString());
        for (Object[] p : predicate.params()) {
            spec = spec.param((String) p[0], p[1]);
        }
        return spec.param("offset", q.offset())
                   .param("limit", q.limit() <= 0 ? 50 : q.limit())
                   .query(CaseRepository::map)
                   .list();
    }

    public List<CaseInstance> search(CaseSearchQuery q) {
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM CM_CASE WHERE 1 = 1");
        List<Object[]> params = new ArrayList<>();
        String normalizedText = q.text() == null ? null : q.text().toLowerCase(Locale.ROOT);
        if (q.tenantId() != null)    { sql.append(" AND TENANT_ID_ = :tenantId");     params.add(new Object[]{"tenantId", q.tenantId()}); }
        if (!q.states().isEmpty())   { sql.append(" AND STATE_ IN (:states)");
                                       params.add(new Object[]{"states",
                                               q.states().stream().map(CaseState::name).toList()}); }
        if (q.assignee() != null)    { sql.append(" AND ASSIGNEE_ = :assignee");      params.add(new Object[]{"assignee", q.assignee()}); }
        if (q.caseDefKey() != null)  { sql.append(" AND CASE_DEF_KEY_ = :defKey");    params.add(new Object[]{"defKey", q.caseDefKey()}); }
        if (q.businessKey() != null) { sql.append(" AND BUSINESS_KEY_ = :bk");        params.add(new Object[]{"bk", q.businessKey()}); }
        if (normalizedText != null) {
            sql.append("""
                     AND (
                        LOWER(ID_) = :searchExact
                        OR LOWER(BUSINESS_KEY_) = :searchExact
                        OR LOWER(BUSINESS_KEY_) LIKE :searchLike ESCAPE '~'
                        OR LOWER(TITLE_) LIKE :searchLike ESCAPE '~'
                    )""");
            params.add(new Object[]{"searchExact", normalizedText});
            params.add(new Object[]{"searchLike", containsLike(normalizedText)});
        }

        if (normalizedText == null) {
            sql.append(" ORDER BY UPDATED_AT_ DESC, CREATED_AT_ DESC, ID_ ASC");
        } else {
            sql.append("""
                     ORDER BY
                      CASE
                        WHEN LOWER(ID_) = :rankingExact THEN 0
                        WHEN LOWER(BUSINESS_KEY_) = :rankingExact THEN 1
                        WHEN LOWER(BUSINESS_KEY_) LIKE :rankingPrefix ESCAPE '~' THEN 2
                        WHEN LOWER(TITLE_) LIKE :rankingPrefix ESCAPE '~' THEN 3
                        ELSE 4
                      END,
                      UPDATED_AT_ DESC, CREATED_AT_ DESC, ID_ ASC""");
        }
        sql.append(" OFFSET :offset ROWS FETCH NEXT :limit ROWS ONLY");

        var spec = jdbc.sql(sql.toString());
        if (normalizedText != null) {
            spec = spec.param("rankingExact", normalizedText)
                       .param("rankingPrefix", startsWithLike(normalizedText));
        }
        for (Object[] p : params) {
            spec = spec.param((String) p[0], p[1]);
        }
        return spec.param("offset", q.offset())
                   .param("limit", q.limit() <= 0 ? 25 : q.limit())
                   .query(CaseRepository::map)
                   .list();
    }

    private static String startsWithLike(String value) {
        return value
                .replace("~", "~~")
                .replace("%", "~%")
                .replace("_", "~_") + "%";
    }

    public long count(CaseQuery q) {
        Predicate predicate = predicate(q);
        var spec = jdbc.sql("SELECT COUNT(*) FROM CM_CASE" + predicate.where());
        for (Object[] p : predicate.params()) {
            spec = spec.param((String) p[0], p[1]);
        }
        return spec.query(Long.class).single();
    }

    private static Predicate predicate(CaseQuery q) {
        StringBuilder sql = new StringBuilder(" WHERE 1 = 1");
        List<Object[]> params = new ArrayList<>();
        if (q.tenantId() != null) {
            sql.append(" AND TENANT_ID_ = :tenantId");
            params.add(new Object[]{"tenantId", q.tenantId()});
        }
        if (!q.states().isEmpty()) {
            sql.append(" AND STATE_ IN (:states)");
            params.add(new Object[]{"states", q.states().stream().map(CaseState::name).toList()});
        }
        if (q.assignee() != null) {
            sql.append(" AND ASSIGNEE_ = :assignee");
            params.add(new Object[]{"assignee", q.assignee()});
        }
        if (q.caseDefKey() != null) {
            sql.append(" AND CASE_DEF_KEY_ = :defKey");
            params.add(new Object[]{"defKey", q.caseDefKey()});
        }
        if (q.businessKey() != null) {
            sql.append(" AND BUSINESS_KEY_ = :bk");
            params.add(new Object[]{"bk", q.businessKey()});
        }
        if (q.participantUser() != null) {
            sql.append(" " + """
                    AND EXISTS (
                        SELECT 1 FROM CM_PARTICIPANT p
                        WHERE p.CASE_ID_ = CM_CASE.ID_ AND p.USER_ID_ = :participantUser)""");
            params.add(new Object[]{"participantUser", q.participantUser()});
        }
        if (q.queueId() != null) {
            sql.append(" AND QUEUE_ID_ = :queueId");
            params.add(new Object[]{"queueId", q.queueId()});
        }
        if (q.slaStatus() != null) {
            sql.append(" AND SLA_STATUS_ = :slaStatus");
            params.add(new Object[]{"slaStatus", q.slaStatus()});
        }
        if (q.priority() != null) {
            sql.append(" AND PRIORITY_ = :priority");
            params.add(new Object[]{"priority", q.priority().name()});
        }
        if (q.createdAfter() != null) {
            sql.append(" AND CREATED_AT_ >= :createdAfter");
            params.add(new Object[]{"createdAfter", q.createdAfter()});
        }
        if (q.createdBefore() != null) {
            sql.append(" AND CREATED_AT_ < :createdBefore");
            params.add(new Object[]{"createdBefore", q.createdBefore()});
        }
        if (q.freeText() != null && !q.freeText().isBlank()) {
            String needle = q.freeText().toLowerCase(Locale.ROOT);
            sql.append(" " + """
                    AND (
                        LOWER(TITLE_) LIKE :freeTextLike ESCAPE '~'
                        OR LOWER(BUSINESS_KEY_) LIKE :freeTextLike ESCAPE '~'
                        OR EXISTS (
                            SELECT 1 FROM CM_COMMENT cm
                            WHERE cm.CASE_ID_ = CM_CASE.ID_
                              AND DBMS_LOB.INSTR(LOWER(cm.TEXT_), :freeTextNeedle) > 0
                        )
                    )""");
            params.add(new Object[]{"freeTextLike", containsLike(needle)});
            params.add(new Object[]{"freeTextNeedle", needle});
        }
        return new Predicate(sql.toString(), params);
    }

    private static String containsLike(String value) {
        return "%" + value.replace("~", "~~").replace("%", "~%").replace("_", "~_") + "%";
    }

    private static String orderBy(CaseQuery q) {
        if (q.sort().isEmpty()) {
            return " ORDER BY CREATED_AT_ DESC, ID_ ASC";
        }
        List<String> terms = new ArrayList<>();
        for (CaseQuery.SortTerm term : q.sort()) {
            terms.add(sortColumn(term.field()) + (term.descending() ? " DESC" : " ASC"));
        }
        terms.add("ID_ ASC");
        return " ORDER BY " + String.join(", ", terms);
    }

    private static String sortColumn(String field) {
        return switch (field) {
            case "createdAt" -> "CREATED_AT_";
            case "updatedAt" -> "UPDATED_AT_";
            case "closedAt" -> "CLOSED_AT_";
            case "priority" -> """
                    CASE PRIORITY_
                        WHEN 'LOW' THEN 1
                        WHEN 'MEDIUM' THEN 2
                        WHEN 'HIGH' THEN 3
                        WHEN 'CRITICAL' THEN 4
                        ELSE 0
                    END""";
            case "state" -> "STATE_";
            case "title" -> "TITLE_";
            case "businessKey" -> "BUSINESS_KEY_";
            default -> throw new IllegalArgumentException("Unsupported case sort field: " + field);
        };
    }

    private record Predicate(String where, List<Object[]> params) {}

    private static CaseInstance map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new CaseInstance(
                rs.getString("ID_"), rs.getString("ENGINE_ID_"), rs.getString("TENANT_ID_"),
                rs.getString("CASE_DEF_ID_"), rs.getString("CASE_DEF_KEY_"), rs.getInt("CASE_DEF_VER_"),
                rs.getString("BUSINESS_KEY_"), rs.getString("TITLE_"),
                CaseState.valueOf(rs.getString("STATE_")),
                CasePriority.valueOf(rs.getString("PRIORITY_")),
                rs.getString("ASSIGNEE_"), rs.getString("QUEUE_ID_"), rs.getString("INITIATOR_"),
                rs.getString("SLA_STATUS_"), rs.getString("OUTCOME_"), rs.getString("CANCEL_REASON_"),
                JsonCodec.toMap(rs.getString("VARIABLES_JSON_")),
                rs.getLong("VERSION_"),
                rs.getObject("CREATED_AT_", OffsetDateTime.class),
                rs.getObject("UPDATED_AT_", OffsetDateTime.class),
                rs.getObject("CLOSED_AT_", OffsetDateTime.class),
                rs.getString("ROOT_PROC_INST_ID_"),
                org.casemgmt.projection.ProjectionStatus.valueOf(rs.getString("PROJECTION_STATUS_")),
                rs.getObject("LAST_ENGINE_UPDATE_AT_", OffsetDateTime.class),
                rs.getObject("LAST_PROJECTED_AT_", OffsetDateTime.class));
    }
}
