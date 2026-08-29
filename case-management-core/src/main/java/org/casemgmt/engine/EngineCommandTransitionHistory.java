package org.casemgmt.engine;

import org.casemgmt.repo.JsonCodec;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Versioned, safe serialization and exact policy replay for durable command transitions. */
public final class EngineCommandTransitionHistory {

    public static final int FORMAT_VERSION = 1;
    public static final int MAX_ENCODED_CHARS = 65_536;
    private static final long MAX_RETRY_AFTER_SECONDS = 31_536_000L;
    private static final Set<String> OUTCOME_FIELDS = Set.of("format", "kind",
            "transportFailure", "http", "confirmation", "review", "operatorAction");
    private static final Set<String> BINDING_FIELDS = Set.of("tenantId", "operationId",
            "commandId", "commandType", "expectedTargetIdentity");

    private EngineCommandTransitionHistory() {
    }

    public static RecordedTransition record(
            EngineCommandPolicy.CommandContext command,
            long version,
            EngineCommandPolicy.Decision previous,
            CommandDispatchOutcome outcome,
            OffsetDateTime decidedAt) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(outcome, "outcome");
        if (version <= 0) throw new IllegalArgumentException("Transition version must be positive");
        OffsetDateTime canonical = EngineCommandPolicy.canonicalPersistedTimestamp(
                decidedAt, "transition decidedAt");
        EngineCommandPolicy policy = new EngineCommandPolicy(
                Clock.fixed(canonical.toInstant(), ZoneOffset.UTC));
        EngineCommandPolicy.Decision next = apply(policy, command, previous, outcome);
        if (next.equals(previous)) {
            throw new IllegalArgumentException("An idempotent replay is not a committed transition");
        }
        if (!next.decidedAt().equals(canonical)) {
            throw new IllegalArgumentException("Transition decision time does not match policy time");
        }
        TransitionRow row = new TransitionRow(
                command.commandId(), command.tenantId(), command.operationId(),
                command.commandType(), command.expectedTargetIdentity(), version,
                previous.status(), next.status(), encodeOutcome(outcome),
                next.appliedAction() == null ? null : next.appliedAction().sequence(), canonical,
                digestDecision(previous), digestDecision(next));
        return new RecordedTransition(row, next);
    }

    public static EngineCommandPolicy.Decision replay(
            EngineCommandPolicy.CommandContext command,
            EngineCommandPolicy.Decision baseline,
            List<TransitionRow> transitions) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(baseline, "baseline");
        Objects.requireNonNull(transitions, "transitions");
        EngineCommandPolicy.Decision current = baseline;
        long expectedVersion = 1;
        for (TransitionRow row : transitions) {
            if (row.version() != expectedVersion) {
                throw new IllegalArgumentException("Command transition versions are not contiguous");
            }
            requireParent(command, row);
            if (row.fromStatus() != current.status()) {
                throw new IllegalArgumentException("Command transition from-status is not contiguous");
            }
            if (!row.previousDecisionDigest().equals(digestDecision(current))) {
                throw new IllegalArgumentException("Command transition previous decision digest differs");
            }
            CommandDispatchOutcome outcome = decodeOutcome(row.outcomeJson());
            EngineCommandPolicy policy = new EngineCommandPolicy(
                    Clock.fixed(row.decidedAt().toInstant(), ZoneOffset.UTC));
            EngineCommandPolicy.Decision next = apply(policy, command, current, outcome);
            if (next.equals(current)) {
                throw new IllegalArgumentException("Command history contains an idempotent no-op row");
            }
            if (row.toStatus() != next.status()
                    || !row.decidedAt().equals(next.decidedAt())
                    || !Objects.equals(row.actionSequence(), next.appliedAction() == null
                            ? null : next.appliedAction().sequence())
                    || !row.nextDecisionDigest().equals(digestDecision(next))) {
                throw new IllegalArgumentException("Command transition next decision digest differs");
            }
            current = next;
            expectedVersion = Math.incrementExact(expectedVersion);
        }
        return current;
    }

    public static EngineCommandPolicy.Decision replay(
            EngineCommandPolicy.CommandContext command,
            EngineCommandPolicy.Decision baseline,
            List<TransitionRow> transitions,
            List<EngineCommandPolicy.ProcessedAction> normalizedActions) {
        Objects.requireNonNull(normalizedActions, "normalizedActions");
        Map<Long, EngineCommandPolicy.ProcessedAction> bySequence = new HashMap<>();
        for (EngineCommandPolicy.ProcessedAction action : normalizedActions) {
            if (bySequence.put(action.sequence(), action) != null) {
                throw new IllegalArgumentException("Normalized command action sequence is duplicated");
            }
        }
        java.util.Set<Long> referencedSequences = new java.util.HashSet<>();
        for (TransitionRow row : transitions) {
            CommandDispatchOutcome outcome = decodeOutcome(row.outcomeJson());
            if (outcome.operatorAction() == null) {
                if (row.actionSequence() != null) throw new IllegalArgumentException(
                        "Non-operator transition references a normalized action");
                continue;
            }
            EngineCommandPolicy.ProcessedAction expected = new EngineCommandPolicy.ProcessedAction(
                    Objects.requireNonNull(row.actionSequence(),
                            "operator transition action sequence"),
                    outcome.operatorAction(), outcome.reviewEvidence());
            if (!expected.equals(bySequence.get(row.actionSequence()))) {
                throw new IllegalArgumentException(
                        "Command transition action evidence differs from normalized action row");
            }
            referencedSequences.add(row.actionSequence());
        }
        if (!referencedSequences.equals(bySequence.keySet())) {
            throw new IllegalArgumentException(
                    "Normalized action history contains unmatched action rows");
        }
        return replay(command, baseline, transitions);
    }

    private static EngineCommandPolicy.Decision apply(
            EngineCommandPolicy policy,
            EngineCommandPolicy.CommandContext command,
            EngineCommandPolicy.Decision previous,
            CommandDispatchOutcome outcome) {
        var state = new EngineCommandPolicy.CommandState(command, previous);
        if (outcome.operatorAction() == null) return policy.transition(state, outcome);
        return policy.transition(state, outcome,
                EngineCommandPolicy.AuthoritativeActionLookup.absent()).decision();
    }

    private static void requireParent(
            EngineCommandPolicy.CommandContext command, TransitionRow row) {
        if (!command.commandId().equals(row.commandId())
                || !command.tenantId().equals(row.tenantId())
                || !command.operationId().equals(row.operationId())
                || command.commandType() != row.commandType()
                || !command.expectedTargetIdentity().equals(row.expectedTargetIdentity())) {
            throw new IllegalArgumentException("Command transition parent binding differs");
        }
    }

    public static String digestDecision(EngineCommandPolicy.Decision decision) {
        Objects.requireNonNull(decision, "decision");
        return JsonCodec.sha256(JsonCodec.canonicalJson(decisionMap(decision)));
    }

    public static String encodeBaseline(EngineCommandPolicy.Decision decision) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("format", FORMAT_VERSION);
        value.put("kind", "BASELINE");
        value.put("decision", decisionMap(Objects.requireNonNull(decision, "decision")));
        return JsonCodec.canonicalJson(value);
    }

    public static EngineCommandPolicy.Decision decodeBaseline(
            EngineCommandPolicy.CommandContext command, String json) {
        requireEncodedSize(json);
        Map<String, Object> value = JsonCodec.toMap(Objects.requireNonNull(json, "json"));
        requireFields(value, Set.of("format", "kind", "decision"), "baseline");
        if (exactInt(value, "format") != FORMAT_VERSION
                || !"BASELINE".equals(string(value, "kind"))) {
            throw new IllegalArgumentException("Unsupported command transition baseline format");
        }
        Map<String, Object> decision = optionalMap(value, "decision");
        if (decision == null) throw new IllegalArgumentException(
                "Command transition baseline decision is missing");
        requireFields(decision, Set.of("status", "decidedAt", "nextAttemptAt", "errorCode",
                "safeSummary", "totalDispatchAttempts", "automaticAttemptsInBudget",
                "budgetEpoch", "automaticBudgetReset", "confirmation", "legacyConfirmation",
                "review", "appliedAction", "appliedActionPrior", "actionSummary"), "decision");
        if (decision.get("confirmation") != null || decision.get("review") != null
                || decision.get("appliedAction") != null
                || decision.get("appliedActionPrior") != null) {
            throw new IllegalArgumentException("Baseline contains unsupported live evidence");
        }
        Map<String, Object> legacy = optionalMap(decision, "legacyConfirmation");
        if (legacy != null) {
            requireFields(legacy, union(BINDING_FIELDS, Set.of("legacyRowId", "oldStatus",
                    "migrationReference", "migratedAt", "legacyFailureCount")),
                    "legacy confirmation");
            requireBinding(command, legacy);
            if (!"DONE".equals(string(legacy, "oldStatus"))) throw new IllegalArgumentException(
                    "Legacy confirmation old status must be DONE");
        }
        EngineCommandPolicy.LegacyConfirmationEvidence legacyEvidence = legacy == null ? null
                : new EngineCommandPolicy.LegacyConfirmationEvidence(command,
                string(legacy, "legacyRowId"), string(legacy, "migrationReference"),
                OffsetDateTime.parse(string(legacy, "migratedAt")),
                exactInt(legacy, "legacyFailureCount"));
        Map<String, Object> summary = optionalMap(decision, "actionSummary");
        if (summary != null) requireFields(summary, Set.of("actionCount", "highWaterSequence",
                "automaticBudgetResetCount", "cancellationCount"), "action summary");
        EngineCommandPolicy.ActionLedgerSummary actionSummary = summary == null
                ? EngineCommandPolicy.ActionLedgerSummary.empty()
                : new EngineCommandPolicy.ActionLedgerSummary(
                exactLong(summary, "actionCount"), exactLong(summary, "highWaterSequence"),
                exactLong(summary, "automaticBudgetResetCount"),
                exactLong(summary, "cancellationCount"));
        EngineCommandPolicy.Decision result = new EngineCommandPolicy.Decision(
                EngineCommandStatus.valueOf(string(decision, "status")),
                OffsetDateTime.parse(string(decision, "decidedAt")),
                optionalTimestamp(decision, "nextAttemptAt"),
                optionalString(decision, "errorCode"), optionalString(decision, "safeSummary"),
                exactLong(decision, "totalDispatchAttempts"),
                exactInt(decision, "automaticAttemptsInBudget"),
                exactLong(decision, "budgetEpoch"),
                booleanValue(decision, "automaticBudgetReset"), null, legacyEvidence,
                null, null, null, actionSummary);
        if (!encodeBaseline(result).equals(json)) throw new IllegalArgumentException(
                "Command transition baseline is not canonical");
        return result;
    }

    private static Map<String, Object> decisionMap(EngineCommandPolicy.Decision decision) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("status", decision.status().name());
        value.put("decidedAt", decision.decidedAt().toString());
        value.put("nextAttemptAt", text(decision.nextAttemptAt()));
        value.put("errorCode", decision.errorCode());
        value.put("safeSummary", decision.safeSummary());
        value.put("totalDispatchAttempts", decision.totalDispatchAttempts());
        value.put("automaticAttemptsInBudget", decision.automaticAttemptsInBudget());
        value.put("budgetEpoch", decision.budgetEpoch());
        value.put("automaticBudgetReset", decision.automaticBudgetReset());
        value.put("confirmation", confirmationMap(decision.terminalConfirmation()));
        value.put("legacyConfirmation", legacyConfirmationMap(decision.legacyConfirmation()));
        value.put("review", reviewMap(decision.decisionEvidence()));
        value.put("appliedAction", processedActionMap(decision.appliedAction()));
        value.put("appliedActionPrior", summaryMap(decision.appliedActionPriorSummary()));
        value.put("actionSummary", summaryMap(decision.actionLedgerSummary()));
        return value;
    }

    public static String encodeOutcome(CommandDispatchOutcome outcome) {
        Objects.requireNonNull(outcome, "outcome");
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("format", FORMAT_VERSION);
        value.put("kind", outcome.kind().name());
        value.put("transportFailure", name(outcome.transportFailure()));
        if (outcome.httpResult() != null) {
            Map<String, Object> http = new LinkedHashMap<>();
            http.put("status", outcome.httpResult().status());
            http.put("acceptance", outcome.httpResult().acceptance().name());
            http.put("retryAfterSeconds", outcome.httpResult().retryAfter() == null
                    ? null : outcome.httpResult().retryAfter().getSeconds());
            http.put("retryAfterNanos", outcome.httpResult().retryAfter() == null
                    ? null : outcome.httpResult().retryAfter().getNano());
            value.put("http", http);
        } else {
            value.put("http", null);
        }
        value.put("confirmation", confirmationMap(outcome.confirmationEvidence()));
        value.put("review", reviewMap(outcome.reviewEvidence()));
        value.put("operatorAction", operatorMap(outcome.operatorAction()));
        return JsonCodec.canonicalJson(value);
    }

    public static CommandDispatchOutcome decodeOutcome(String json) {
        requireEncodedSize(json);
        Map<String, Object> value = JsonCodec.toMap(Objects.requireNonNull(json, "json"));
        requireFields(value, OUTCOME_FIELDS, "outcome");
        if (exactInt(value, "format") != FORMAT_VERSION) {
            throw new IllegalArgumentException("Unsupported command transition outcome format");
        }
        CommandDispatchOutcome.Kind kind = CommandDispatchOutcome.Kind.valueOf(
                string(value, "kind"));
        CommandDispatchOutcome.TransportFailure transport = optionalEnum(value,
                "transportFailure", CommandDispatchOutcome.TransportFailure.class);
        CommandDispatchOutcome.HttpResult http = null;
        Map<String, Object> httpMap = optionalMap(value, "http");
        if (httpMap != null) {
            requireFields(httpMap, Set.of("status", "acceptance", "retryAfterSeconds",
                    "retryAfterNanos"), "HTTP result");
            Long seconds = optionalExactLong(httpMap, "retryAfterSeconds");
            Integer nanos = optionalExactInt(httpMap, "retryAfterNanos");
            if ((seconds == null) != (nanos == null)) throw new IllegalArgumentException(
                    "Retry-After seconds and nanos must both be present or absent");
            if (seconds != null && (seconds < 0 || seconds > MAX_RETRY_AFTER_SECONDS)) {
                throw new IllegalArgumentException("Retry-After seconds are outside the safe range");
            }
            if (nanos != null && (nanos < 0 || nanos > 999_999_999)) {
                throw new IllegalArgumentException("Retry-After nanos are outside the valid range");
            }
            Duration retryAfter = seconds == null ? null : Duration.ofSeconds(seconds, nanos);
            http = new CommandDispatchOutcome.HttpResult(exactInt(httpMap, "status"),
                    CommandDispatchOutcome.Acceptance.valueOf(string(httpMap, "acceptance")),
                    retryAfter);
        }
        CommandDispatchOutcome result = new CommandDispatchOutcome(kind, transport, http,
                confirmation(optionalMap(value, "confirmation")),
                review(optionalMap(value, "review")),
                operator(optionalMap(value, "operatorAction")));
        if (!encodeOutcome(result).equals(json)) throw new IllegalArgumentException(
                "Command transition outcome is not canonical");
        return result;
    }

    private static Map<String, Object> confirmationMap(
            CommandDispatchOutcome.ConfirmationEvidence evidence) {
        if (evidence == null) return null;
        Map<String, Object> value = bindingMap(evidence.tenantId(), evidence.operationId(),
                evidence.commandId(), evidence.commandType(), evidence.expectedTargetIdentity());
        value.put("remoteIdentity", evidence.remoteIdentity());
        value.put("remoteState", evidence.remoteState().name());
        value.put("source", evidence.source().name());
        value.put("evidenceReference", evidence.evidenceReference());
        return value;
    }

    private static Map<String, Object> legacyConfirmationMap(
            EngineCommandPolicy.LegacyConfirmationEvidence evidence) {
        if (evidence == null) return null;
        Map<String, Object> value = bindingMap(evidence.tenantId(), evidence.operationId(),
                evidence.commandId(), evidence.commandType(), evidence.expectedTargetIdentity());
        value.put("legacyRowId", evidence.legacyRowId());
        value.put("oldStatus", evidence.oldStatus().name());
        value.put("migrationReference", evidence.migrationReference());
        value.put("migratedAt", evidence.migratedAt().toString());
        value.put("legacyFailureCount", evidence.legacyFailureCount());
        return value;
    }

    private static Map<String, Object> reviewMap(CommandDispatchOutcome.ReviewEvidence evidence) {
        if (evidence == null) return null;
        Map<String, Object> value = bindingMap(evidence.tenantId(), evidence.operationId(),
                evidence.commandId(), evidence.commandType(), evidence.expectedTargetIdentity());
        value.put("finding", evidence.finding().name());
        value.put("source", evidence.source().name());
        value.put("evidenceReference", evidence.evidenceReference());
        return value;
    }

    private static Map<String, Object> operatorMap(CommandDispatchOutcome.OperatorAction action) {
        if (action == null) return null;
        Map<String, Object> value = bindingMap(action.tenantId(), action.operationId(),
                action.commandId(), action.commandType(), action.expectedTargetIdentity());
        value.put("actionType", action.actionType().name());
        value.put("actionId", action.actionId());
        value.put("auditReference", action.auditReference());
        value.put("performedAt", action.performedAt().toString());
        value.put("overrideAutomaticAttemptCap", action.overrideAutomaticAttemptCap());
        return value;
    }

    private static Map<String, Object> processedActionMap(
            EngineCommandPolicy.ProcessedAction action) {
        if (action == null) return null;
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("sequence", action.sequence());
        value.put("action", operatorMap(action.action()));
        value.put("review", reviewMap(action.reviewEvidence()));
        return value;
    }

    private static Map<String, Object> summaryMap(
            EngineCommandPolicy.ActionLedgerSummary summary) {
        if (summary == null) return null;
        return Map.of("actionCount", summary.actionCount(),
                "highWaterSequence", summary.highWaterSequence(),
                "automaticBudgetResetCount", summary.automaticBudgetResetCount(),
                "cancellationCount", summary.cancellationCount());
    }

    private static Map<String, Object> bindingMap(
            String tenant, String operation, String command, EngineCommand.Type type,
            String target) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("tenantId", tenant);
        value.put("operationId", operation);
        value.put("commandId", command);
        value.put("commandType", type.name());
        value.put("expectedTargetIdentity", target);
        return value;
    }

    private static CommandDispatchOutcome.ConfirmationEvidence confirmation(
            Map<String, Object> value) {
        if (value == null) return null;
        requireFields(value, union(BINDING_FIELDS, Set.of("remoteIdentity", "remoteState",
                "source", "evidenceReference")), "confirmation");
        return new CommandDispatchOutcome.ConfirmationEvidence(
                string(value, "tenantId"), string(value, "operationId"),
                string(value, "commandId"), EngineCommand.Type.valueOf(
                        string(value, "commandType")), string(value, "expectedTargetIdentity"),
                string(value, "remoteIdentity"), CommandDispatchOutcome.RemoteState.valueOf(
                        string(value, "remoteState")),
                CommandDispatchOutcome.ConfirmationSource.valueOf(string(value, "source")),
                string(value, "evidenceReference"));
    }

    private static CommandDispatchOutcome.ReviewEvidence review(Map<String, Object> value) {
        if (value == null) return null;
        requireFields(value, union(BINDING_FIELDS,
                Set.of("finding", "source", "evidenceReference")), "review");
        return new CommandDispatchOutcome.ReviewEvidence(
                string(value, "tenantId"), string(value, "operationId"),
                string(value, "commandId"), EngineCommand.Type.valueOf(
                        string(value, "commandType")), string(value, "expectedTargetIdentity"),
                CommandDispatchOutcome.ReviewFinding.valueOf(string(value, "finding")),
                CommandDispatchOutcome.ReviewSource.valueOf(string(value, "source")),
                string(value, "evidenceReference"));
    }

    private static CommandDispatchOutcome.OperatorAction operator(Map<String, Object> value) {
        if (value == null) return null;
        requireFields(value, union(BINDING_FIELDS, Set.of("actionType", "actionId",
                "auditReference", "performedAt", "overrideAutomaticAttemptCap")),
                "operator action");
        return new CommandDispatchOutcome.OperatorAction(
                string(value, "tenantId"), string(value, "operationId"),
                string(value, "commandId"), EngineCommand.Type.valueOf(
                        string(value, "commandType")), string(value, "expectedTargetIdentity"),
                CommandDispatchOutcome.ActionType.valueOf(string(value, "actionType")),
                string(value, "actionId"), string(value, "auditReference"),
                OffsetDateTime.parse(string(value, "performedAt")),
                booleanValue(value, "overrideAutomaticAttemptCap"));
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private static String text(OffsetDateTime value) {
        return value == null ? null : value.toString();
    }

    private static String string(Map<String, Object> map, String field) {
        Object value = map.get(field);
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException("Transition field " + field + " must be text");
        }
        return text;
    }

    private static String optionalString(Map<String, Object> map, String field) {
        Object value = map.get(field);
        if (value == null) return null;
        if (!(value instanceof String text)) throw new IllegalArgumentException(
                "Transition field " + field + " must be text");
        return text;
    }

    private static OffsetDateTime optionalTimestamp(Map<String, Object> map, String field) {
        String value = optionalString(map, field);
        return value == null ? null : OffsetDateTime.parse(value);
    }

    private static Number number(Map<String, Object> map, String field) {
        Number value = optionalNumber(map, field);
        if (value == null) throw new IllegalArgumentException(
                "Transition field " + field + " must be numeric");
        return value;
    }

    private static Number optionalNumber(Map<String, Object> map, String field) {
        Object value = map.get(field);
        if (value == null) return null;
        if (!(value instanceof Number number)) throw new IllegalArgumentException(
                "Transition field " + field + " must be numeric");
        return number;
    }

    private static long exactLong(Map<String, Object> map, String field) {
        Long value = optionalExactLong(map, field);
        if (value == null) throw new IllegalArgumentException(
                "Transition field " + field + " must be numeric");
        return value;
    }

    private static Long optionalExactLong(Map<String, Object> map, String field) {
        Number value = optionalNumber(map, field);
        if (value == null) return null;
        try {
            return new java.math.BigDecimal(value.toString()).longValueExact();
        } catch (ArithmeticException | NumberFormatException ex) {
            throw new IllegalArgumentException(
                    "Transition field " + field + " must be an integral 64-bit number", ex);
        }
    }

    private static int exactInt(Map<String, Object> map, String field) {
        Integer value = optionalExactInt(map, field);
        if (value == null) throw new IllegalArgumentException(
                "Transition field " + field + " must be numeric");
        return value;
    }

    private static Integer optionalExactInt(Map<String, Object> map, String field) {
        Long value = optionalExactLong(map, field);
        if (value == null) return null;
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "Transition field " + field + " is outside the 32-bit range");
        }
        return value.intValue();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> optionalMap(Map<String, Object> map, String field) {
        Object value = map.get(field);
        if (value == null) return null;
        if (!(value instanceof Map<?, ?> nested)) throw new IllegalArgumentException(
                "Transition field " + field + " must be an object");
        return (Map<String, Object>) nested;
    }

    private static boolean booleanValue(Map<String, Object> map, String field) {
        Object value = map.get(field);
        if (!(value instanceof Boolean result)) throw new IllegalArgumentException(
                "Transition field " + field + " must be boolean");
        return result;
    }

    private static <E extends Enum<E>> E optionalEnum(
            Map<String, Object> map, String field, Class<E> type) {
        Object value = map.get(field);
        if (value == null) return null;
        if (!(value instanceof String text)) throw new IllegalArgumentException(
                "Transition field " + field + " must be text");
        return Enum.valueOf(type, text);
    }

    private static void requireEncodedSize(String json) {
        if (json == null || json.length() == 0 || json.length() > MAX_ENCODED_CHARS) {
            throw new IllegalArgumentException("Command transition encoded size is invalid");
        }
    }

    private static void requireFields(
            Map<String, Object> value, Set<String> expected, String node) {
        if (!value.keySet().equals(expected)) {
            throw new IllegalArgumentException("Command transition " + node
                    + " fields differ from the versioned contract");
        }
    }

    private static Set<String> union(Set<String> left, Set<String> right) {
        java.util.HashSet<String> result = new java.util.HashSet<>(left);
        result.addAll(right);
        return Set.copyOf(result);
    }

    private static void requireBinding(
            EngineCommandPolicy.CommandContext command, Map<String, Object> value) {
        if (!command.tenantId().equals(string(value, "tenantId"))
                || !command.operationId().equals(string(value, "operationId"))
                || !command.commandId().equals(string(value, "commandId"))
                || !command.commandType().name().equals(string(value, "commandType"))
                || !command.expectedTargetIdentity().equals(
                        string(value, "expectedTargetIdentity"))) {
            throw new IllegalArgumentException("Command transition evidence binding differs");
        }
    }

    public record TransitionRow(
            String commandId, String tenantId, String operationId,
            EngineCommand.Type commandType, String expectedTargetIdentity,
            long version, EngineCommandStatus fromStatus, EngineCommandStatus toStatus,
            String outcomeJson, Long actionSequence, OffsetDateTime decidedAt,
            String previousDecisionDigest, String nextDecisionDigest) {
        public TransitionRow {
            Objects.requireNonNull(commandId, "commandId");
            Objects.requireNonNull(tenantId, "tenantId");
            Objects.requireNonNull(operationId, "operationId");
            Objects.requireNonNull(commandType, "commandType");
            Objects.requireNonNull(expectedTargetIdentity, "expectedTargetIdentity");
            if (version <= 0) throw new IllegalArgumentException(
                    "Transition version must be positive");
            Objects.requireNonNull(fromStatus, "fromStatus");
            Objects.requireNonNull(toStatus, "toStatus");
            Objects.requireNonNull(outcomeJson, "outcomeJson");
            if (actionSequence != null && actionSequence <= 0) {
                throw new IllegalArgumentException("Transition action sequence must be positive");
            }
            decidedAt = EngineCommandPolicy.canonicalPersistedTimestamp(
                    decidedAt, "transition decidedAt");
            requireDigest(previousDecisionDigest, "previousDecisionDigest");
            requireDigest(nextDecisionDigest, "nextDecisionDigest");
        }

        public TransitionRow withPreviousDecisionDigest(String digest) {
            return new TransitionRow(commandId, tenantId, operationId, commandType,
                    expectedTargetIdentity, version, fromStatus, toStatus, outcomeJson,
                    actionSequence, decidedAt, digest, nextDecisionDigest);
        }
    }

    public record RecordedTransition(
            TransitionRow row, EngineCommandPolicy.Decision nextDecision) {
        public RecordedTransition {
            Objects.requireNonNull(row, "row");
            Objects.requireNonNull(nextDecision, "nextDecision");
        }
    }

    private static void requireDigest(String digest, String field) {
        if (digest == null || !digest.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException(field + " must be lower-case SHA-256");
        }
    }
}
