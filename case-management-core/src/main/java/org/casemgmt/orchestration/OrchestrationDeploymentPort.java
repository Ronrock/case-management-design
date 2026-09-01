package org.casemgmt.orchestration;

import org.casemgmt.release.ReleaseStatus;

public interface OrchestrationDeploymentPort {

    DeploymentResult deploy(String releaseId, String definitionKey, String tenantId, byte[] content,
                            String mediaType);

    record DeploymentResult(ReleaseStatus status, EngineDeploymentIdentity identity,
                            String failureDetail) {
        public static DeploymentResult active(EngineDeploymentIdentity identity) {
            return new DeploymentResult(ReleaseStatus.ACTIVE, identity, null);
        }

        public static DeploymentResult deploying() {
            return new DeploymentResult(ReleaseStatus.DEPLOYING, null, null);
        }
    }
}
