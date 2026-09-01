package org.casemgmt.sla;

import org.casemgmt.domain.CaseInstance;
import org.casemgmt.release.BindingStatus;
import org.casemgmt.release.CaseContractValidator;
import org.casemgmt.release.CaseDefinitionRelease;
import org.casemgmt.release.CaseDefinitionVersionBinding;
import org.casemgmt.release.ReleaseKind;
import org.casemgmt.release.ValidatedCaseContract;
import org.casemgmt.repo.CaseDefinitionReleaseRepository;
import org.casemgmt.repo.CaseDefinitionVersionBindingRepository;

import java.util.Objects;

/** Resolves the exact published contract immutable-bound to a running case. */
final class BoundSlaContractResolver {

    private final CaseDefinitionVersionBindingRepository bindings;
    private final CaseDefinitionReleaseRepository releases;
    private final CaseContractValidator contracts;

    BoundSlaContractResolver(CaseDefinitionVersionBindingRepository bindings,
                             CaseDefinitionReleaseRepository releases,
                             CaseContractValidator contracts) {
        this.bindings = Objects.requireNonNull(bindings, "bindings");
        this.releases = Objects.requireNonNull(releases, "releases");
        this.contracts = Objects.requireNonNull(contracts, "contracts");
    }

    ResolvedContract resolve(CaseInstance instance) {
        CaseDefinitionVersionBinding binding = bindings.find(instance.caseDefId()).orElseThrow(
                () -> new IllegalStateException("Case '" + instance.id()
                        + "' has no immutable contract binding"));
        CaseDefinitionRelease release = releases.require(binding.contractReleaseId(), instance.tenantId());
        if (!instance.caseDefId().equals(binding.caseDefinitionId())
                || !instance.caseDefKey().equals(binding.caseDefinitionKey())
                || !Objects.equals(instance.tenantId(), binding.tenantId())
                || (binding.status() != BindingStatus.ACTIVE && binding.status() != BindingStatus.RETIRED)
                || !binding.contractReleaseId().equals(release.id())
                || !binding.contractSha256().equals(release.sha256())
                || release.kind() != ReleaseKind.CONTRACT
                || !instance.caseDefKey().equals(release.definitionKey())
                || !Objects.equals(instance.tenantId(), release.tenantId())) {
            throw new IllegalStateException("Published contract release does not match case '"
                    + instance.id() + "' immutable binding");
        }
        return new ResolvedContract(release.id(), release.sha256(),
                contracts.validate(instance.caseDefKey(), release.content()));
    }

    record ResolvedContract(String releaseId, String sha256, ValidatedCaseContract contract) { }
}
