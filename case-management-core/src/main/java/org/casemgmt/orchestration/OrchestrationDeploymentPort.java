package org.casemgmt.orchestration;

import org.casemgmt.release.ReleaseStatus;

public interface OrchestrationDeploymentPort {

    DeploymentResult deploy(String releaseId, String definitionKey, String tenantId, byte[] content,
                            String mediaType);

    record DeploymentResult(ReleaseStatus status, String engineDeploymentId, String failureDetail) {
        public static DeploymentResult active(String engineDeploymentId) {
            return new DeploymentResult(ReleaseStatus.ACTIVE, engineDeploymentId, null);
        }

        public static DeploymentResult deploying() {
            return new DeploymentResult(ReleaseStatus.DEPLOYING, null, null);
        }
    }
}
