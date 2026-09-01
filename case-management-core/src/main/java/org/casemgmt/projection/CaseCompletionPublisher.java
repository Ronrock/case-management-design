package org.casemgmt.projection;

import java.time.OffsetDateTime;

@FunctionalInterface
public interface CaseCompletionPublisher {

    void publish(String caseId, String terminalState, OffsetDateTime completedAt);

    static CaseCompletionPublisher none() {
        return (caseId, terminalState, completedAt) -> { };
    }
}
