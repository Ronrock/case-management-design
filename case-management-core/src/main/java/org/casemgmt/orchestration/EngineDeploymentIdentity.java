package org.casemgmt.orchestration;

/**
 * The exact executable process definition verified for one orchestration deployment.
 *
 * <p>The deployment id alone is not enough to start the approved process: one deployment can
 * contain several resources. This value therefore keeps the engine's immutable process-definition
 * id together with the key/version/tenant that were checked before activation.
 */
public record EngineDeploymentIdentity(
        String deploymentId,
        String processDefinitionId,
        String processDefinitionKey,
        Integer processDefinitionVersion,
        String tenantId) {

    public EngineDeploymentIdentity {
        requireNonBlank(deploymentId, "deploymentId");
        requireNonBlank(processDefinitionId, "processDefinitionId");
        requireNonBlank(processDefinitionKey, "processDefinitionKey");
        if (processDefinitionVersion == null || processDefinitionVersion < 1) {
            throw new IllegalArgumentException("processDefinitionVersion must be positive");
        }
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
