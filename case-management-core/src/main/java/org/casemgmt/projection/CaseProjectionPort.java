package org.casemgmt.projection;

/** Vendor-neutral write port shared by embedded events and remote reconciliation. */
public interface CaseProjectionPort {

    void observe(TaskObservation observation);

    void observe(ActivityObservation observation);

    void observe(ProcessCompletionObservation observation);
}
