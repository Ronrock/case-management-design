package org.casemgmt.observation;

import java.time.Instant;

/**
 * Narrow Workstream 3 boundary for lifecycle-driven SLA changes.
 *
 * <p>Workstream 6 owns target resolution, clocks and scheduling. The observation handler only
 * announces an accepted anchor and root terminalization inside its caller transaction.
 */
public interface SlaLifecyclePort {

    record Anchor(String caseId, String observationKind, String eventType,
                  String entityId, Instant occurredAt) { }

    enum TerminalState { COMPLETED, CANCELLED }

    void observeAnchor(Anchor anchor);

    void terminalizeRoot(String caseId, TerminalState state, Instant occurredAt);

    static SlaLifecyclePort none() {
        return new SlaLifecyclePort() {
            @Override public void observeAnchor(Anchor anchor) { }
            @Override public void terminalizeRoot(String caseId, TerminalState state,
                                                  Instant occurredAt) { }
        };
    }
}
