package org.casemgmt.engine;

import org.casemgmt.repo.JsonCodec;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.Reader;
import java.sql.Types;
import java.time.Duration;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.List;
import java.util.UUID;

/**
 * Persistence for {@code CM_ENGINE_COMMAND} (spec §3.5) — the remote-mode engine command
 * outbox. {@link org.casemgmt.engine.OutboxEngineGateway} enqueues rows in the caller's
 * transaction; {@link org.casemgmt.engine.EngineCommandDispatcher} claims and delivers them.
 */
public class ProductionEngineCommandStore {

    private static final String PRODUCTION_COLUMNS = """
            ID_, CASE_ID_, TENANT_ID_, OPERATION_ID_, IDEMPOTENCY_KEY_, PAYLOAD_DIGEST_,
            TYPE_, PAYLOAD_JSON_, TARGET_IDENTITY_, CORRELATION_JSON_, CANONICAL_PATCH_JSON_,
            EXPECTED_CASE_VERSION_, STATUS_, NEXT_ATTEMPT_AT_, DECIDED_AT_, SAFE_ERROR_CODE_,
            SAFE_SUMMARY_, TOTAL_DISPATCH_ATTEMPTS_, AUTO_ATTEMPTS_, BUDGET_EPOCH_,
            AUTO_BUDGET_RESET_, ROW_VERSION_, ACTION_COUNT_, ACTION_HIGH_WATER_,
            ACTION_RESET_COUNT_, ACTION_CANCEL_COUNT_, CURRENT_ACTION_SEQ_, CONFIRM_SOURCE_, REMOTE_IDENTITY_,
            REMOTE_STATE_, EVIDENCE_REFERENCE_, DECISION_REVIEW_FINDING_,
            DECISION_REVIEW_SOURCE_, DECISION_REVIEW_REF_, LEGACY_ROW_ID_, LEGACY_STATUS_,
            LEGACY_MIGRATION_REF_, LEGACY_MIGRATED_AT_, LEGACY_FAILURE_COUNT_,
            LEASE_TOKEN_, LEASE_OWNER_, LEASE_EXPIRES_AT_, DISPATCHED_AT_, CONFIRMED_AT_,
            FAILED_AT_, RAW_LEGACY_PAYLOAD_, RAW_LEGACY_ERROR_, RAW_LEGACY_CLAIM_TOKEN_,
            RAW_LEGACY_CLAIMED_AT_, RAW_LEGACY_ATTEMPTS_, RAW_LEGACY_CREATED_AT_,
            RAW_LEGACY_UPDATED_AT_, MIGRATION_BASELINE_DECIDED_AT_,
            MIGRATION_BASELINE_ACTIVE_, ORIGINAL_STATUS_, ATTEMPTS_, LAST_ERROR_, CLAIM_TOKEN_,
            CLAIMED_AT_, CREATED_AT_, UPDATED_AT_""";

    /** Compatibility lease used until Task 3 passes explicit lease ownership to the dispatcher. */
    static final Duration CLAIM_LEASE = Duration.ofMinutes(5);

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final Runnable beforeDecisionCas;

    public ProductionEngineCommandStore(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = null;
        this.clock = Clock.systemUTC();
        this.beforeDecisionCas = () -> { };
    }

    /** Production constructor: JDBC and local transactions are derived from one exact resource. */
    public ProductionEngineCommandStore(DataSource dataSource) {
        this(dataSource, Clock.systemUTC());
    }

    /** Testable production constructor with the policy clock kept inside persistence ownership. */
    public ProductionEngineCommandStore(DataSource dataSource, Clock clock) {
        this(dataSource, clock, () -> { });
    }

    ProductionEngineCommandStore(
            DataSource dataSource, Clock clock, Runnable beforeDecisionCas) {
        DataSource resource = Objects.requireNonNull(dataSource, "dataSource");
        while (resource instanceof TransactionAwareDataSourceProxy proxy) {
            resource = Objects.requireNonNull(proxy.getTargetDataSource(), "targetDataSource");
        }
        this.jdbc = JdbcClient.create(dataSource);
        this.transactions = new TransactionTemplate(new DataSourceTransactionManager(resource));
        this.clock = Objects.requireNonNull(clock, "clock");
        this.beforeDecisionCas = Objects.requireNonNull(beforeDecisionCas, "beforeDecisionCas");
    }

    public void enqueue(EngineCommand c) {
        String tenant = c.payload().get("tenantId") instanceof String value && !value.isBlank()
                ? value : "__legacy_runtime__";
        submit(new ProductionCommandRequest(
                c.id(), c.caseId(), tenant, c.id(), "legacy-runtime:" + c.id(),
                c.type(), c.payload(), legacyTarget(c),
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
                    MIGRATION_BASELINE_ACTIVE_=CASE
                      WHEN ORIGINAL_STATUS_ IS NULL THEN NULL ELSE 0 END,
                    ROW_VERSION_=ROW_VERSION_+1
                WHERE ID_ = :id AND STATUS_='DISPATCHING'""")
            .param("id", id).update();
    }

    @Deprecated(forRemoval = false)
    public void markRetry(String id, String error, OffsetDateTime nextAttempt) {
        jdbc.sql("""
                UPDATE CM_ENGINE_COMMAND SET STATUS_ = 'RETRYABLE',
                    NEXT_ATTEMPT_AT_ = :next,
                    SAFE_ERROR_CODE_='transport.not_sent',
                    SAFE_SUMMARY_='Remote request sent zero bytes',
                    LEASE_TOKEN_=NULL, LEASE_OWNER_=NULL, LEASE_EXPIRES_AT_=NULL,
                    UPDATED_AT_=SYSTIMESTAMP, DECIDED_AT_=SYSTIMESTAMP,
                    MIGRATION_BASELINE_ACTIVE_=CASE
                      WHEN ORIGINAL_STATUS_ IS NULL THEN NULL ELSE 0 END,
                    ROW_VERSION_=ROW_VERSION_+1
                WHERE ID_ = :id AND STATUS_='DISPATCHING'""")
            .param("next", nextAttempt).param("id", id).update();
    }

    @Deprecated(forRemoval = false)
    public void markDead(String id, String error) {
        jdbc.sql("""
                UPDATE CM_ENGINE_COMMAND SET STATUS_ = 'FAILED',
                    SAFE_ERROR_CODE_='attempts.exhausted',
                    SAFE_SUMMARY_='Remote command exhausted automatic dispatch attempts',
                    LEASE_TOKEN_=NULL, LEASE_OWNER_=NULL, LEASE_EXPIRES_AT_=NULL,
                    FAILED_AT_=SYSTIMESTAMP, UPDATED_AT_=SYSTIMESTAMP, DECIDED_AT_=SYSTIMESTAMP,
                    MIGRATION_BASELINE_ACTIVE_=CASE
                      WHEN ORIGINAL_STATUS_ IS NULL THEN NULL ELSE 0 END,
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
        SubmissionResult result = inTransaction(() -> {
            String creationToken = UUID.randomUUID().toString();
            jdbc.sql("""
                        BEGIN
                        INSERT INTO CM_ENGINE_COMMAND
                          (ID_, CASE_ID_, TENANT_ID_, OPERATION_ID_, IDEMPOTENCY_KEY_,
                           PAYLOAD_DIGEST_, TYPE_, PAYLOAD_JSON_, TARGET_IDENTITY_,
                           CORRELATION_JSON_, CANONICAL_PATCH_JSON_, EXPECTED_CASE_VERSION_,
                           STATUS_, ATTEMPTS_, NEXT_ATTEMPT_AT_, CREATED_AT_, UPDATED_AT_,
                           DECIDED_AT_, TOTAL_DISPATCH_ATTEMPTS_, AUTO_ATTEMPTS_, BUDGET_EPOCH_,
                           AUTO_BUDGET_RESET_, ROW_VERSION_, ACTION_COUNT_, ACTION_HIGH_WATER_,
                           ACTION_RESET_COUNT_, ACTION_CANCEL_COUNT_, CLAIM_TOKEN_)
                        VALUES
                          (:id, :caseId, :tenantId, :operationId, :idempotencyKey,
                           :payloadDigest, :type, :payload, :target, :correlation, :patch,
                           :expectedCaseVersion, 'PENDING', 0, NULL, :submittedAt,
                           :submittedAt, :submittedAt, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                           :creationToken);
                        EXCEPTION WHEN DUP_VAL_ON_INDEX THEN NULL;
                        END;
                        """)
                        .param("id", request.commandId())
                        .param("caseId", request.caseId())
                        .param("tenantId", request.tenantId())
                        .param("operationId", request.operationId())
                        .param("idempotencyKey", request.idempotencyKey())
                        .param("payloadDigest", request.payloadDigest())
                        .param("type", request.commandType().name())
                        .param("payload", request.canonicalPayloadJson())
                        .param("target", request.expectedTargetIdentity())
                        .param("correlation", request.correlationJson())
                        .param("patch", request.canonicalPatchJson())
                        .param("expectedCaseVersion", request.expectedCaseVersion())
                        .param("submittedAt", request.submittedAt())
                        .param("creationToken", creationToken)
                        .update();
            boolean inserted = jdbc.sql("""
                    SELECT CLAIM_TOKEN_ FROM CM_ENGINE_COMMAND
                    WHERE TENANT_ID_=:tenantId AND IDEMPOTENCY_KEY_=:idempotencyKey
                    """).param("tenantId", request.tenantId())
                    .param("idempotencyKey", request.idempotencyKey())
                    .query(String.class).optional().filter(creationToken::equals).isPresent();
            if (inserted) {
                jdbc.sql("UPDATE CM_ENGINE_COMMAND SET CLAIM_TOKEN_=NULL "
                        + "WHERE ID_=:id AND CLAIM_TOKEN_=:creationToken")
                        .param("id", request.commandId())
                        .param("creationToken", creationToken).update();
            }
            Optional<StoredCommand> byIdempotency = findByIdempotency(
                    request.tenantId(), request.idempotencyKey());
            if (byIdempotency.isPresent()) {
                StoredCommand existing = byIdempotency.orElseThrow();
                if (!sameSubmission(existing, request)
                        || existing.commandId().equals(request.commandId())
                        && (!existing.operationId().equals(request.operationId())
                        || !existing.idempotencyKey().equals(request.idempotencyKey()))) {
                    return SubmissionResult.idempotencyConflict();
                }
                return SubmissionResult.success(new Submission(
                        existing, !inserted));
            }
            Optional<StoredCommand> byCommandId = findByCommandId(request.commandId());
            if (byCommandId.isPresent()) {
                StoredCommand existing = byCommandId.orElseThrow();
                return sameCommandBinding(existing, request)
                        ? SubmissionResult.success(new Submission(existing, true))
                        : SubmissionResult.operationConflict();
            }
            if (findByOperation(request.tenantId(), request.operationId()).isPresent()) {
                return SubmissionResult.operationConflict();
            }
            return SubmissionResult.operationConflict();
        });
        return switch (result.kind()) {
            case CREATED, REPLAY -> result.submission();
            case IDEMPOTENCY_CONFLICT -> throw new IdempotencyConflictException(
                    "Idempotency key is already bound to a different command intent");
            case OPERATION_CONFLICT -> throw new OperationConflictException(
                    "Operation or command ID is already bound to different intent");
        };
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
        OffsetDateTime decisionTime = EngineCommandPolicy.canonicalPersistedTimestamp(
                now, "claim time");
        int boundedLimit = Math.clamp(limit, 1, 200);
        return inRequiredTransaction(() -> {
            List<String> candidates = jdbc.sql("""
                    SELECT ID_ FROM CM_ENGINE_COMMAND
                    WHERE (STATUS_='PENDING' OR
                          (STATUS_='RETRYABLE' AND NEXT_ATTEMPT_AT_ <= :now))
                      AND AUTO_ATTEMPTS_ < :maxAttempts
                      AND ROWNUM <= :limit
                    FOR UPDATE SKIP LOCKED
                    """).param("now", decisionTime)
                    .param("maxAttempts", EngineCommandPolicy.MAX_AUTOMATIC_ATTEMPTS)
                    .param("limit", boundedLimit).query(String.class).list();
            List<LeasedCommand> claimed = new java.util.ArrayList<>();
            EngineCommandPolicy policy = policyAt(decisionTime);
            for (String commandId : candidates) {
                StoredCommand prior = requireByCommandId(commandId);
                EngineCommandPolicy.Decision next = policy.transition(
                        prior.state(), CommandDispatchOutcome.dispatchRequested());
                String token = UUID.randomUUID().toString();
                OffsetDateTime expiresAt = EngineCommandPolicy.canonicalPersistedTimestamp(
                        decisionTime.plus(leaseDuration), "lease expiry");
                int updated = persistDecision(prior, next, prior.version(),
                        prior.state().committedDecision().actionLedgerSummary(),
                        new LeaseState(token, safeOwner, expiresAt), true);
                if (updated == 1) {
                    StoredCommand persisted = requireByCommandId(commandId);
                    claimed.add(new LeasedCommand(persisted, token, safeOwner, expiresAt));
                }
            }
            return List.copyOf(claimed);
        });
    }

    /** Quarantines expired possibly-sent work; it is never made due for a blind resend. */
    public int recoverExpiredLeases(OffsetDateTime now) {
        Objects.requireNonNull(now, "now");
        OffsetDateTime decisionTime = EngineCommandPolicy.canonicalPersistedTimestamp(
                now, "recovery time");
        return inRequiredTransaction(() -> {
            List<String> expired = jdbc.sql("""
                    SELECT ID_ FROM CM_ENGINE_COMMAND
                    WHERE STATUS_='DISPATCHING' AND LEASE_EXPIRES_AT_ <= :now
                    ORDER BY ID_
                    FOR UPDATE SKIP LOCKED
                    """).param("now", decisionTime).query(String.class).list();
            int recovered = 0;
            EngineCommandPolicy policy = policyAt(decisionTime);
            for (String commandId : expired) {
                StoredCommand prior = requireByCommandId(commandId);
                EngineCommandPolicy.Decision next = policy.transition(
                        prior.state(), CommandDispatchOutcome.leaseExpired());
                recovered += persistDecision(prior, next, prior.version(),
                        prior.state().committedDecision().actionLedgerSummary(), null, false);
            }
            return recovered;
        });
    }

    /**
     * Commits the policy decision for one owned dispatch lease. Both the opaque lease token and
     * optimistic row version are required, so a timed-out worker cannot overwrite recovery or a
     * newer dispatcher decision.
     */
    public StoredCommand commitLeaseOutcome(
            String tenantId, String operationId, String leaseToken, long expectedVersion,
            CommandDispatchOutcome outcome) {
        required(leaseToken, "leaseToken", 64);
        Objects.requireNonNull(outcome, "outcome");
        if (outcome.operatorAction() != null) throw new IllegalArgumentException(
                "Operator outcomes require applyOperatorOutcome");
        return inRequiredTransaction(() -> {
            StoredCommand current = require(tenantId, operationId);
            if (current.version() != expectedVersion
                    || current.state().committedDecision().status()
                    != EngineCommandStatus.DISPATCHING
                    || !leaseToken.equals(currentLeaseToken(current.commandId()))) {
                throw new OptimisticCommandException(
                        "Lease token, row version, or command state changed before commit");
            }
            EngineCommandPolicy.Decision decision = new EngineCommandPolicy(clock)
                    .transition(current.state(), outcome);
            if (decision.equals(current.state().committedDecision())) {
                return current;
            }
            int updated = persistDecision(current, decision, expectedVersion,
                    current.state().committedDecision().actionLedgerSummary(), null, false);
            if (updated != 1) {
                throw new OptimisticCommandException(
                        "Lease token, row version, or command state changed before commit");
            }
            return require(tenantId, operationId);
        });
    }

    /** Applies a non-lease observation/reconciliation fact under optimistic version control. */
    public StoredCommand applyOutcome(
            String tenantId, String operationId, long expectedVersion,
            CommandDispatchOutcome outcome) {
        Objects.requireNonNull(outcome, "outcome");
        if (outcome.operatorAction() != null
                || outcome.kind() == CommandDispatchOutcome.Kind.DISPATCH_REQUESTED
                || outcome.kind() == CommandDispatchOutcome.Kind.LEASE_EXPIRED) {
            throw new IllegalArgumentException(
                    "Outcome requires the dedicated lease or operator repository API");
        }
        return inRequiredTransaction(() -> {
            StoredCommand current = require(tenantId, operationId);
            if (current.version() != expectedVersion) {
                throw new OptimisticCommandException("Command version changed before outcome");
            }
            EngineCommandPolicy.Decision decision = new EngineCommandPolicy(clock)
                    .transition(current.state(), outcome);
            if (decision.equals(current.state().committedDecision())) {
                return current;
            }
            int updated = persistDecision(current, decision, expectedVersion,
                    current.state().committedDecision().actionLedgerSummary(), null, false);
            if (updated != 1) {
                throw new OptimisticCommandException("Command state changed before outcome");
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

    /** Applies a typed operator fact and persists its policy transition atomically. */
    public ActionCommit applyOperatorOutcome(
            String tenantId, String operationId, long expectedVersion,
            CommandDispatchOutcome outcome) {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(outcome.operatorAction(), "outcome.operatorAction");
        return inRequiredTransaction(() -> {
            StoredCommand current = require(tenantId, operationId);
            CommandDispatchOutcome.OperatorAction requestedAction = outcome.operatorAction();
            Optional<EngineCommandPolicy.ProcessedAction> existing = findAction(
                    current.commandId(), requestedAction.actionId());
            if (existing.isPresent()) {
                if (!exactAction(existing.orElseThrow(), outcome)) {
                    throw new ActionIdentityConflictException(
                            "Operator action ID is bound to different evidence");
                }
                new EngineCommandPolicy(clock).transition(current.state(), outcome,
                        EngineCommandPolicy.AuthoritativeActionLookup.exact(
                                existing.orElseThrow()));
                return ActionCommit.EXACT_REPLAY;
            }
            if (current.version() != expectedVersion) {
                throw new OptimisticCommandException("Command version changed before action");
            }
            EngineCommandPolicy.OperatorTransition transition = new EngineCommandPolicy(clock)
                    .transition(current.state(), outcome,
                            EngineCommandPolicy.AuthoritativeActionLookup.absent());
            if (transition.actionAppend() == null) return ActionCommit.EXACT_REPLAY;
            EngineCommandPolicy.ActionAppend append = transition.actionAppend();

            // Catch DUP_VAL_ON_INDEX inside Oracle, not in Spring. A translated integrity
            // exception can mark a caller-owned REQUIRED transaction rollback-only before the
            // repository has a chance to reload the winner. The normalized row is then read back
            // and classified as our insert, an exact concurrent replay, or a conflict.
            insertActionIgnoringDuplicate(current.commandId(), append.action());
            Optional<EngineCommandPolicy.ProcessedAction> authoritative = findAction(
                    current.commandId(), append.action().action().actionId());
            if (authoritative.isEmpty()) {
                throw new OptimisticCommandException(
                        "Action sequence was concurrently claimed by a different action");
            }
            if (!authoritative.orElseThrow().equals(append.action())) {
                throw new ActionIdentityConflictException(
                        "Operator action ID is bound to different evidence");
            }

            CommandCasState cas = commandCasState(current.commandId());
            if (cas.summary().equals(append.resultingSummary())) {
                return exactReplayOrConflict(current.commandId(), transition,
                        authoritative.orElseThrow());
            }
            if (!cas.summary().equals(append.expectedSummary())
                    || cas.version() != expectedVersion) {
                throw new OptimisticCommandException(
                        "Command version or action summary changed during append");
            }
            int updated = persistDecision(current, transition.decision(), expectedVersion,
                    append.expectedSummary(), null, false);
            if (updated != 1) {
                throw new OptimisticCommandException(
                        "Command version or action summary changed during append");
            }
            return ActionCommit.APPLIED;
        });
    }

    private static boolean exactAction(
            EngineCommandPolicy.ProcessedAction existing, CommandDispatchOutcome outcome) {
        return existing.action().equals(outcome.operatorAction())
                && Objects.equals(existing.reviewEvidence(), outcome.reviewEvidence());
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

    private Optional<StoredCommand> findByCommandId(String commandId) {
        return jdbc.sql("SELECT " + PRODUCTION_COLUMNS + " FROM CM_ENGINE_COMMAND WHERE ID_=:id")
                .param("id", commandId).query((rs, row) -> mapStored(rs)).optional();
    }

    private static boolean sameSubmission(
            StoredCommand existing, ProductionCommandRequest request) {
        return existing.payloadDigest().equals(request.payloadDigest())
                && existing.caseId().equals(request.caseId())
                && existing.state().command().commandType() == request.commandType()
                && existing.expectedTargetIdentity().equals(request.expectedTargetIdentity())
                && JsonCodec.canonicalJson(existing.payload())
                    .equals(request.canonicalPayloadJson())
                && Objects.equals(existing.correlationJson(), request.correlationJson())
                && Objects.equals(existing.canonicalPatchJson(), request.canonicalPatchJson())
                && Objects.equals(existing.expectedCaseVersion(), request.expectedCaseVersion());
    }

    private static boolean sameCommandBinding(
            StoredCommand existing, ProductionCommandRequest request) {
        return existing.commandId().equals(request.commandId())
                && existing.operationId().equals(request.operationId())
                && existing.idempotencyKey().equals(request.idempotencyKey())
                && existing.state().command().tenantId().equals(request.tenantId())
                && sameSubmission(existing, request);
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

    private int persistDecision(
            StoredCommand prior, EngineCommandPolicy.Decision decision, long expectedVersion,
            EngineCommandPolicy.ActionLedgerSummary expectedSummary, LeaseState lease,
            boolean startingDispatch) {
        new EngineCommandPolicy.CommandState(prior.state().command(), decision);
        beforeDecisionCas.run();
        EngineCommandPolicy.ActionLedgerSummary summary = decision.actionLedgerSummary();
        CommandDispatchOutcome.ConfirmationEvidence confirmation =
                decision.terminalConfirmation();
        CommandDispatchOutcome.ReviewEvidence review = decision.decisionEvidence();
        Long currentAction = decision.appliedAction() == null
                ? null : decision.appliedAction().sequence();
        return jdbc.sql("""
                UPDATE CM_ENGINE_COMMAND SET
                  STATUS_=:status, NEXT_ATTEMPT_AT_=:nextAttempt, DECIDED_AT_=:decidedAt,
                  UPDATED_AT_=:decidedAt, SAFE_ERROR_CODE_=:errorCode,
                  SAFE_SUMMARY_=:safeSummary, TOTAL_DISPATCH_ATTEMPTS_=:totalAttempts,
                  AUTO_ATTEMPTS_=:autoAttempts, BUDGET_EPOCH_=:budgetEpoch,
                  AUTO_BUDGET_RESET_=:budgetReset, ACTION_COUNT_=:actionCount,
                  ACTION_HIGH_WATER_=:highWater, ACTION_RESET_COUNT_=:resetCount,
                  ACTION_CANCEL_COUNT_=:cancelCount, CURRENT_ACTION_SEQ_=:currentAction,
                  CONFIRM_SOURCE_=:confirmSource, REMOTE_IDENTITY_=:remoteIdentity,
                  REMOTE_STATE_=:remoteState, EVIDENCE_REFERENCE_=:evidenceReference,
                  CONFIRMED_AT_=CASE WHEN :status='CONFIRMED' THEN :decidedAt ELSE NULL END,
                  FAILED_AT_=CASE WHEN :status='FAILED' THEN :decidedAt ELSE NULL END,
                  DECISION_REVIEW_FINDING_=:reviewFinding,
                  DECISION_REVIEW_SOURCE_=:reviewSource, DECISION_REVIEW_REF_=:reviewReference,
                  LEASE_TOKEN_=:leaseToken, LEASE_OWNER_=:leaseOwner,
                  LEASE_EXPIRES_AT_=:leaseExpires,
                  DISPATCHED_AT_=CASE WHEN :startingDispatch=1 THEN :decidedAt
                                      ELSE DISPATCHED_AT_ END,
                  MIGRATION_BASELINE_ACTIVE_=CASE
                    WHEN ORIGINAL_STATUS_ IS NULL THEN NULL ELSE 0 END,
                  ROW_VERSION_=ROW_VERSION_+1
                WHERE ID_=:commandId AND ROW_VERSION_=:expectedVersion
                  AND STATUS_=:expectedStatus
                  AND ACTION_COUNT_=:expectedCount AND ACTION_HIGH_WATER_=:expectedHighWater
                  AND ACTION_RESET_COUNT_=:expectedResetCount
                  AND ACTION_CANCEL_COUNT_=:expectedCancelCount
                  AND DECODE(CURRENT_ACTION_SEQ_,:expectedCurrentAction,1,0)=1
                  AND DECODE(MIGRATION_BASELINE_ACTIVE_,:expectedBaselineActive,1,0)=1
                  AND DECODE(ORIGINAL_STATUS_,:expectedOriginalStatus,1,0)=1
                  AND DECODE(RAW_LEGACY_ERROR_,:expectedRawError,1,0)=1
                  AND DECODE(RAW_LEGACY_CLAIM_TOKEN_,:expectedRawClaimToken,1,0)=1
                  AND DECODE(RAW_LEGACY_CLAIMED_AT_,:expectedRawClaimedAt,1,0)=1
                  AND DECODE(RAW_LEGACY_ATTEMPTS_,:expectedRawAttempts,1,0)=1
                  AND DECODE(RAW_LEGACY_CREATED_AT_,:expectedRawCreatedAt,1,0)=1
                  AND DECODE(RAW_LEGACY_UPDATED_AT_,:expectedRawUpdatedAt,1,0)=1
                  AND DECODE(MIGRATION_BASELINE_DECIDED_AT_,:expectedBaselineDecidedAt,1,0)=1
                  AND PAYLOAD_DIGEST_=:expectedPayloadDigest
                  AND ((RAW_LEGACY_PAYLOAD_ IS NULL AND :expectedRawPayload IS NULL)
                       OR (RAW_LEGACY_PAYLOAD_ IS NOT NULL AND :expectedRawPayload IS NOT NULL
                           AND DBMS_LOB.COMPARE(RAW_LEGACY_PAYLOAD_,:expectedRawPayload)=0))
                  AND ((PAYLOAD_JSON_ IS NULL AND :expectedPayload IS NULL)
                       OR (PAYLOAD_JSON_ IS NOT NULL AND :expectedPayload IS NOT NULL
                           AND DBMS_LOB.COMPARE(PAYLOAD_JSON_,:expectedPayload)=0))
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
                .param("currentAction", currentAction)
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
                .param("reviewReference", review == null ? null : review.evidenceReference())
                .param("leaseToken", lease == null ? null : lease.token())
                .param("leaseOwner", lease == null ? null : lease.owner())
                .param("leaseExpires", lease == null ? null : lease.expiresAt())
                .param("startingDispatch", startingDispatch ? 1 : 0)
                .param("commandId", prior.commandId()).param("expectedVersion", expectedVersion)
                .param("expectedStatus", prior.state().committedDecision().status().name())
                .param("expectedCount", expectedSummary.actionCount())
                .param("expectedHighWater", expectedSummary.highWaterSequence())
                .param("expectedResetCount", expectedSummary.automaticBudgetResetCount())
                .param("expectedCancelCount", expectedSummary.cancellationCount())
                .param("expectedCurrentAction", prior.migrationCas().currentActionSequence())
                .param("expectedBaselineActive", prior.migrationCas().baselineActive())
                .param("expectedOriginalStatus", prior.migrationCas().originalStatus())
                .param("expectedRawError", prior.migrationCas().rawError())
                .param("expectedRawClaimToken", prior.migrationCas().rawClaimToken())
                .param("expectedRawClaimedAt", prior.migrationCas().rawClaimedAt())
                .param("expectedRawAttempts", prior.migrationCas().rawAttempts())
                .param("expectedRawCreatedAt", prior.migrationCas().rawCreatedAt())
                .param("expectedRawUpdatedAt", prior.migrationCas().rawUpdatedAt())
                .param("expectedBaselineDecidedAt", prior.migrationCas().baselineDecidedAt())
                .param("expectedPayloadDigest", prior.payloadDigest())
                .param("expectedRawPayload", clobParameter(
                        prior.migrationCas().rawPayload()))
                .param("expectedPayload", clobParameter(
                        prior.migrationCas().payload())).update();
    }

    private StoredCommand requireByCommandId(String commandId) {
        return jdbc.sql("SELECT " + PRODUCTION_COLUMNS + " FROM CM_ENGINE_COMMAND WHERE ID_=:id")
                .param("id", commandId).query((rs, row) -> mapStored(rs)).optional()
                .orElseThrow(() -> new OptimisticCommandException(
                        "Command disappeared during persistence transition"));
    }

    private String currentLeaseToken(String commandId) {
        return jdbc.sql("SELECT LEASE_TOKEN_ FROM CM_ENGINE_COMMAND WHERE ID_=:id")
                .param("id", commandId).query(String.class).optional().orElse(null);
    }

    private static EngineCommandPolicy policyAt(OffsetDateTime at) {
        return new EngineCommandPolicy(Clock.fixed(at.toInstant(), java.time.ZoneOffset.UTC));
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
        List<EngineCommandPolicy.ProcessedAction> actions = loadAndValidateActions(context);
        EngineCommandPolicy.ActionLedgerSummary actualSummary = summarize(actions);
        if (!summary.equals(actualSummary)) {
            throw new IllegalArgumentException(
                    "Persisted command action summary does not match normalized history");
        }
        validateEvidenceShape(rs);
        String confirmationSource = rs.getString("CONFIRM_SOURCE_");
        EngineCommandPolicy.Decision decision;
        if ("LEGACY_MIGRATION".equals(confirmationSource)) {
            Integer legacyFailureCount = rs.getObject("LEGACY_FAILURE_COUNT_", Integer.class);
            if (legacyFailureCount == null) {
                throw new IllegalArgumentException(
                        "Persisted legacy DONE provenance is missing its failure count");
            }
            decision = LegacyDoneCommandMigration.migrate(
                    new LegacyDoneCommandMigration.LegacyDoneRow(
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
            Long currentActionSequence = rs.getObject("CURRENT_ACTION_SEQ_", Long.class);
            EngineCommandPolicy.ProcessedAction applied = currentActionSequence == null
                    ? null : actions.stream()
                    .filter(action -> action.sequence() == currentActionSequence)
                    .findFirst().orElseThrow(() -> new IllegalArgumentException(
                            "Current command action pointer has no normalized row"));
            if (applied != null && currentActionSequence != summary.highWaterSequence()) {
                throw new IllegalArgumentException(
                        "Current command action pointer must reference the ledger high-water row");
            }
            EngineCommandPolicy.ActionLedgerSummary prior = applied == null ? null
                    : summarize(actions.stream()
                    .filter(action -> action.sequence() < currentActionSequence).toList());
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
        String payloadJson = Objects.requireNonNull(
                readClob(rs, "PAYLOAD_JSON_"), "persisted payloadJson");
        String rawPayloadJson = readClob(rs, "RAW_LEGACY_PAYLOAD_");
        java.util.Map<String, Object> payload = JsonCodec.toMap(payloadJson);
        String expectedDigest = rs.getString("ORIGINAL_STATUS_") == null
                ? JsonCodec.canonicalSha256(payload) : JsonCodec.sha256(payloadJson);
        if (!payloadDigest.equals(expectedDigest)) {
            throw new IllegalArgumentException(
                    "Persisted payloadDigest does not match its retained payload");
        }
        validatePersistedTuple(rs, decision);
        validateHistoricalTuple(rs, payloadJson, rawPayloadJson, decision, actions);
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
                payload,
                rs.getString("TARGET_IDENTITY_"), rs.getString("CORRELATION_JSON_"),
                rs.getString("CANONICAL_PATCH_JSON_"),
                rs.getObject("EXPECTED_CASE_VERSION_", Long.class),
                new EngineCommandPolicy.CommandState(context, decision),
                version, createdAt, updatedAt, new MigrationCas(
                        rs.getObject("MIGRATION_BASELINE_ACTIVE_", Integer.class),
                        rs.getString("ORIGINAL_STATUS_"),
                        rs.getString("RAW_LEGACY_ERROR_"),
                        rs.getString("RAW_LEGACY_CLAIM_TOKEN_"),
                        rs.getObject("RAW_LEGACY_CLAIMED_AT_", OffsetDateTime.class),
                        rs.getObject("RAW_LEGACY_ATTEMPTS_", Integer.class),
                        rs.getObject("RAW_LEGACY_CREATED_AT_", OffsetDateTime.class),
                        rs.getObject("RAW_LEGACY_UPDATED_AT_", OffsetDateTime.class),
                        rs.getObject("MIGRATION_BASELINE_DECIDED_AT_", OffsetDateTime.class),
                        rs.getObject("CURRENT_ACTION_SEQ_", Long.class),
                        payloadJson, rawPayloadJson));
    }

    private static void validatePersistedTuple(
            java.sql.ResultSet rs, EngineCommandPolicy.Decision decision)
            throws java.sql.SQLException {
        OffsetDateTime createdAt = Objects.requireNonNull(
                rs.getObject("CREATED_AT_", OffsetDateTime.class), "persisted createdAt");
        OffsetDateTime updatedAt = Objects.requireNonNull(
                rs.getObject("UPDATED_AT_", OffsetDateTime.class), "persisted updatedAt");
        OffsetDateTime decidedAt = decision.decidedAt();
        boolean untouchedLegacyDone = "DONE".equals(rs.getString("ORIGINAL_STATUS_"))
                && Integer.valueOf(1).equals(
                        rs.getObject("MIGRATION_BASELINE_ACTIVE_", Integer.class));
        if (decidedAt.isBefore(createdAt)
                || !updatedAt.equals(decidedAt)
                && !(untouchedLegacyDone && updatedAt.equals(createdAt))) {
            throw new IllegalArgumentException(
                    "Persisted decision/update timestamps do not match repository semantics");
        }
        boolean dispatching = decision.status() == EngineCommandStatus.DISPATCHING;
        boolean completeLease = rs.getString("LEASE_TOKEN_") != null
                && rs.getString("LEASE_OWNER_") != null
                && rs.getObject("LEASE_EXPIRES_AT_") != null;
        boolean emptyLease = rs.getString("LEASE_TOKEN_") == null
                && rs.getString("LEASE_OWNER_") == null
                && rs.getObject("LEASE_EXPIRES_AT_") == null;
        if (dispatching != completeLease || (!dispatching && !emptyLease)) {
            throw new IllegalArgumentException(
                    "Persisted lease tuple does not match command status");
        }
        OffsetDateTime leaseExpiresAt = rs.getObject(
                "LEASE_EXPIRES_AT_", OffsetDateTime.class);
        if (!validTemporalDecision(decision.status(), decidedAt, decision.nextAttemptAt(),
                leaseExpiresAt)) {
            throw new IllegalArgumentException(
                    "Persisted retry/lease time must be strictly after its decision time");
        }
        OffsetDateTime confirmedAt = rs.getObject("CONFIRMED_AT_", OffsetDateTime.class);
        OffsetDateTime failedAt = rs.getObject("FAILED_AT_", OffsetDateTime.class);
        if ((decision.status() == EngineCommandStatus.CONFIRMED)
                    != Objects.equals(confirmedAt, decision.decidedAt())
                || (decision.status() == EngineCommandStatus.FAILED)
                    != Objects.equals(failedAt, decision.decidedAt())) {
            throw new IllegalArgumentException(
                    "Persisted terminal timestamp does not match terminal decision");
        }
        if (decision.status() != EngineCommandStatus.CONFIRMED && confirmedAt != null
                || decision.status() != EngineCommandStatus.FAILED && failedAt != null) {
            throw new IllegalArgumentException(
                    "Non-terminal state retained a terminal timestamp");
        }
        String correlation = rs.getString("CORRELATION_JSON_");
        String patch = rs.getString("CANONICAL_PATCH_JSON_");
        if (correlation != null && !correlation.equals(JsonCodec.canonicalJson(correlation))
                || patch != null && !patch.equals(JsonCodec.canonicalJson(patch))) {
            throw new IllegalArgumentException("Persisted intent JSON is not canonical");
        }
        Integer rawAttempts = rs.getObject("RAW_LEGACY_ATTEMPTS_", Integer.class);
        long migrationAttempts = rawAttempts == null ? 0L : rawAttempts
                + ("CLAIMED".equals(rs.getString("ORIGINAL_STATUS_"))
                || "DONE".equals(rs.getString("ORIGINAL_STATUS_")) ? 1L : 0L);
        if (decision.totalDispatchAttempts() < migrationAttempts) {
            throw new IllegalArgumentException(
                    "Current dispatch attempts precede the immutable migration baseline");
        }
        OffsetDateTime dispatchedAt = rs.getObject("DISPATCHED_AT_", OffsetDateTime.class);
        boolean postMigrationDispatch = decision.totalDispatchAttempts() > migrationAttempts;
        boolean dispatchTimeExact = dispatching
                ? postMigrationDispatch && Objects.equals(dispatchedAt, decidedAt)
                : postMigrationDispatch == (dispatchedAt != null);
        if (!dispatchTimeExact || dispatchedAt != null
                && (dispatchedAt.isBefore(createdAt) || dispatchedAt.isAfter(decidedAt))) {
            throw new IllegalArgumentException(
                    "Persisted dispatch timestamp does not match attempt history");
        }
        boolean initialLegacy = rs.getString("ORIGINAL_STATUS_") != null
                && Integer.valueOf(1).equals(
                        rs.getObject("MIGRATION_BASELINE_ACTIVE_", Integer.class));
        boolean diagnosticStatus = switch (decision.status()) {
            case RETRYABLE, AWAITING_CONFIRMATION, FAILED, CONFLICT, MANUAL_REVIEW -> true;
            default -> false;
        };
        if (!initialLegacy && diagnosticStatus != (decision.errorCode() != null)) {
            throw new IllegalArgumentException(
                    "Persisted diagnostic tuple does not match command status");
        }
        if (!diagnosticStatus && decision.errorCode() != null) {
            throw new IllegalArgumentException(
                    "Persisted command status cannot retain diagnostics");
        }
        if (decision.status() == EngineCommandStatus.PENDING
                && (decision.totalDispatchAttempts() != 0
                || dispatchedAt != null || decision.actionLedgerSummary().actionCount() != 0)) {
            throw new IllegalArgumentException(
                    "Pending commands must remain undispatched and action-free");
        }
    }

    private static void validateEvidenceShape(java.sql.ResultSet rs)
            throws java.sql.SQLException {
        int confirmationParts = countPresent(rs.getString("CONFIRM_SOURCE_"),
                rs.getString("REMOTE_IDENTITY_"), rs.getString("REMOTE_STATE_"),
                rs.getString("EVIDENCE_REFERENCE_"));
        if (!"LEGACY_MIGRATION".equals(rs.getString("CONFIRM_SOURCE_"))
                && confirmationParts != 0 && confirmationParts != 4) {
            throw new IllegalArgumentException("Persisted live confirmation tuple is partial");
        }
        int reviewParts = countPresent(rs.getString("DECISION_REVIEW_FINDING_"),
                rs.getString("DECISION_REVIEW_SOURCE_"),
                rs.getString("DECISION_REVIEW_REF_"));
        if (reviewParts != 0 && reviewParts != 3) {
            throw new IllegalArgumentException("Persisted review tuple is partial");
        }
    }

    private static void validateHistoricalTuple(
            java.sql.ResultSet rs, String payloadJson, String rawPayloadJson,
            EngineCommandPolicy.Decision decision,
            List<EngineCommandPolicy.ProcessedAction> actions)
            throws java.sql.SQLException {
        String original = rs.getString("ORIGINAL_STATUS_");
        Object[] legacyFields = {
                rs.getString("LEGACY_ROW_ID_"), rs.getString("LEGACY_STATUS_"),
                rs.getString("LEGACY_MIGRATION_REF_"), rs.getObject("LEGACY_MIGRATED_AT_"),
                rs.getObject("LEGACY_FAILURE_COUNT_"), rawPayloadJson,
                rs.getString("RAW_LEGACY_ERROR_"), rs.getString("RAW_LEGACY_CLAIM_TOKEN_"),
                rs.getObject("RAW_LEGACY_CLAIMED_AT_"),
                rs.getObject("RAW_LEGACY_ATTEMPTS_"),
                rs.getObject("RAW_LEGACY_CREATED_AT_"),
                rs.getObject("RAW_LEGACY_UPDATED_AT_"),
                rs.getObject("MIGRATION_BASELINE_DECIDED_AT_"),
                rs.getObject("MIGRATION_BASELINE_ACTIVE_")};
        if (original == null) {
            if (countPresent(legacyFields) != 0 || rs.getInt("ATTEMPTS_") != 0
                    || rs.getString("LAST_ERROR_") != null
                    || rs.getString("CLAIM_TOKEN_") != null
                    || rs.getObject("CLAIMED_AT_") != null
                    || (decision.totalDispatchAttempts() == 0)
                    != (rs.getObject("DISPATCHED_AT_") == null)) {
                throw new IllegalArgumentException("Native command retained legacy state");
            }
            return;
        }
        EngineCommandStatus mapped = switch (original) {
            case "PENDING" -> EngineCommandStatus.PENDING;
            case "RETRYING" -> EngineCommandStatus.RETRYABLE;
            case "CLAIMED" -> EngineCommandStatus.AWAITING_CONFIRMATION;
            case "DONE" -> EngineCommandStatus.CONFIRMED;
            case "DEAD" -> EngineCommandStatus.FAILED;
            default -> throw new IllegalArgumentException("Unknown retained legacy status");
        };
        Integer oldAttemptsValue = rs.getObject("RAW_LEGACY_ATTEMPTS_", Integer.class);
        if (oldAttemptsValue == null) {
            throw new IllegalArgumentException("Persisted legacy attempts baseline is missing");
        }
        int oldAttempts = oldAttemptsValue;
        long expectedAttempts = oldAttempts
                + (original.equals("CLAIMED") || original.equals("DONE") ? 1L : 0L);
        boolean claimed = original.equals("CLAIMED");
        boolean done = original.equals("DONE");
        OffsetDateTime createdAt = rs.getObject("CREATED_AT_", OffsetDateTime.class);
        OffsetDateTime rawCreatedAt = rs.getObject(
                "RAW_LEGACY_CREATED_AT_", OffsetDateTime.class);
        OffsetDateTime rawUpdatedAt = rs.getObject(
                "RAW_LEGACY_UPDATED_AT_", OffsetDateTime.class);
        OffsetDateTime migratedAt = rs.getObject("LEGACY_MIGRATED_AT_", OffsetDateTime.class);
        OffsetDateTime baselineDecidedAt = rs.getObject(
                "MIGRATION_BASELINE_DECIDED_AT_", OffsetDateTime.class);
        Integer baselineActive = rs.getObject("MIGRATION_BASELINE_ACTIVE_", Integer.class);
        EngineCommand.Type type = EngineCommand.Type.valueOf(rs.getString("TYPE_"));
        java.util.Map<String, Object> payload = JsonCodec.toMap(payloadJson);
        String expectedTarget = legacyTarget(type, payload, rs.getString("CASE_ID_"));
        boolean immutableBaseline = oldAttempts >= 0
                && matchesRetainedLegacyPayload(rawPayloadJson, payloadJson)
                && rs.getInt("ATTEMPTS_") == oldAttempts
                && Objects.equals(rs.getString("LAST_ERROR_"), rs.getString("RAW_LEGACY_ERROR_"))
                && Objects.equals(createdAt, rawCreatedAt)
                && Objects.equals(rawUpdatedAt, rawCreatedAt)
                && Objects.equals(baselineDecidedAt, done ? migratedAt : rawCreatedAt)
                && "__legacy_unscoped__".equals(rs.getString("TENANT_ID_"))
                && rs.getString("ID_").equals(rs.getString("OPERATION_ID_"))
                && ("legacy:" + rs.getString("ID_")).equals(rs.getString("IDEMPOTENCY_KEY_"))
                && Objects.equals(expectedTarget, rs.getString("TARGET_IDENTITY_"))
                && rs.getString("CORRELATION_JSON_") == null
                && rs.getString("CANONICAL_PATCH_JSON_") == null
                && rs.getObject("EXPECTED_CASE_VERSION_") == null
                && rs.getString("CLAIM_TOKEN_") == null && rs.getObject("CLAIMED_AT_") == null
                && (rs.getString("RAW_LEGACY_CLAIM_TOKEN_") != null) == claimed
                && (rs.getObject("RAW_LEGACY_CLAIMED_AT_") != null) == claimed
                && (rs.getString("LEGACY_ROW_ID_") != null) == done
                && (rs.getString("LEGACY_STATUS_") != null) == done
                && (rs.getString("LEGACY_MIGRATION_REF_") != null) == done
                && (migratedAt != null) == done
                && (rs.getObject("LEGACY_FAILURE_COUNT_") != null) == done
                && (baselineActive != null && (baselineActive == 0 || baselineActive == 1));
        if (!immutableBaseline) {
            throw new IllegalArgumentException(
                    "Persisted legacy command does not match its retained historical tuple");
        }
        if (baselineActive == 1) {
            boolean exactInitial = rs.getLong("ROW_VERSION_") == 0
                    && decision.actionLedgerSummary().actionCount() == 0
                    && decision.status() == mapped
                    && decision.totalDispatchAttempts() == expectedAttempts
                    && decision.automaticAttemptsInBudget() == expectedAttempts
                    && (rs.getObject("NEXT_ATTEMPT_AT_") != null)
                        == original.equals("RETRYING")
                    && rs.getObject("DISPATCHED_AT_") == null
                    && Objects.equals(decision.decidedAt(), baselineDecidedAt);
            if (!exactInitial) {
                throw new IllegalArgumentException(
                        "Untouched legacy command no longer matches its migration decision");
            }
        } else {
            CommandDispatchOutcome.ReviewEvidence review = decision.decisionEvidence();
            boolean coherentEvolution = !done && isPolicyReachableLegacyEvolution(
                    new LegacyEvolutionFacts(mapped, decision.status(), expectedAttempts,
                            decision.totalDispatchAttempts(),
                            decision.actionLedgerSummary().actionCount(),
                            actions.stream().filter(action -> action.action().actionType()
                                    == CommandDispatchOutcome.ActionType.RETRY_OVERRIDE).count(),
                            actions.stream().filter(action -> action.action().actionType()
                                    == CommandDispatchOutcome.ActionType.CANCEL).count(),
                            decision.appliedOperatorAction() == null ? null
                                    : decision.appliedOperatorAction().actionType(),
                            review == null ? null : review.finding(),
                            review == null ? null : review.source(),
                            decision.terminalConfirmation() != null,
                            rs.getLong("ROW_VERSION_"), baselineDecidedAt,
                            decision.decidedAt()));
            if (!coherentEvolution) {
                throw new IllegalArgumentException(
                        "Evolved legacy command lacks a policy-reachable repository transition");
            }
        }
    }

    static boolean matchesRetainedLegacyPayload(
            String rawLegacyPayloadJson, String currentPayloadJson) {
        return currentPayloadJson != null
                && (rawLegacyPayloadJson == null
                ? "{}".equals(currentPayloadJson)
                : rawLegacyPayloadJson.equals(currentPayloadJson));
    }

    static boolean validTemporalDecision(
            EngineCommandStatus status, OffsetDateTime decidedAt,
            OffsetDateTime nextAttemptAt, OffsetDateTime leaseExpiresAt) {
        boolean retryTime = status == EngineCommandStatus.RETRYABLE
                ? nextAttemptAt != null && nextAttemptAt.isAfter(decidedAt)
                : nextAttemptAt == null;
        boolean leaseTime = status != EngineCommandStatus.DISPATCHING
                || leaseExpiresAt != null && leaseExpiresAt.isAfter(decidedAt);
        return retryTime && leaseTime;
    }

    record LegacyEvolutionFacts(
            EngineCommandStatus baselineStatus,
            EngineCommandStatus currentStatus,
            long baselineAttempts,
            long currentAttempts,
            long actionCount,
            long retryActionCount,
            long cancelActionCount,
            CommandDispatchOutcome.ActionType appliedActionType,
            CommandDispatchOutcome.ReviewFinding reviewFinding,
            CommandDispatchOutcome.ReviewSource reviewSource,
            boolean hasConfirmation,
            long rowVersion,
            OffsetDateTime baselineDecidedAt,
            OffsetDateTime currentDecidedAt) {
        LegacyEvolutionFacts {
            Objects.requireNonNull(baselineStatus, "baselineStatus");
            Objects.requireNonNull(currentStatus, "currentStatus");
            Objects.requireNonNull(baselineDecidedAt, "baselineDecidedAt");
            Objects.requireNonNull(currentDecidedAt, "currentDecidedAt");
            if (baselineAttempts < 0 || currentAttempts < 0 || actionCount < 0
                    || retryActionCount < 0 || cancelActionCount < 0
                    || retryActionCount > actionCount || cancelActionCount > actionCount) {
                throw new IllegalArgumentException("Legacy evolution counters must be coherent");
            }
            if ((reviewFinding == null) != (reviewSource == null)) {
                throw new IllegalArgumentException("Legacy evolution review provenance is partial");
            }
        }
    }

    static boolean isPolicyReachableLegacyEvolution(LegacyEvolutionFacts facts) {
        EngineCommandStatus baseline = facts.baselineStatus();
        EngineCommandStatus current = facts.currentStatus();
        if (baseline == EngineCommandStatus.CONFIRMED || baseline == EngineCommandStatus.FAILED
                || current == EngineCommandStatus.PENDING
                || facts.currentAttempts() < facts.baselineAttempts()
                || facts.rowVersion() <= 0
                || !facts.currentDecidedAt().isAfter(facts.baselineDecidedAt())
                || facts.hasConfirmation() != (current == EngineCommandStatus.CONFIRMED)
                || (facts.cancelActionCount() > 0) != (current == EngineCommandStatus.CANCELLED)) {
            return false;
        }
        if (facts.appliedActionType() != null && switch (facts.appliedActionType()) {
            case MANUAL_REVIEW -> current != EngineCommandStatus.MANUAL_REVIEW;
            case RECONCILE -> current != EngineCommandStatus.AWAITING_CONFIRMATION;
            case RETRY_OVERRIDE -> current != EngineCommandStatus.RETRYABLE;
            case CANCEL -> current != EngineCommandStatus.CANCELLED;
        }) {
            return false;
        }
        long attemptDelta = facts.currentAttempts() - facts.baselineAttempts();
        long minimumVersion;
        try {
            minimumVersion = Math.addExact(facts.actionCount(), Math.multiplyExact(2L, attemptDelta));
            if (current == EngineCommandStatus.DISPATCHING && attemptDelta > 0) minimumVersion--;
            if (baseline == EngineCommandStatus.AWAITING_CONFIRMATION && attemptDelta > 0
                    && facts.retryActionCount() == 0) minimumVersion++;
            if (attemptDelta == 0 && facts.actionCount() > 0
                    && facts.appliedActionType() == null) minimumVersion++;
        } catch (ArithmeticException ex) {
            return false;
        }
        if (facts.rowVersion() < Math.max(1L, minimumVersion)) return false;

        boolean definitiveReconciliation = facts.reviewFinding()
                == CommandDispatchOutcome.ReviewFinding.DEFINITIVE_ABSENCE
                && facts.reviewSource() == CommandDispatchOutcome.ReviewSource.RECONCILIATION;
        boolean definitiveOperatorReview = facts.reviewFinding()
                == CommandDispatchOutcome.ReviewFinding.DEFINITIVE_ABSENCE
                && facts.reviewSource() == CommandDispatchOutcome.ReviewSource.OPERATOR_REVIEW;
        boolean inconclusiveReconciliation = facts.reviewFinding()
                == CommandDispatchOutcome.ReviewFinding.INCONCLUSIVE
                && facts.reviewSource() == CommandDispatchOutcome.ReviewSource.RECONCILIATION;
        return switch (current) {
            case PENDING -> false;
            case DISPATCHING -> attemptDelta > 0;
            case RETRYABLE -> attemptDelta > 0
                    || facts.appliedActionType()
                        == CommandDispatchOutcome.ActionType.RETRY_OVERRIDE
                        && (baseline == EngineCommandStatus.RETRYABLE
                            || baseline == EngineCommandStatus.AWAITING_CONFIRMATION)
                    || baseline == EngineCommandStatus.AWAITING_CONFIRMATION
                        && definitiveReconciliation;
            case AWAITING_CONFIRMATION -> attemptDelta > 0
                    || baseline == EngineCommandStatus.AWAITING_CONFIRMATION
                        && facts.appliedActionType()
                        == CommandDispatchOutcome.ActionType.RECONCILE;
            case CONFIRMED -> facts.hasConfirmation();
            case FAILED -> attemptDelta > 0
                    || baseline == EngineCommandStatus.AWAITING_CONFIRMATION
                        && facts.baselineAttempts()
                        == EngineCommandPolicy.MAX_AUTOMATIC_ATTEMPTS
                        && definitiveReconciliation;
            case CONFLICT -> attemptDelta > 0;
            case MANUAL_REVIEW -> (inconclusiveReconciliation
                    || facts.appliedActionType()
                        == CommandDispatchOutcome.ActionType.MANUAL_REVIEW)
                    && (baseline == EngineCommandStatus.AWAITING_CONFIRMATION
                        || attemptDelta > 0);
            case CANCELLED -> facts.appliedActionType()
                    == CommandDispatchOutcome.ActionType.CANCEL
                    && facts.cancelActionCount() == 1
                    && (attemptDelta == 0
                        && baseline != EngineCommandStatus.AWAITING_CONFIRMATION
                        || definitiveOperatorReview);
        };
    }

    private static int countPresent(Object... values) {
        int count = 0;
        for (Object value : values) if (value != null) count++;
        return count;
    }

    private static SqlParameterValue clobParameter(String value) {
        return new SqlParameterValue(Types.CLOB, value);
    }

    private static String readClob(java.sql.ResultSet rs, String column)
            throws java.sql.SQLException {
        Reader reader = rs.getCharacterStream(column);
        if (reader == null) return null;
        try (reader) {
            StringBuilder value = new StringBuilder();
            char[] buffer = new char[8192];
            for (int read; (read = reader.read(buffer)) >= 0; ) {
                if (read > 0) value.append(buffer, 0, read);
            }
            return value.toString();
        } catch (IOException ex) {
            throw new java.sql.SQLException("Unable to read persisted CLOB " + column, ex);
        }
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

    private List<EngineCommandPolicy.ProcessedAction> loadAndValidateActions(
            EngineCommandPolicy.CommandContext context) {
        List<EngineCommandPolicy.ProcessedAction> actions = jdbc.sql("""
                SELECT COMMAND_ID_, SEQUENCE_, ACTION_ID_, TENANT_ID_, OPERATION_ID_,
                       COMMAND_TYPE_, EXPECTED_TARGET_, ACTION_TYPE_, AUDIT_REFERENCE_,
                       PERFORMED_AT_, OVERRIDE_AUTO_CAP_, REVIEW_FINDING_, REVIEW_SOURCE_,
                       REVIEW_REFERENCE_
                FROM CM_ENGINE_COMMAND_ACTION WHERE COMMAND_ID_=:commandId ORDER BY SEQUENCE_
                """).param("commandId", context.commandId())
                .query((rs, row) -> mapAction(rs)).list();
        long expectedSequence = 1;
        for (EngineCommandPolicy.ProcessedAction action : actions) {
            CommandDispatchOutcome.OperatorAction row = action.action();
            if (action.sequence() != expectedSequence++
                    || !row.commandId().equals(context.commandId())
                    || !row.tenantId().equals(context.tenantId())
                    || !row.operationId().equals(context.operationId())
                    || row.commandType() != context.commandType()
                    || !row.expectedTargetIdentity().equals(context.expectedTargetIdentity())) {
                throw new IllegalArgumentException(
                        "Normalized action row does not match its command parent binding/sequence");
            }
        }
        return actions;
    }

    private static EngineCommandPolicy.ActionLedgerSummary summarize(
            List<EngineCommandPolicy.ProcessedAction> actions) {
        long resets = actions.stream().filter(action ->
                action.action().actionType() == CommandDispatchOutcome.ActionType.RETRY_OVERRIDE
                        && action.action().overrideAutomaticAttemptCap()).count();
        long cancellations = actions.stream().filter(action ->
                action.action().actionType() == CommandDispatchOutcome.ActionType.CANCEL).count();
        return new EngineCommandPolicy.ActionLedgerSummary(actions.size(),
                actions.isEmpty() ? 0 : actions.getLast().sequence(), resets, cancellations);
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
        int reviewParts = countPresent(finding, rs.getString("REVIEW_SOURCE_"),
                rs.getString("REVIEW_REFERENCE_"));
        if (reviewParts != 0 && reviewParts != 3) {
            throw new IllegalArgumentException("Normalized action review tuple is partial");
        }
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
            String idempotencyKey, EngineCommand.Type commandType,
            java.util.Map<String, Object> payload, String expectedTargetIdentity,
            String correlationJson, String canonicalPatchJson, Long expectedCaseVersion,
            OffsetDateTime submittedAt) {
        public ProductionCommandRequest {
            commandId = required(commandId, "commandId", 64);
            caseId = required(caseId, "caseId", 140);
            tenantId = required(tenantId, "tenantId", 64);
            operationId = required(operationId, "operationId", 64);
            idempotencyKey = required(idempotencyKey, "idempotencyKey", 128);
            Objects.requireNonNull(commandType, "commandType");
            String canonicalPayload = JsonCodec.canonicalJson(
                    Objects.requireNonNull(payload, "payload"));
            payload = java.util.Collections.unmodifiableMap(JsonCodec.toMap(canonicalPayload));
            expectedTargetIdentity = required(
                    expectedTargetIdentity, "expectedTargetIdentity", 255);
            correlationJson = correlationJson == null ? null
                    : JsonCodec.canonicalJson(correlationJson);
            canonicalPatchJson = canonicalPatchJson == null ? null
                    : JsonCodec.canonicalJson(canonicalPatchJson);
            submittedAt = EngineCommandPolicy.canonicalPersistedTimestamp(
                    submittedAt, "submittedAt");
        }

        /** Compatibility constructor that rejects a caller-forged digest. */
        public ProductionCommandRequest(
                String commandId, String caseId, String tenantId, String operationId,
                String idempotencyKey, String suppliedDigest, EngineCommand.Type commandType,
                java.util.Map<String, Object> payload, String expectedTargetIdentity,
                String correlationJson, String canonicalPatchJson, Long expectedCaseVersion,
                OffsetDateTime submittedAt) {
            this(commandId, caseId, tenantId, operationId, idempotencyKey, commandType,
                    payload, expectedTargetIdentity, correlationJson, canonicalPatchJson,
                    expectedCaseVersion, submittedAt);
            if (!payloadDigest().equals(suppliedDigest)) {
                throw new IllegalArgumentException(
                        "supplied payloadDigest does not match canonical payload");
            }
        }

        public String canonicalPayloadJson() {
            return JsonCodec.canonicalJson(payload);
        }

        public String payloadDigest() {
            return JsonCodec.sha256(canonicalPayloadJson());
        }
    }

    public record Submission(StoredCommand command, boolean replayed) {
        public Submission { Objects.requireNonNull(command, "command"); }
    }

    private enum SubmissionKind {
        CREATED, REPLAY, IDEMPOTENCY_CONFLICT, OPERATION_CONFLICT
    }

    private record SubmissionResult(SubmissionKind kind, Submission submission) {
        private static SubmissionResult success(Submission submission) {
            return new SubmissionResult(submission.replayed()
                    ? SubmissionKind.REPLAY : SubmissionKind.CREATED, submission);
        }
        private static SubmissionResult idempotencyConflict() {
            return new SubmissionResult(SubmissionKind.IDEMPOTENCY_CONFLICT, null);
        }
        private static SubmissionResult operationConflict() {
            return new SubmissionResult(SubmissionKind.OPERATION_CONFLICT, null);
        }
    }

    public record StoredCommand(
            String commandId, String operationId, String idempotencyKey, String payloadDigest,
            String caseId, java.util.Map<String, Object> payload, String expectedTargetIdentity,
            String correlationJson, String canonicalPatchJson, Long expectedCaseVersion,
            EngineCommandPolicy.CommandState state, long version,
            OffsetDateTime createdAt, OffsetDateTime updatedAt, MigrationCas migrationCas) {
        public StoredCommand { Objects.requireNonNull(migrationCas, "migrationCas"); }
    }

    /** Immutable values that make repository writes compare the rehydrated migration tuple. */
    public static final class MigrationCas {
        private final Integer baselineActive;
        private final String originalStatus;
        private final String rawError;
        private final String rawClaimToken;
        private final OffsetDateTime rawClaimedAt;
        private final Integer rawAttempts;
        private final OffsetDateTime rawCreatedAt;
        private final OffsetDateTime rawUpdatedAt;
        private final OffsetDateTime baselineDecidedAt;
        private final Long currentActionSequence;
        private final String payload;
        private final String rawPayload;

        private MigrationCas(
                Integer baselineActive, String originalStatus, String rawError,
                String rawClaimToken, OffsetDateTime rawClaimedAt, Integer rawAttempts,
                OffsetDateTime rawCreatedAt, OffsetDateTime rawUpdatedAt,
                OffsetDateTime baselineDecidedAt, Long currentActionSequence,
                String payload, String rawPayload) {
            this.baselineActive = baselineActive;
            this.originalStatus = originalStatus;
            this.rawError = rawError;
            this.rawClaimToken = rawClaimToken;
            this.rawClaimedAt = rawClaimedAt;
            this.rawAttempts = rawAttempts;
            this.rawCreatedAt = rawCreatedAt;
            this.rawUpdatedAt = rawUpdatedAt;
            this.baselineDecidedAt = baselineDecidedAt;
            this.currentActionSequence = currentActionSequence;
            this.payload = payload;
            this.rawPayload = rawPayload;
        }

        private Integer baselineActive() { return baselineActive; }
        private String originalStatus() { return originalStatus; }
        private String rawError() { return rawError; }
        private String rawClaimToken() { return rawClaimToken; }
        private OffsetDateTime rawClaimedAt() { return rawClaimedAt; }
        private Integer rawAttempts() { return rawAttempts; }
        private OffsetDateTime rawCreatedAt() { return rawCreatedAt; }
        private OffsetDateTime rawUpdatedAt() { return rawUpdatedAt; }
        private OffsetDateTime baselineDecidedAt() { return baselineDecidedAt; }
        private Long currentActionSequence() { return currentActionSequence; }
        private String payload() { return payload; }
        private String rawPayload() { return rawPayload; }

        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof MigrationCas that)) return false;
            return Objects.equals(baselineActive, that.baselineActive)
                    && Objects.equals(originalStatus, that.originalStatus)
                    && Objects.equals(rawError, that.rawError)
                    && Objects.equals(rawClaimToken, that.rawClaimToken)
                    && Objects.equals(rawClaimedAt, that.rawClaimedAt)
                    && Objects.equals(rawAttempts, that.rawAttempts)
                    && Objects.equals(rawCreatedAt, that.rawCreatedAt)
                    && Objects.equals(rawUpdatedAt, that.rawUpdatedAt)
                    && Objects.equals(baselineDecidedAt, that.baselineDecidedAt)
                    && Objects.equals(currentActionSequence, that.currentActionSequence)
                    && Objects.equals(payload, that.payload)
                    && Objects.equals(rawPayload, that.rawPayload);
        }

        @Override public int hashCode() {
            return Objects.hash(baselineActive, originalStatus, rawError, rawClaimToken,
                    rawClaimedAt, rawAttempts, rawCreatedAt, rawUpdatedAt,
                    baselineDecidedAt, currentActionSequence, payload, rawPayload);
        }
    }

    public record LeasedCommand(
            StoredCommand command, String leaseToken, String leaseOwner,
            OffsetDateTime leaseExpiresAt) {
    }

    public enum ActionCommit { APPLIED, EXACT_REPLAY }

    private record CommandCasState(
            long version, EngineCommandPolicy.ActionLedgerSummary summary) {
    }

    private record LeaseState(String token, String owner, OffsetDateTime expiresAt) {
    }

    public static class IdempotencyConflictException extends IllegalStateException {
        public IdempotencyConflictException(String message) { super(message); }
    }

    public static class OperationConflictException extends IllegalStateException {
        public OperationConflictException(String message) { super(message); }
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

    private static String legacyTarget(EngineCommand command) {
        return legacyTarget(command.type(), command.payload(), command.caseId());
    }

    private static String legacyTarget(
            EngineCommand.Type type, java.util.Map<String, Object> payload, String caseId) {
        String key = switch (type) {
            case CREATE_TASK -> "planItemId";
            case CLAIM_TASK, COMPLETE_TASK -> "engineTaskId";
            case START_PROCESS -> payload.containsKey("processDefinitionId")
                    ? "processDefinitionId" : "processDefinitionKey";
            case CANCEL_PROCESS -> "processInstanceId";
            case DEPLOY_ORCHESTRATION -> "definitionKey";
            case CORRELATE_MESSAGE -> "messageName";
        };
        Object value = payload.get(key);
        return value instanceof String text && !text.isBlank() ? text : caseId;
    }
}
