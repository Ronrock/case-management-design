package org.casemgmt.observation;

/** Safe authority rejection carrying only a bounded reason and stable case identifier. */
public final class ObservationAuthorityException extends SecurityException {
    private final ObservationRejectionReason reason;

    public ObservationAuthorityException(ObservationRejectionReason reason, String caseId) {
        super("Engine observation rejected for case " + caseId + ": "
                + reason.name().toLowerCase(java.util.Locale.ROOT));
        this.reason = reason;
    }

    public ObservationRejectionReason reason() {
        return reason;
    }
}
