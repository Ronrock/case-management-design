package org.casemgmt.orchestration;

/** Command-side adapter used by the remote outbox dispatcher. */
public interface OrchestrationDeploymentClient {

    EngineDeploymentIdentity deploy(String releaseId, String definitionKey, String tenantId,
                                    byte[] content, String mediaType);
}
