package org.casemgmt.engine.embedded;

import org.casemgmt.observation.ProcessCaseAuthority;

import java.util.Objects;
import java.util.Optional;

/** Compatibility adapter; persisted authority and backfill are owned by core. */
public final class PersistedProcessCaseCorrelation implements ProcessCaseCorrelation {

    private final ProcessCaseAuthority authority;

    public PersistedProcessCaseCorrelation(ProcessCaseAuthority authority) {
        this.authority = Objects.requireNonNull(authority, "authority");
    }

    @Override
    public String caseId(String processInstanceId) {
        return authority.caseId(processInstanceId);
    }

    @Override
    public String caseId(String processInstanceId, String processDefinitionId) {
        return authority.caseId(processInstanceId, processDefinitionId);
    }

    @Override
    public Optional<Authority> authority(String processInstanceId, String processDefinitionId) {
        return authority.authority(processInstanceId, processDefinitionId);
    }
}
