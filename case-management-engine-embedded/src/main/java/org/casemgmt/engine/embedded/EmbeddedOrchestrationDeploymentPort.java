package org.casemgmt.engine.embedded;

import org.casemgmt.orchestration.OrchestrationDeploymentPort;
import org.operaton.bpm.engine.RepositoryService;

import java.io.ByteArrayInputStream;
import java.util.zip.ZipInputStream;

public class EmbeddedOrchestrationDeploymentPort implements OrchestrationDeploymentPort {

    private final RepositoryService repositoryService;

    public EmbeddedOrchestrationDeploymentPort(RepositoryService repositoryService) {
        this.repositoryService = repositoryService;
    }

    @Override
    public DeploymentResult deploy(String releaseId, String definitionKey, String tenantId,
                                   byte[] content, String mediaType) {
        var builder = repositoryService.createDeployment()
                .name("case-release:" + releaseId)
                .tenantId(tenantId)
                .enableDuplicateFiltering(true);
        if ("application/zip".equals(mediaType)) {
            builder.addZipInputStream(new ZipInputStream(new ByteArrayInputStream(content)));
        } else {
            builder.addInputStream(definitionKey + ".bpmn", new ByteArrayInputStream(content));
        }
        return DeploymentResult.active(builder.deploy().getId());
    }
}
