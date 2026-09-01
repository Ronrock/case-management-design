package org.casemgmt.orchestration;

import org.casemgmt.domain.CaseIds;
import org.casemgmt.engine.EngineCommand;
import org.casemgmt.repo.EngineCommandRepository;

import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** Persists remote deployments in the same transaction as release publication. */
public final class OutboxOrchestrationDeploymentPort implements OrchestrationDeploymentPort {

    private final EngineCommandRepository commands;

    public OutboxOrchestrationDeploymentPort(EngineCommandRepository commands) {
        this.commands = commands;
    }

    @Override
    public DeploymentResult deploy(String releaseId, String definitionKey, String tenantId,
                                   byte[] content, String mediaType) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("releaseId", releaseId);
        payload.put("definitionKey", definitionKey);
        payload.put("tenantId", tenantId == null ? "" : tenantId);
        payload.put("mediaType", mediaType);
        payload.put("contentBase64", Base64.getEncoder().encodeToString(content));
        commands.enqueue(new EngineCommand(CaseIds.newId(), definitionKey,
                EngineCommand.Type.DEPLOY_ORCHESTRATION, payload, "PENDING", 0,
                OffsetDateTime.now(), null));
        return DeploymentResult.deploying();
    }
}
