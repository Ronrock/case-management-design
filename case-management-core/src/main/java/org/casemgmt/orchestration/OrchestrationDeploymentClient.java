package org.casemgmt.orchestration;

/** Command-side adapter used by the remote outbox dispatcher. */
public interface OrchestrationDeploymentClient {

    String deploy(String releaseId, String definitionKey, String tenantId, byte[] content,
                  String mediaType);
}
