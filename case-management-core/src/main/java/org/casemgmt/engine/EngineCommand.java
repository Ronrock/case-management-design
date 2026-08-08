package org.casemgmt.engine;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * One pending (or already-attempted) effect a remote-mode case mutation needs the Operaton
 * engine to apply — a row in {@code CM_ENGINE_COMMAND} (spec §3.5). The command outbox exists
 * because a remote engine cannot join the local case transaction: the case mutation writes this
 * row instead of calling the engine directly, and {@link EngineCommandDispatcher} delivers it
 * afterwards, retrying with backoff until it succeeds or is dead-lettered.
 */
public record EngineCommand(String id, String caseId, Type type, Map<String, Object> payload,
                            String status, int attempts, OffsetDateTime nextAttemptAt, String lastError) {

    public enum Type { CREATE_TASK, CLAIM_TASK, COMPLETE_TASK, START_PROCESS, CANCEL_PROCESS }

    /** Shared with the webhook dispatcher (Task 20): 1m, 5m, 25m, 2h, 10h, then dead. */
    public static final List<Duration> BACKOFF = List.of(
            Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(25),
            Duration.ofHours(2), Duration.ofHours(10));

    public static boolean exhausted(int attempts) {
        return attempts >= BACKOFF.size();
    }

    public static OffsetDateTime nextAttempt(int attempts) {
        return OffsetDateTime.now().plus(BACKOFF.get(Math.min(attempts, BACKOFF.size() - 1)));
    }
}
