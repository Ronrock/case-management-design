package org.casemgmt.engine;

/** Production lifecycle of one durable remote-engine operation. */
public enum EngineCommandStatus {
    PENDING,
    DISPATCHING,
    RETRYABLE,
    AWAITING_CONFIRMATION,
    CONFIRMED,
    FAILED,
    CONFLICT,
    MANUAL_REVIEW,
    CANCELLED;

    public boolean isTerminal() {
        return this == CONFIRMED || this == FAILED || this == CANCELLED;
    }
}
