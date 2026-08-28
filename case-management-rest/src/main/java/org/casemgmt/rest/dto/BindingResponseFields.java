package org.casemgmt.rest.dto;

import org.casemgmt.orchestration.EngineDeploymentIdentity;
import org.casemgmt.release.CaseDefinitionVersionBinding;

import java.util.Map;

/** Additive public fields describing one immutable case-definition version binding. */
public final class BindingResponseFields {

    private BindingResponseFields() {}

    public static void put(Map<String, Object> body, CaseDefinitionVersionBinding binding,
                           boolean includeOperationalIdentity) {
        body.put("orchestrationReleaseId", binding.orchestrationReleaseId());
        body.put("orchestrationSha256", binding.orchestrationSha256());
        body.put("contractReleaseId", binding.contractReleaseId());
        body.put("contractSha256", binding.contractSha256());
        body.put("presentationReleaseId", binding.presentationReleaseId());
        body.put("presentationSha256", binding.presentationSha256());
        body.put("deploymentStatus", binding.deploymentStatus().name());
        body.put("bindingStatus", binding.status().name());
        body.put("boundAt", binding.boundAt());
        body.put("activatedAt", binding.activatedAt());
        body.put("retiredAt", binding.retiredAt());

        EngineDeploymentIdentity identity = binding.engineIdentity();
        body.put("engineProcessDefinitionKey",
                identity == null ? null : identity.processDefinitionKey());
        body.put("engineProcessDefinitionVersion",
                identity == null ? null : identity.processDefinitionVersion());
        body.put("engineTenantId", identity == null ? null : identity.tenantId());
        if (includeOperationalIdentity) {
            body.put("engineDeploymentId", identity == null ? null : identity.deploymentId());
            body.put("engineProcessDefinitionId",
                    identity == null ? null : identity.processDefinitionId());
        }
    }
}
