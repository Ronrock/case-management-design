package org.casemgmt.projection;

/** Vendor-neutral write port shared by embedded events and remote reconciliation. */
public interface CaseProjectionPort {

    /** Read-only guard used before a handler applies business effects. */
    void assertEntityOwnership(ProjectionEntityIdentity identity);

    void observe(TaskObservation observation);

    void observe(ActivityObservation observation);

    void observe(ProcessCompletionObservation observation);

    /** Handler-safe projection that reports the authoritative root transition without callbacks. */
    ProcessProjectionResult observeFromHandler(ProcessCompletionObservation observation);

    /** Restores one stale terminal linked-process row from explicit active engine evidence. */
    boolean observeStartedFromHandler(ProcessStartObservation observation);
}
