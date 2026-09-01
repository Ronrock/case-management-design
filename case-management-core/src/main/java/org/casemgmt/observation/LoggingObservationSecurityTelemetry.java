package org.casemgmt.observation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Rollback-independent, redacted rejection telemetry backed only by the application log. */
public final class LoggingObservationSecurityTelemetry implements ObservationSecurityTelemetry {

    private static final Logger LOG = LoggerFactory.getLogger(
            LoggingObservationSecurityTelemetry.class);

    @Override
    public void rejected(Rejection rejection) {
        LOG.warn("Engine observation rejected reason={} caseId={} processInstanceId={} entityId={}",
                rejection.reason(), rejection.caseId(), rejection.processInstanceId(),
                rejection.entityId());
    }
}
