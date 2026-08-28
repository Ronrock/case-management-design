package org.casemgmt.observation;

/** Non-business rejection telemetry; implementations must not persist in a new transaction. */
@FunctionalInterface
public interface ObservationSecurityTelemetry {

    void rejected(Rejection rejection);

    record Rejection(String caseId, String processInstanceId, String entityId,
                     ObservationRejectionReason reason) { }

    static ObservationSecurityTelemetry none() {
        return rejection -> { };
    }
}
