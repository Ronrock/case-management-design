package org.casemgmt.service;

import org.casemgmt.release.CaseContractValidator;
import org.casemgmt.release.CaseDefinitionRelease;
import org.casemgmt.release.CaseDefinitionVersionBinding;
import org.casemgmt.release.CombinedCaseDefinitionArchive;
import org.casemgmt.release.JsonSchemaCaseContractValidator;
import org.casemgmt.release.ReleaseKind;
import org.casemgmt.repo.JsonCodec;
import org.casemgmt.error.InvalidCaseDefinitionException;

import java.nio.charset.StandardCharsets;
import org.springframework.transaction.annotation.Transactional;

public class CombinedCaseDefinitionDeploymentService {

    private final CaseDefinitionReleaseService releases;
    private final CaseDefinitionVersionService versions;
    private final CaseContractValidator contracts;

    public CombinedCaseDefinitionDeploymentService(CaseDefinitionReleaseService releases,
                                                   CaseDefinitionVersionService versions) {
        this(releases, versions, new JsonSchemaCaseContractValidator());
    }

    public CombinedCaseDefinitionDeploymentService(CaseDefinitionReleaseService releases,
                                                   CaseDefinitionVersionService versions,
                                                   CaseContractValidator contracts) {
        this.releases = releases;
        this.versions = versions;
        this.contracts = contracts;
    }

    @Transactional
    public CaseDefinitionVersionBinding deploy(String tenantId, byte[] archive, String deployedBy) {
        CombinedCaseDefinitionArchive parsed = CombinedCaseDefinitionArchive.read("<combined>", archive);
        Object rawKey = JsonCodec.toMap(parsed.contractJson()).get("key");
        if (rawKey == null || rawKey.toString().isBlank()) {
            throw new InvalidCaseDefinitionException("<combined>",
                    "Combined deployment contract.json requires key");
        }
        String key = rawKey.toString();
        // Before publication, not after: a release row that was written and then rolled back is
        // still a release an operator saw appear. Binding validates again because it is a public
        // entry point in its own right.
        contracts.validate(key, parsed.contractJson().getBytes(StandardCharsets.UTF_8));
        CaseDefinitionRelease orchestration = releases.publish(key, tenantId,
                ReleaseKind.ORCHESTRATION, "application/zip", parsed.orchestrationZip(), deployedBy);
        CaseDefinitionRelease contract = releases.publish(key, tenantId, ReleaseKind.CONTRACT,
                "application/json", parsed.contractJson().getBytes(StandardCharsets.UTF_8), deployedBy);
        CaseDefinitionRelease presentation = releases.publish(key, tenantId,
                ReleaseKind.PRESENTATION, "application/json",
                parsed.presentationJson().getBytes(StandardCharsets.UTF_8), deployedBy);
        return versions.bind(key, tenantId, orchestration.id(), contract.id(), presentation.id(),
                deployedBy);
    }
}
