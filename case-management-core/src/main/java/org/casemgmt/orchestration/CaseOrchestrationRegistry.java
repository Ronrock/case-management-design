package org.casemgmt.orchestration;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public final class CaseOrchestrationRegistry {

    private final Map<OrchestrationMode, CaseOrchestration> orchestrations;

    public CaseOrchestrationRegistry(List<CaseOrchestration> orchestrations) {
        EnumMap<OrchestrationMode, CaseOrchestration> indexed =
                new EnumMap<>(OrchestrationMode.class);
        for (CaseOrchestration orchestration : orchestrations) {
            if (indexed.putIfAbsent(orchestration.mode(), orchestration) != null) {
                throw new IllegalArgumentException(
                        "Multiple orchestrations registered for " + orchestration.mode());
            }
        }
        this.orchestrations = Map.copyOf(indexed);
    }

    public CaseOrchestration require(OrchestrationMode mode) {
        CaseOrchestration orchestration = orchestrations.get(mode);
        if (orchestration == null) {
            throw new IllegalStateException("No case orchestration registered for " + mode);
        }
        return orchestration;
    }
}
