package org.casemgmt.orchestration;

/** Resolves the exact engine identity pinned by an active immutable case-definition binding. */
@FunctionalInterface
public interface EngineDeploymentIdentityResolver {

    EngineDeploymentIdentity requireActive(String caseDefinitionId, String tenantId);
}
