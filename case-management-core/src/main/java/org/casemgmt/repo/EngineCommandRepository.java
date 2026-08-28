package org.casemgmt.repo;

import org.casemgmt.engine.EngineCommand;
import org.casemgmt.engine.EngineCommandPersistenceMapper;
import org.casemgmt.engine.EngineCommandPolicy;
import org.casemgmt.engine.EngineCommandStatus;
import org.casemgmt.engine.CommandDispatchOutcome;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.List;
import java.util.UUID;

/**
 * Persistence for {@code CM_ENGINE_COMMAND} (spec §3.5) — the remote-mode engine command
 * outbox. {@link org.casemgmt.engine.OutboxEngineGateway} enqueues rows in the caller's
 * transaction; {@link org.casemgmt.engine.EngineCommandDispatcher} claims and delivers them.
 */
public class EngineCommandRepository {

    private static final String PRODUCTION_COLUMNS = """
            ID_, CASE_ID_, TENANT_ID_, OPERATION_ID_, IDEMPOTENCY_KEY_, PAYLOAD_DIGEST_,
            TYPE_, PAYLOAD_JSON_, TARGET_IDENTITY_, CORRELATION_JSON_, CANONICAL_PATCH_JSON_,
            EXPECTED_CASE_VERSION_, STATUS_, NEXT_ATTEMPT_AT_, DECIDED_AT_, SAFE_ERROR_CODE_,
            SAFE_SUMMARY_, TOTAL_DISPATCH_ATTEMPTS_, AUTO_ATTEMPTS_, BUDGET_EPOCH_,
            AUTO_BUDGET_RESET_, ROW_VERSION_, ACTION_COUNT_, ACTION_HIGH_WATER_,
            ACTION_RESET_COUNT_, ACTION_CANCEL_COUNT_, CONFIRM_SOURCE_, REMOTE_IDENTITY_,
            REMOTE_STATE_, EVIDENCE_REFERENCE_, DECISION_REVIEW_FINDING_,
            DECISION_REVIEW_SOURCE_, DECISION_REVIEW_REF_, LEGACY_ROW_ID_, LEGACY_STATUS_,
            LEGACY_MIGRATION_REF_, LEGACY_MIGRATED_AT_, LEGACY_FAILURE_COUNT_,
            LEASE_TOKEN_, LEASE_OWNER_, LEASE_EXPIRES_AT_, DISPATCHED_AT_, CONFIRMED_AT_,
            FAILED_AT_, RAW_LEGACY_PAYLOAD_, RAW_LEGACY_ERROR_, RAW_LEGACY_CLAIM_TOKEN_,
            RAW_LEGACY_CLAIMED_AT_, ORIGINAL_STATUS_, CREATED_AT_, UPDATED_AT_""";

    /** Compatibility lease used until Task 3 passes explicit lease ownership to the dispatcher. */
    static final Duration CLAIM_LEASE = Duration.ofMinutes(5);

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    public EngineCommandRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = null;
    }

    /** Production constructor: JDBC and local transactions are derived from one exact resource. */
    public EngineCommandRepository(DataSource dataSource) {
        DataSource resource = Objects.requireNonNull(dataSource, "dataSource");
        while (resource instanceof TransactionAwareDataSourceProxy proxy) {
            resource = Objects.requireNonNull(proxy.getTargetDataSource(), "targetDataSource");
        }
        this.jdbc = JdbcClient.create(dataSource);
        this.transactions = new TransactionTemplate(new DataSourceTransactionManager(resource));
    }

    public void enqueue(EngineCommand c) {
        String payloadJson = JsonCodec.toJson(c.payload());
        String tenant = c.payload().get("tenantId") instanceof String value && !value.isBlank()
                ? value : "__legacy_runtime__";
        submit(new ProductionCommandRequest(
                c.id(), c.caseId(), tenant, c.id(), "legacy-runtime:" + c.id(),
                sha256(payloadJson), c.type(), c.payload(), legacyTarget(c),
                null, null, null, OffsetDateTime.now()));
    }

    /** Transitional PoC dispatcher adapter; production callers retain the returned lease token. */
    @Deprecated(forRemoval = false)
    public List<EngineCommand> claimDue(int limit) {
        OffsetDateTime now = OffsetDateTime.now();
        recoverExpiredLeases(now);
        return claimDue("legacy-dispatcher:" + UUID.randomUUID(), limit, now, CLAIM_LEASE)
                .stream().map(leased -> {
                    StoredCommand stored = leased.command();
                    EngineCommandPolicy.Decision decision = stored.state().committedDecision();
                    return new EngineCommand(stored.commandId(), stored.caseId(),
                            stored.state().command().commandType(), stored.payload(),
                            decision.status().name(),
                            Math.max(0, decision.automaticAttemptsInBudget() - 1),
                            decision.nextAttemptAt(), decision.safeSummary());
                }).toList();
    }

    @Deprecated(forRemoval = false)
    public void markDone(String id) {
        jdbc.sql("""
                UPDATE CM_ENGINE_COMMAND SET STATUS_ = 'AWAITING_CONFIRMATION',
                    SAFE_ERROR_CODE_='response.unconfirmed',
                    SAFE_SUMMARY_='Accepted response lacked matching confirmation evidence',
                    LEASE_TOKEN_=NULL, LEASE_OWNER_=NULL, LEASE_EXPIRES_AT_=NULL,
                    UPDATED_AT_=SYSTIMESTAMP, DECIDED_AT_=SYSTIMESTAMP,
                    ROW_VERSION_=ROW_VERSION_+1
                WHERE ID_ = :id AND STATUS_='DISPATCHING'""")
            .param("id", id).update();
    }

    @Deprecated(forRemoval = false)
    public void markRetry(String id, String error, OffsetDateTime nextAttempt) {
        jdbc.sql("""
                UPDATE CM_ENGINE_COMMAND SET STATUS_ = 'RETRYABLE',
                    LAST_ERROR_ = NULL, NEXT_ATTEMPT_AT_ = :next,
                    SAFE_ERROR_CODE_='transport.not_sent',
                    SAFE_SUMMARY_='Remote request sent zero bytes',
                    LEASE_TOKEN_=NULL, LEASE_OWNER_=NULL, LEASE_EXPIRES_AT_=NULL,
                    UPDATED_AT_=SYSTIMESTAMP, DECIDED_AT_=SYSTIMESTAMP,
                    ROW_VERSION_=ROW_VERSION_+1
                WHERE ID_ = :id AND STATUS_='DISPATCHING'""")
            .param("next", nextAttempt).param("id", id).update();
    }

    @Deprecated(forRemoval = false)
    public void markDead(String id, String error) {
        jdbc.sql("""
                UPDATE CM_ENGINE_COMMAND SET STATUS_ = 'FAILED',
                    LAST_ERROR_ = NULL, SAFE_ERROR_CODE_='attempts.exhausted',
                    SAFE_SUMMARY_='Remote command exhausted automatic dispatch attempts',
                    LEASE_TOKEN_=NULL, LEASE_OWNER_=NULL, LEASE_EXPIRES_AT_=NULL,
                    FAILED_AT_=SYSTIMESTAMP, UPDATED_AT_=SYSTIMESTAMP, DECIDED_AT_=SYSTIMESTAMP,
                    ROW_VERSION_=ROW_VERSION_+1
                WHERE ID_ = :id AND STATUS_='DISPATCHING'""")
            .param("id", id).update();
    }

    public List<EngineCommand> findDead(int limit) {
        return jdbc.sql("""
                SELECT ID_, CASE_ID_, TYPE_, PAYLOAD_JSON_, STATUS_, ATTEMPTS_,
                       NEXT_ATTEMPT_AT_, SAFE_SUMMARY_
                FROM CM_ENGINE_COMMAND WHERE STATUS_ = 'FAILED'
                ORDER BY CREATED_AT_ FETCH FIRST :limit ROWS ONLY""")
                .param("limit", Math.clamp(limit, 1, 200))
                .query((rs, n) -> new EngineCommand(rs.getString("ID_"), rs.getString("CASE_ID_"),
                        EngineCommand.Type.valueOf(rs.getString("TYPE_")),
                        JsonCodec.toMap(rs.getString("PAYLOAD_JSON_")), rs.getString("STATUS_"),
                        rs.getInt("ATTEMPTS_"),
                        rs.getObject("NEXT_ATTEMPT_AT_", OffsetDateTime.class),
                        rs.getString("SAFE_SUMMARY_")))
                .list();
    }

    /** Task 5 replaces this PoC endpoint with an authorised normalized operator action. */
    @Deprecated(forRemoval = false)
    public boolean retryDead(String id) {
        return false;
    }

    /** Inserts a production operation or returns the original same-payload idempotent result. */
    public Submission submit(ProductionCommandRequest request) {
        Objects.requireNonNull(request, "request");
        try {
            return inTransaction(() -> {
                jdbc.sql("""
                        INSERT INTO CM_ENGINE_COMMAND
                          (ID_, CASE_ID_, TENANT_ID_, OPERATION_ID_, IDEMPOTENCY_KEY_,
                           PAYLOAD_DIGEST_, TYPE_, PAYLOAD_JSON_, TARGET_IDENTITY_,
                           CORRELATION_JSON_, CANONICAL_PATCH_JSON_, EXPECTED_CASE_VERSION_,
                           STATUS_, ATTEMPTS_, NEXT_ATTEMPT_AT_, CREATED_AT_, UPDATED_AT_,
                           DECIDED_AT_, TOTAL_DISPATCH_ATTEMPTS_, AUTO_ATTEMPTS_, BUDGET_EPOCH_,
                           AUTO_BUDGET_RESET_, ROW_VERSION_, ACTION_COUNT_, ACTION_HIGH_WATER_,
                           ACTION_RESET_COUNT_, ACTION_CANCEL_COUNT_)
                        VALUES
                          (:id, :caseId, :tenantId, :operationId, :idempotencyKey,
                           :payloadDigest, :type, :payload, :target, :correlation, :patch,
                           :expectedCaseVersion, 'PENDING', 0, NULL, :submittedAt,
                           :submittedAt, :submittedAt, 0, 0, 0, 0, 0, 0, 0, 0, 0)
                        """)
                        .param("id", request.commandId())
                        .param("caseId", request.caseId())
                        .param("tenantId", request.tenantId())
                        .param("operationId", request.operationId())
                        .param("idempotencyKey", request.idempotencyKey())
                        .param("payloadDigest", request.payloadDigest())
                        .param("type", request.commandType().name())
                        .param("payload", JsonCodec.toJson(request.payload()))
                        .param("target", request.expectedTargetIdentity())
                        .param("correlation", request.correlationJson())
                        .param("patch", request.canonicalPatchJson())
                        .param("expectedCaseVersion", request.expectedCaseVersion())
                        .param("submittedAt", request.submittedAt())
                        .update();
                return new Submission(require(request.tenantId(), request.operationId()), false);
            });
        } catch (DataIntegrityViolationException duplicate) {
            Optional<StoredCommand> byIdempotency = findByIdempotency(
                    request.tenantId(), request.idempotencyKey());
            if (byIdempotency.isPresent()) {
                StoredCommand existing = byIdempotency.orElseThrow();
                if (!sameSubmission(existing, request)) {
                    throw new IdempotencyConflictException(
                            "Idempotency key is already bound to a different command intent");
                }
                return new Submission(existing, true);
            }
            if (findByOperation(request.tenantId(), request.operationId()).isPresent()) {
                throw new IdempotencyConflictException(
                        "Operation ID is already bound to a different idempotency key");
            }
            throw duplicate;
        }
    }

    /** Atomically leases due commands; attempts and row version advance with the lease. */
    public List<LeasedCommand> claimDue(
            String owner, int limit, OffsetDateTime now, Duration leaseDuration) {
        String safeOwner = required(owner, "owner", 128);
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(leaseDuration, "leaseDuration");
        if (leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }
        int boundedLimit = Math.clamp(limit, 1, 200);
        String token = UUID.randomUUID().toString();
        OffsetDateTime expiresAt = now.plus(leaseDuration);
        int claimed = jdbc.sql("""
                UPDATE CM_ENGINE_COMMAND
                SET STATUS_='DISPATCHING', LEASE_TOKEN_=:token, LEASE_OWNER_=:owner,
                    LEASE_EXPIRES_AT_=:expiresAt, DISPATCHED_AT_=:now, UPDATED_AT_=:now,
                    DECIDED_AT_=:now, TOTAL_DISPATCH_ATTEMPTS_=TOTAL_DISPATCH_ATTEMPTS_+1,
                    AUTO_ATTEMPTS_=AUTO_ATTEMPTS_+1, ROW_VERSION_=ROW_VERSION_+1,
                    SAFE_ERROR_CODE_=NULL, SAFE_SUMMARY_=NULL
                WHERE (STATUS_='PENDING' OR
                       (STATUS_='RETRYABLE' AND NEXT_ATTEMPT_AT_ <= :now))
                  AND AUTO_ATTEMPTS_ < :maxAttempts
                  AND ID_ IN (
                    SELECT ID_ FROM (
                      SELECT ID_ FROM CM_ENGINE_COMMAND
                      WHERE (STATUS_='PENDING' OR
                            (STATUS_='RETRYABLE' AND NEXT_ATTEMPT_AT_ <= :now))
                        AND AUTO_ATTEMPTS_ < :maxAttempts
                      ORDER BY CREATED_AT_, ID_)
                    WHERE ROWNUM <= :limit)
                """).param("token", token).param("owner", safeOwner)
                .param("expiresAt", expiresAt).param("now", now)
                .param("maxAttempts", EngineCommandPolicy.MAX_AUTOMATIC_ATTEMPTS)
                .param("limit", boundedLimit).update();
        if (claimed == 0) return List.of();
        return jdbc.sql("SELECT " + PRODUCTION_COLUMNS + " FROM CM_ENGINE_COMMAND "
                        + "WHERE LEASE_TOKEN_=:token ORDER BY CREATED_AT_, ID_")
                .param("token", token)
                .query((rs, row) -> {
                    StoredCommand command = mapStored(rs);
                    return new LeasedCommand(command, rs.getString("LEASE_TOKEN_"),
                            rs.getString("LEASE_OWNER_"),
                            rs.getObject("LEASE_EXPIRES_AT_", OffsetDateTime.class));
                }).list();
    }

    /** Quarantines expired possibly-sent work; it is never made due for a blind resend. */
    public int recoverExpiredLeases(OffsetDateTime now) {
        Objects.requireNonNull(now, "now");
        return jdbc.sql("""
                UPDATE CM_ENGINE_COMMAND
                SET STATUS_='AWAITING_CONFIRMATION', LEASE_TOKEN_=NULL, LEASE_OWNER_=NULL,
                    LEASE_EXPIRES_AT_=NULL, UPDATED_AT_=:now, DECIDED_AT_=:now,
                    SAFE_ERROR_CODE_='dispatch.lease_expired',
                    SAFE_SUMMARY_='Dispatch lease expired with an unknown remote outcome',
                    ROW_VERSION_=ROW_VERSION_+1
                WHERE STATUS_='DISPATCHING' AND LEASE_EXPIRES_AT_ <= :now
                """).param("now", now).update();
    }

    /**
     * Commits the policy decision for one owned dispatch lease. Both the opaque lease token and
     * optimistic row version are required, so a timed-out worker cannot overwrite recovery or a
     * newer dispatcher decision.
     */
    public StoredCommand commitLeaseDecision(
            String tenantId, String operationId, String leaseToken, long expectedVersion,
            EngineCommandPolicy.Decision decision) {
        required(leaseToken, "leaseToken", 64);
        Objects.requireNonNull(decision, "decision");
        if (decision.legacyConfirmation() != null) {
            throw new IllegalArgumentException(
                    "A live lease cannot create legacy migration provenance");
        }
        return inRequiredTransaction(() -> {
            StoredCommand current = require(tenantId, operationId);
            new EngineCommandPolicy.CommandState(current.state().command(), decision);
            CommandDispatchOutcome.ConfirmationEvidence confirmation =
                    decision.terminalConfirmation();
            CommandDispatchOutcome.ReviewEvidence review = decision.decisionEvidence();
            EngineCommandPolicy.ActionLedgerSummary summary = decision.actionLedgerSummary();
            int updated = jdbc.sql("""
                    UPDATE CM_ENGINE_COMMAND SET STATUS_=:status,
                      NEXT_ATTEMPT_AT_=:nextAttempt, DECIDED_AT_=:decidedAt,
                      UPDATED_AT_=:decidedAt, SAFE_ERROR_CODE_=:errorCode,
                      SAFE_SUMMARY_=:safeSummary,
                      TOTAL_DISPATCH_ATTEMPTS_=:totalAttempts,
                      AUTO_ATTEMPTS_=:autoAttempts, BUDGET_EPOCH_=:budgetEpoch,
                      AUTO_BUDGET_RESET_=:budgetReset,
                      CONFIRM_SOURCE_=:confirmSource, REMOTE_IDENTITY_=:remoteIdentity,
                      REMOTE_STATE_=:remoteState, EVIDENCE_REFERENCE_=:evidenceReference,
                      CONFIRMED_AT_=CASE WHEN :status='CONFIRMED' THEN :decidedAt ELSE NULL END,
                      FAILED_AT_=CASE WHEN :status='FAILED' THEN :decidedAt ELSE NULL END,
                      DECISION_REVIEW_FINDING_=:reviewFinding,
                      DECISION_REVIEW_SOURCE_=:reviewSource,
                      DECISION_REVIEW_REF_=:reviewReference,
                      LEASE_TOKEN_=NULL, LEASE_OWNER_=NULL, LEASE_EXPIRES_AT_=NULL,
                      ROW_VERSION_=ROW_VERSION_+1
                    WHERE ID_=:commandId AND TENANT_ID_=:tenantId
                      AND OPERATION_ID_=:operationId AND STATUS_='DISPATCHING'
                      AND LEASE_TOKEN_=:leaseToken AND ROW_VERSION_=:expectedVersion
                      AND ACTION_COUNT_=:actionCount AND ACTION_HIGH_WATER_=:highWater
                      AND ACTION_RESET_COUNT_=:resetCount AND ACTION_CANCEL_COUNT_=:cancelCount
                    """).param("status", decision.status().name())
                    .param("nextAttempt", decision.nextAttemptAt())
                    .param("decidedAt", decision.decidedAt())
                    .param("errorCode", decision.errorCode())
                    .param("safeSummary", decision.safeSummary())
                    .param("totalAttempts", decision.totalDispatchAttempts())
                    .param("autoAttempts", decision.automaticAttemptsInBudget())
                    .param("budgetEpoch", decision.budgetEpoch())
                    .param("budgetReset", decision.automaticBudgetReset() ? 1 : 0)
                    .param("confirmSource", confirmation == null ? null
                            : confirmation.source().name())
                    .param("remoteIdentity", confirmation == null ? null
                            : confirmation.remoteIdentity())
                    .param("remoteState", confirmation == null ? null
                            : confirmation.remoteState().name())
                    .param("evidenceReference", confirmation == null ? null
                            : confirmation.evidenceReference())
                    .param("reviewFinding", review == null ? null : review.finding().name())
                    .param("reviewSource", review == null ? null : review.source().name())
                    .param("reviewReference", review == null ? null
                            : review.evidenceReference())
                    .param("commandId", current.commandId()).param("tenantId", tenantId)
                    .param("operationId", operationId).param("leaseToken", leaseToken)
                    .param("expectedVersion", expectedVersion)
                    .param("actionCount", summary.actionCount())
                    .param("highWater", summary.highWaterSequence())
                    .param("resetCount", summary.automaticBudgetResetCount())
                    .param("cancelCount", summary.cancellationCount()).update();
            if (updated != 1) {
                throw new OptimisticCommandException(
                        "Lease token, row version, or command state changed before commit");
            }
            return require(tenantId, operationId);
        });
    }

    public StoredCommand require(String tenantId, String operationId) {
        return jdbc.sql("SELECT " + PRODUCTION_COLUMNS + " FROM CM_ENGINE_COMMAND "
                        + "WHERE TENANT_ID_=:tenantId AND OPERATION_ID_=:operationId")
                .param("tenantId", tenantId).param("operationId", operationId)
                .query((rs, row) -> mapStored(rs)).optional()
                .orElseThrow(() -> new IllegalArgumentException("Engine operation was not found"));
    }

    public long countCommands() {
        return jdbc.sql("SELECT COUNT(*) FROM CM_ENGINE_COMMAND").query(Long.class).single();
    }

    public Optional<EngineCommandPolicy.ProcessedAction> findAction(
            String commandId, String actionId) {
        return jdbc.sql("""
                SELECT COMMAND_ID_, SEQUENCE_, ACTION_ID_, TENANT_ID_, OPERATION_ID_,
                       COMMAND_TYPE_, EXPECTED_TARGET_, ACTION_TYPE_, AUDIT_REFERENCE_,
                       PERFORMED_AT_, OVERRIDE_AUTO_CAP_, REVIEW_FINDING_, REVIEW_SOURCE_,
                       REVIEW_REFERENCE_
                FROM CM_ENGINE_COMMAND_ACTION
                WHERE COMMAND_ID_=:commandId AND ACTION_ID_=:actionId
                """).param("commandId", commandId).param("actionId", actionId)
                .query((rs, row) -> mapAction(rs)).optional();
    }

    /** Inserts the normalized action and advances command state/version in one local transaction. */
    public ActionCommit appendActionAndTransition(
            String commandId, long expectedVersion,
            EngineCommandPolicy.OperatorTransition transition) {
        Objects.requireNonNull(transition, "transition");
        EngineCommandPolicy.ActionAppend append = Objects.requireNonNull(
                transition.actionAppend(), "transition.actionAppend");
        return inRequiredTransaction(() -> {
            Optional<EngineCommandPolicy.ProcessedAction> existing = findAction(
                    commandId, append.action().action().actionId());
            if (existing.isPresent()) {
                return exactReplayOrConflict(commandId, transition,
                        existing.orElseThrow());
            }

            // Catch DUP_VAL_ON_INDEX inside Oracle, not in Spring. A translated integrity
            // exception can mark a caller-owned REQUIRED transaction rollback-only before the
            // repository has a chance to reload the winner. The normalized row is then read back
            // and classified as our insert, an exact concurrent replay, or a conflict.
            insertActionIgnoringDuplicate(commandId, append.action());
            Optional<EngineCommandPolicy.ProcessedAction> authoritative = findAction(
                    commandId, append.action().action().actionId());
            if (authoritative.isEmpty()) {
                throw new OptimisticCommandException(
                        "Action sequence was concurrently claimed by a different action");
            }
            if (!authoritative.orElseThrow().equals(append.action())) {
                throw new ActionIdentityConflictException(
                        "Operator action ID is bound to different evidence");
            }

            CommandCasState cas = commandCasState(commandId);
            if (cas.summary().equals(append.resultingSummary())) {
                return exactReplayOrConflict(commandId, transition,
                        authoritative.orElseThrow());
            }
            if (!cas.summary().equals(append.expectedSummary())
                    || cas.version() != expectedVersion) {
                throw new OptimisticCommandException(
                        "Command version or action summary changed during append");
            }
            int updated = updateDecision(commandId, expectedVersion, transition.decision(),
                    append.expectedSummary());
            if (updated != 1) {
                throw new OptimisticCommandException(
                        "Command version or action summary changed during append");
            }
            return ActionCommit.APPLIED;
        });
    }

    private ActionCommit exactReplayOrConflict(
            String commandId, EngineCommandPolicy.OperatorTransition transition,
            EngineCommandPolicy.ProcessedAction persisted) {
        EngineCommandPolicy.ProcessedAction requested = transition.actionAppend().action();
        if (!persisted.equals(requested)) {
            throw new ActionIdentityConflictException(
                    "Operator action ID is bound to different evidence");
        }
        CommandDispatchOutcome.OperatorAction action = persisted.action();
        StoredCommand committed = require(action.tenantId(), action.operationId());
        if (!committed.commandId().equals(commandId)
                || !committed.state().committedDecision().equals(transition.decision())) {
            throw new OptimisticCommandException(
                    "Operator action exists but its command transition is not an exact replay");
        }
        return ActionCommit.EXACT_REPLAY;
    }

    private CommandCasState commandCasState(String commandId) {
        return jdbc.sql("""
                SELECT ROW_VERSION_, ACTION_COUNT_, ACTION_HIGH_WATER_,
                       ACTION_RESET_COUNT_, ACTION_CANCEL_COUNT_
                FROM CM_ENGINE_COMMAND WHERE ID_=:commandId
                """).param("commandId", commandId)
                .query((rs, row) -> new CommandCasState(
                        rs.getLong("ROW_VERSION_"),
                        new EngineCommandPolicy.ActionLedgerSummary(
                                rs.getLong("ACTION_COUNT_"),
                                rs.getLong("ACTION_HIGH_WATER_"),
                                rs.getLong("ACTION_RESET_COUNT_"),
                                rs.getLong("ACTION_CANCEL_COUNT_"))))
                .optional().orElseThrow(() -> new OptimisticCommandException(
                        "Command disappeared during action append"));
    }

    private Optional<StoredCommand> findByIdempotency(String tenantId, String key) {
        return jdbc.sql("SELECT " + PRODUCTION_COLUMNS + " FROM CM_ENGINE_COMMAND "
                        + "WHERE TENANT_ID_=:tenantId AND IDEMPOTENCY_KEY_=:key")
                .param("tenantId", tenantId).param("key", key)
                .query((rs, row) -> mapStored(rs)).optional();
    }

    private Optional<StoredCommand> findByOperation(String tenantId, String operationId) {
        return jdbc.sql("SELECT " + PRODUCTION_COLUMNS + " FROM CM_ENGINE_COMMAND "
                        + "WHERE TENANT_ID_=:tenantId AND OPERATION_ID_=:operationId")
                .param("tenantId", tenantId).param("operationId", operationId)
                .query((rs, row) -> mapStored(rs)).optional();
    }

    private static boolean sameSubmission(
            StoredCommand existing, ProductionCommandRequest request) {
        return existing.payloadDigest().equals(request.payloadDigest())
                && existing.caseId().equals(request.caseId())
                && existing.state().command().commandType() == request.commandType()
                && existing.expectedTargetIdentity().equals(request.expectedTargetIdentity())
                && existing.payload().equals(request.payload())
                && Objects.equals(existing.correlationJson(), request.correlationJson())
                && Objects.equals(existing.canonicalPatchJson(), request.canonicalPatchJson())
                && Objects.equals(existing.expectedCaseVersion(), request.expectedCaseVersion());
    }

    private void insertActionIgnoringDuplicate(
            String commandId, EngineCommandPolicy.ProcessedAction row) {
        CommandDispatchOutcome.OperatorAction action = row.action();
        CommandDispatchOutcome.ReviewEvidence review = row.reviewEvidence();
        jdbc.sql("""
                BEGIN
                INSERT INTO CM_ENGINE_COMMAND_ACTION
                  (COMMAND_ID_, SEQUENCE_, ACTION_ID_, TENANT_ID_, OPERATION_ID_, COMMAND_TYPE_,
                   EXPECTED_TARGET_, ACTION_TYPE_, AUDIT_REFERENCE_, PERFORMED_AT_,
                   OVERRIDE_AUTO_CAP_, REVIEW_FINDING_, REVIEW_SOURCE_, REVIEW_REFERENCE_)
                VALUES (:commandId, :sequence, :actionId, :tenantId, :operationId, :commandType,
                        :target, :actionType, :auditReference, :performedAt, :overrideCap,
                        :reviewFinding, :reviewSource, :reviewReference);
                EXCEPTION WHEN DUP_VAL_ON_INDEX THEN NULL;
                END;
                """).param("commandId", commandId).param("sequence", row.sequence())
                .param("actionId", action.actionId()).param("tenantId", action.tenantId())
                .param("operationId", action.operationId())
                .param("commandType", action.commandType().name())
                .param("target", action.expectedTargetIdentity())
                .param("actionType", action.actionType().name())
                .param("auditReference", action.auditReference())
                .param("performedAt", action.performedAt())
                .param("overrideCap", action.overrideAutomaticAttemptCap() ? 1 : 0)
                .param("reviewFinding", review == null ? null : review.finding().name())
                .param("reviewSource", review == null ? null : review.source().name())
                .param("reviewReference", review == null ? null : review.evidenceReference())
                .update();
    }

    private int updateDecision(
            String commandId, long expectedVersion, EngineCommandPolicy.Decision decision,
            EngineCommandPolicy.ActionLedgerSummary expectedSummary) {
        EngineCommandPolicy.ActionLedgerSummary summary = decision.actionLedgerSummary();
        CommandDispatchOutcome.ReviewEvidence review = decision.decisionEvidence();
        return jdbc.sql("""
                UPDATE CM_ENGINE_COMMAND SET
                  STATUS_=:status, NEXT_ATTEMPT_AT_=:nextAttempt, DECIDED_AT_=:decidedAt,
                  UPDATED_AT_=:decidedAt, SAFE_ERROR_CODE_=:errorCode,
                  SAFE_SUMMARY_=:safeSummary, TOTAL_DISPATCH_ATTEMPTS_=:totalAttempts,
                  AUTO_ATTEMPTS_=:autoAttempts, BUDGET_EPOCH_=:budgetEpoch,
                  AUTO_BUDGET_RESET_=:budgetReset, ACTION_COUNT_=:actionCount,
                  ACTION_HIGH_WATER_=:highWater, ACTION_RESET_COUNT_=:resetCount,
                  ACTION_CANCEL_COUNT_=:cancelCount, DECISION_REVIEW_FINDING_=:reviewFinding,
                  DECISION_REVIEW_SOURCE_=:reviewSource, DECISION_REVIEW_REF_=:reviewReference,
                  ROW_VERSION_=ROW_VERSION_+1
                WHERE ID_=:commandId AND ROW_VERSION_=:expectedVersion
                  AND ACTION_COUNT_=:expectedCount AND ACTION_HIGH_WATER_=:expectedHighWater
                  AND ACTION_RESET_COUNT_=:expectedResetCount
                  AND ACTION_CANCEL_COUNT_=:expectedCancelCount
                """).param("status", decision.status().name())
                .param("nextAttempt", decision.nextAttemptAt())
                .param("decidedAt", decision.decidedAt())
                .param("errorCode", decision.errorCode())
                .param("safeSummary", decision.safeSummary())
                .param("totalAttempts", decision.totalDispatchAttempts())
                .param("autoAttempts", decision.automaticAttemptsInBudget())
                .param("budgetEpoch", decision.budgetEpoch())
                .param("budgetReset", decision.automaticBudgetReset() ? 1 : 0)
                .param("actionCount", summary.actionCount())
                .param("highWater", summary.highWaterSequence())
                .param("resetCount", summary.automaticBudgetResetCount())
                .param("cancelCount", summary.cancellationCount())
                .param("reviewFinding", review == null ? null : review.finding().name())
                .param("reviewSource", review == null ? null : review.source().name())
                .param("reviewReference", review == null ? null : review.evidenceReference())
                .param("commandId", commandId).param("expectedVersion", expectedVersion)
                .param("expectedCount", expectedSummary.actionCount())
                .param("expectedHighWater", expectedSummary.highWaterSequence())
                .param("expectedResetCount", expectedSummary.automaticBudgetResetCount())
                .param("expectedCancelCount", expectedSummary.cancellationCount()).update();
    }

    private StoredCommand mapStored(java.sql.ResultSet rs) throws java.sql.SQLException {
        String commandId = rs.getString("ID_");
        var context = new EngineCommandPolicy.CommandContext(
                rs.getString("TENANT_ID_"), rs.getString("OPERATION_ID_"), commandId,
                EngineCommand.Type.valueOf(rs.getString("TYPE_")),
                rs.getString("TARGET_IDENTITY_"));
        EngineCommandPolicy.ActionLedgerSummary summary = new EngineCommandPolicy.ActionLedgerSummary(
                rs.getLong("ACTION_COUNT_"), rs.getLong("ACTION_HIGH_WATER_"),
                rs.getLong("ACTION_RESET_COUNT_"), rs.getLong("ACTION_CANCEL_COUNT_"));
        verifyActionAggregates(commandId, summary);
        String confirmationSource = rs.getString("CONFIRM_SOURCE_");
        EngineCommandPolicy.Decision decision;
        if ("LEGACY_MIGRATION".equals(confirmationSource)) {
            Integer legacyFailureCount = rs.getObject("LEGACY_FAILURE_COUNT_", Integer.class);
            if (legacyFailureCount == null) {
                throw new IllegalArgumentException(
                        "Persisted legacy DONE provenance is missing its failure count");
            }
            decision = EngineCommandPersistenceMapper.rehydrateLegacyDone(
                    new EngineCommandPersistenceMapper.LegacyDoneDatabaseRow(
                            context, rs.getString("LEGACY_ROW_ID_"),
                            rs.getString("LEGACY_MIGRATION_REF_"),
                            rs.getObject("LEGACY_MIGRATED_AT_", OffsetDateTime.class),
                            legacyFailureCount));
            validateLegacyDoneRow(rs, context, decision);
        } else {
            CommandDispatchOutcome.ConfirmationEvidence confirmation = confirmationSource == null
                    ? null : new CommandDispatchOutcome.ConfirmationEvidence(
                    context.tenantId(), context.operationId(), context.commandId(),
                    context.commandType(), context.expectedTargetIdentity(),
                    rs.getString("REMOTE_IDENTITY_"),
                    CommandDispatchOutcome.RemoteState.valueOf(rs.getString("REMOTE_STATE_")),
                    CommandDispatchOutcome.ConfirmationSource.valueOf(confirmationSource),
                    rs.getString("EVIDENCE_REFERENCE_"));
            CommandDispatchOutcome.ReviewEvidence review = decisionReview(rs, context);
            EngineCommandPolicy.ProcessedAction applied = summary.actionCount() == 0
                    ? null : findActionBySequence(commandId, summary.highWaterSequence())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Command action high-water row is missing"));
            EngineCommandPolicy.ActionLedgerSummary prior = applied == null ? null
                    : aggregateActions(commandId, summary.highWaterSequence() - 1);
            decision = new EngineCommandPolicy.Decision(
                    EngineCommandStatus.valueOf(rs.getString("STATUS_")),
                    rs.getObject("DECIDED_AT_", OffsetDateTime.class),
                    rs.getObject("NEXT_ATTEMPT_AT_", OffsetDateTime.class),
                    rs.getString("SAFE_ERROR_CODE_"), rs.getString("SAFE_SUMMARY_"),
                    rs.getLong("TOTAL_DISPATCH_ATTEMPTS_"), rs.getInt("AUTO_ATTEMPTS_"),
                    rs.getLong("BUDGET_EPOCH_"), rs.getInt("AUTO_BUDGET_RESET_") == 1,
                    confirmation, null, review, applied, prior, summary);
        }
        String idempotencyKey = required(
                rs.getString("IDEMPOTENCY_KEY_"), "persisted idempotencyKey", 128);
        String payloadDigest = rs.getString("PAYLOAD_DIGEST_");
        if (payloadDigest == null || !payloadDigest.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException(
                    "Persisted payloadDigest must be lowercase SHA-256");
        }
        long version = rs.getLong("ROW_VERSION_");
        if (version < 0) {
            throw new IllegalArgumentException("Persisted command version must not be negative");
        }
        OffsetDateTime createdAt = Objects.requireNonNull(
                rs.getObject("CREATED_AT_", OffsetDateTime.class), "persisted createdAt");
        OffsetDateTime updatedAt = Objects.requireNonNull(
                rs.getObject("UPDATED_AT_", OffsetDateTime.class), "persisted updatedAt");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException(
                    "Persisted updatedAt must not precede createdAt");
        }
        return new StoredCommand(commandId, context.operationId(),
                idempotencyKey, payloadDigest,
                required(rs.getString("CASE_ID_"), "persisted caseId", 140),
                JsonCodec.toMap(rs.getString("PAYLOAD_JSON_")),
                rs.getString("TARGET_IDENTITY_"), rs.getString("CORRELATION_JSON_"),
                rs.getString("CANONICAL_PATCH_JSON_"),
                rs.getObject("EXPECTED_CASE_VERSION_", Long.class),
                new EngineCommandPolicy.CommandState(context, decision),
                version, createdAt, updatedAt);
    }

    private static void validateLegacyDoneRow(
            java.sql.ResultSet rs, EngineCommandPolicy.CommandContext context,
            EngineCommandPolicy.Decision decision) throws java.sql.SQLException {
        OffsetDateTime decidedAt = rs.getObject("DECIDED_AT_", OffsetDateTime.class);
        OffsetDateTime migratedAt = rs.getObject("LEGACY_MIGRATED_AT_", OffsetDateTime.class);
        OffsetDateTime confirmedAt = rs.getObject("CONFIRMED_AT_", OffsetDateTime.class);
        boolean exact = EngineCommandStatus.CONFIRMED.name().equals(rs.getString("STATUS_"))
                && Objects.equals(context.commandId(), rs.getString("LEGACY_ROW_ID_"))
                && "DONE".equals(rs.getString("LEGACY_STATUS_"))
                && "DONE".equals(rs.getString("ORIGINAL_STATUS_"))
                && "ws4-task2".equals(rs.getString("LEGACY_MIGRATION_REF_"))
                && Objects.equals(decidedAt, migratedAt)
                && Objects.equals(confirmedAt, migratedAt)
                && rs.getObject("NEXT_ATTEMPT_AT_") == null
                && rs.getString("SAFE_ERROR_CODE_") == null
                && rs.getString("SAFE_SUMMARY_") == null
                && rs.getString("REMOTE_IDENTITY_") == null
                && rs.getString("REMOTE_STATE_") == null
                && rs.getString("EVIDENCE_REFERENCE_") == null
                && rs.getString("DECISION_REVIEW_FINDING_") == null
                && rs.getString("DECISION_REVIEW_SOURCE_") == null
                && rs.getString("DECISION_REVIEW_REF_") == null
                && rs.getObject("FAILED_AT_") == null
                && rs.getLong("TOTAL_DISPATCH_ATTEMPTS_")
                    == decision.totalDispatchAttempts()
                && rs.getInt("AUTO_ATTEMPTS_") == decision.automaticAttemptsInBudget()
                && rs.getLong("BUDGET_EPOCH_") == 0
                && rs.getInt("AUTO_BUDGET_RESET_") == 0;
        if (!exact) {
            throw new IllegalArgumentException(
                    "Persisted legacy DONE provenance does not match its retained migration row");
        }
    }

    private CommandDispatchOutcome.ReviewEvidence decisionReview(
            java.sql.ResultSet rs, EngineCommandPolicy.CommandContext context)
            throws java.sql.SQLException {
        String finding = rs.getString("DECISION_REVIEW_FINDING_");
        return finding == null ? null : new CommandDispatchOutcome.ReviewEvidence(
                context.tenantId(), context.operationId(), context.commandId(),
                context.commandType(), context.expectedTargetIdentity(),
                CommandDispatchOutcome.ReviewFinding.valueOf(finding),
                CommandDispatchOutcome.ReviewSource.valueOf(
                        rs.getString("DECISION_REVIEW_SOURCE_")),
                rs.getString("DECISION_REVIEW_REF_"));
    }

    private void verifyActionAggregates(
            String commandId, EngineCommandPolicy.ActionLedgerSummary persisted) {
        EngineCommandPolicy.ActionLedgerSummary actual = aggregateActions(commandId, Long.MAX_VALUE);
        if (!persisted.equals(actual)) {
            throw new IllegalArgumentException(
                    "Persisted command action summary does not match normalized history");
        }
    }

    private EngineCommandPolicy.ActionLedgerSummary aggregateActions(
            String commandId, long throughSequence) {
        return jdbc.sql("""
                SELECT COUNT(*) ACTION_COUNT, NVL(MAX(SEQUENCE_),0) HIGH_WATER,
                       NVL(SUM(CASE WHEN ACTION_TYPE_='RETRY_OVERRIDE' AND OVERRIDE_AUTO_CAP_=1
                                    THEN 1 ELSE 0 END),0) RESET_COUNT,
                       NVL(SUM(CASE WHEN ACTION_TYPE_='CANCEL' THEN 1 ELSE 0 END),0) CANCEL_COUNT
                FROM CM_ENGINE_COMMAND_ACTION
                WHERE COMMAND_ID_=:commandId AND SEQUENCE_ <= :throughSequence
                """).param("commandId", commandId).param("throughSequence", throughSequence)
                .query((rs, row) -> new EngineCommandPolicy.ActionLedgerSummary(
                        rs.getLong("ACTION_COUNT"), rs.getLong("HIGH_WATER"),
                        rs.getLong("RESET_COUNT"), rs.getLong("CANCEL_COUNT"))).single();
    }

    private Optional<EngineCommandPolicy.ProcessedAction> findActionBySequence(
            String commandId, long sequence) {
        return jdbc.sql("""
                SELECT COMMAND_ID_, SEQUENCE_, ACTION_ID_, TENANT_ID_, OPERATION_ID_,
                       COMMAND_TYPE_, EXPECTED_TARGET_, ACTION_TYPE_, AUDIT_REFERENCE_,
                       PERFORMED_AT_, OVERRIDE_AUTO_CAP_, REVIEW_FINDING_, REVIEW_SOURCE_,
                       REVIEW_REFERENCE_
                FROM CM_ENGINE_COMMAND_ACTION
                WHERE COMMAND_ID_=:commandId AND SEQUENCE_=:sequence
                """).param("commandId", commandId).param("sequence", sequence)
                .query((rs, row) -> mapAction(rs)).optional();
    }

    private static EngineCommandPolicy.ProcessedAction mapAction(java.sql.ResultSet rs)
            throws java.sql.SQLException {
        CommandDispatchOutcome.OperatorAction action = new CommandDispatchOutcome.OperatorAction(
                rs.getString("TENANT_ID_"), rs.getString("OPERATION_ID_"),
                rs.getString("COMMAND_ID_"),
                EngineCommand.Type.valueOf(rs.getString("COMMAND_TYPE_")),
                rs.getString("EXPECTED_TARGET_"),
                CommandDispatchOutcome.ActionType.valueOf(rs.getString("ACTION_TYPE_")),
                rs.getString("ACTION_ID_"), rs.getString("AUDIT_REFERENCE_"),
                rs.getObject("PERFORMED_AT_", OffsetDateTime.class),
                rs.getInt("OVERRIDE_AUTO_CAP_") == 1);
        String finding = rs.getString("REVIEW_FINDING_");
        CommandDispatchOutcome.ReviewEvidence review = finding == null ? null
                : new CommandDispatchOutcome.ReviewEvidence(
                action.tenantId(), action.operationId(), action.commandId(), action.commandType(),
                action.expectedTargetIdentity(), CommandDispatchOutcome.ReviewFinding.valueOf(finding),
                CommandDispatchOutcome.ReviewSource.valueOf(rs.getString("REVIEW_SOURCE_")),
                rs.getString("REVIEW_REFERENCE_"));
        return new EngineCommandPolicy.ProcessedAction(rs.getLong("SEQUENCE_"), action, review);
    }

    private <T> T inTransaction(java.util.function.Supplier<T> work) {
        return transactions == null ? work.get() : transactions.execute(status -> work.get());
    }

    private <T> T inRequiredTransaction(java.util.function.Supplier<T> work) {
        if (transactions == null) {
            throw new IllegalStateException(
                    "Atomic command actions require the DataSource repository constructor");
        }
        return transactions.execute(status -> work.get());
    }

    private static String required(String value, String field, int max) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw new IllegalArgumentException(field + " must be 1-" + max + " characters");
        }
        return value;
    }

    public record ProductionCommandRequest(
            String commandId, String caseId, String tenantId, String operationId,
            String idempotencyKey, String payloadDigest, EngineCommand.Type commandType,
            java.util.Map<String, Object> payload, String expectedTargetIdentity,
            String correlationJson, String canonicalPatchJson, Long expectedCaseVersion,
            OffsetDateTime submittedAt) {
        public ProductionCommandRequest {
            commandId = required(commandId, "commandId", 64);
            caseId = required(caseId, "caseId", 140);
            tenantId = required(tenantId, "tenantId", 64);
            operationId = required(operationId, "operationId", 64);
            idempotencyKey = required(idempotencyKey, "idempotencyKey", 128);
            if (payloadDigest == null || !payloadDigest.matches("[a-f0-9]{64}")) {
                throw new IllegalArgumentException("payloadDigest must be lowercase SHA-256");
            }
            Objects.requireNonNull(commandType, "commandType");
            payload = java.util.Map.copyOf(Objects.requireNonNull(payload, "payload"));
            expectedTargetIdentity = required(
                    expectedTargetIdentity, "expectedTargetIdentity", 255);
            Objects.requireNonNull(submittedAt, "submittedAt");
        }
    }

    public record Submission(StoredCommand command, boolean replayed) {
        public Submission { Objects.requireNonNull(command, "command"); }
    }

    public record StoredCommand(
            String commandId, String operationId, String idempotencyKey, String payloadDigest,
            String caseId, java.util.Map<String, Object> payload, String expectedTargetIdentity,
            String correlationJson, String canonicalPatchJson, Long expectedCaseVersion,
            EngineCommandPolicy.CommandState state, long version,
            OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    }

    public record LeasedCommand(
            StoredCommand command, String leaseToken, String leaseOwner,
            OffsetDateTime leaseExpiresAt) {
    }

    public enum ActionCommit { APPLIED, EXACT_REPLAY }

    private record CommandCasState(
            long version, EngineCommandPolicy.ActionLedgerSummary summary) {
    }

    public static class IdempotencyConflictException extends IllegalStateException {
        public IdempotencyConflictException(String message) { super(message); }
    }

    public static class ActionIdentityConflictException extends IllegalStateException {
        public ActionIdentityConflictException(String message) { super(message); }
        public ActionIdentityConflictException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static class OptimisticCommandException extends IllegalStateException {
        public OptimisticCommandException(String message) { super(message); }
        public OptimisticCommandException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JVM does not provide SHA-256", impossible);
        }
    }

    private static String legacyTarget(EngineCommand command) {
        String key = switch (command.type()) {
            case CREATE_TASK -> "planItemId";
            case CLAIM_TASK, COMPLETE_TASK -> "engineTaskId";
            case START_PROCESS -> command.payload().containsKey("processDefinitionId")
                    ? "processDefinitionId" : "processDefinitionKey";
            case CANCEL_PROCESS -> "processInstanceId";
            case DEPLOY_ORCHESTRATION -> "definitionKey";
            case CORRELATE_MESSAGE -> "messageName";
        };
        Object value = command.payload().get(key);
        return value instanceof String text && !text.isBlank() ? text : command.caseId();
    }
}
