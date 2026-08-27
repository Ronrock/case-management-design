package org.casemgmt.service;

import org.casemgmt.domain.CaseDefinition;
import org.casemgmt.error.InvalidCaseDefinitionException;
import org.casemgmt.release.CaseDefinitionRelease;
import org.casemgmt.release.CaseDefinitionVersionBinding;
import org.casemgmt.release.ReleaseKind;
import org.casemgmt.release.ReleaseStatus;
import org.casemgmt.release.BpmnReleaseValidator;
import org.casemgmt.release.CaseContractValidator;
import org.casemgmt.release.JsonSchemaCaseContractValidator;
import org.casemgmt.release.ValidatedCaseContract;
import org.casemgmt.repo.CaseDefinitionReleaseRepository;
import org.casemgmt.repo.CaseDefinitionVersionBindingRepository;
import org.casemgmt.repo.JsonCodec;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.transaction.annotation.Transactional;

public class CaseDefinitionVersionService {

    private final CaseDefinitionReleaseRepository releases;
    private final CaseDefinitionVersionBindingRepository bindings;
    private final CaseDefinitionService definitions;
    private final CaseContractValidator contracts;

    public CaseDefinitionVersionService(CaseDefinitionReleaseRepository releases,
                                        CaseDefinitionVersionBindingRepository bindings,
                                        CaseDefinitionService definitions) {
        this(releases, bindings, definitions, new JsonSchemaCaseContractValidator());
    }

    public CaseDefinitionVersionService(CaseDefinitionReleaseRepository releases,
                                        CaseDefinitionVersionBindingRepository bindings,
                                        CaseDefinitionService definitions,
                                        CaseContractValidator contracts) {
        this.releases = releases;
        this.bindings = bindings;
        this.definitions = definitions;
        this.contracts = contracts;
    }

    @Transactional
    public CaseDefinitionVersionBinding bind(
            String key, String tenantId, String orchestrationReleaseId,
            String contractReleaseId, String presentationReleaseId, String boundBy) {
        CaseDefinitionRelease orchestration = require(
                orchestrationReleaseId, tenantId, key, ReleaseKind.ORCHESTRATION);
        CaseDefinitionRelease contract = require(
                contractReleaseId, tenantId, key, ReleaseKind.CONTRACT);
        CaseDefinitionRelease presentation = require(
                presentationReleaseId, tenantId, key, ReleaseKind.PRESENTATION);
        validateBoundArtifacts(key, orchestration, contract, presentation);
        CaseDefinition definition = definitions.deployBpmn(key,
                new String(contract.content(), StandardCharsets.UTF_8), boundBy, tenantId);
        CaseDefinitionVersionBinding binding = new CaseDefinitionVersionBinding(
                definition.id(), orchestration.id(), orchestration.sha256(),
                contract.id(), contract.sha256(), presentation.id(), presentation.sha256(),
                orchestration.status(), OffsetDateTime.now(), boundBy);
        bindings.insert(binding);
        return binding;
    }

    /**
     * Validates the contract before anything is deployed or bound, then cross-checks the three
     * artifacts against each other.
     *
     * <p>Schema validation comes first deliberately. A binding is the point where a release
     * becomes selectable, so a contract that would fail at runtime has to fail here — before
     * {@code deployBpmn} runs and before a binding row exists. Cross-references then read the
     * typed {@link ValidatedCaseContract} rather than re-deriving meaning from
     * {@code Map<String,Object>}: the shape is already guaranteed, so what remains is genuinely
     * about whether two artifacts agree.
     */
    private void validateBoundArtifacts(String key, CaseDefinitionRelease orchestration,
                                        CaseDefinitionRelease contractRelease,
                                        CaseDefinitionRelease presentationRelease) {
        requireDeclaredOrchestrationMode(key, contractRelease);
        ValidatedCaseContract contract = contracts.validate(key, contractRelease.content());

        BpmnReleaseValidator.Index orchestrationIndex = BpmnReleaseValidator.validate(key,
                orchestration.content(), orchestration.mediaType());
        Map<String, Object> presentation = JsonCodec.toMap(new String(presentationRelease.content(),
                StandardCharsets.UTF_8));

        Set<String> forms = contract.forms().keySet();
        for (String formRef : orchestrationIndex.formRefs()) {
            if (!dynamic(formRef) && !forms.contains(formRef)) {
                throw invalid(key, "BPMN user task references unknown contract form '" + formRef + "'");
            }
        }
        Set<String> candidateGroups = contract.candidateGroups();
        for (String candidateGroup : orchestrationIndex.candidateGroups()) {
            if (!candidateGroups.contains(candidateGroup)) {
                throw invalid(key, "BPMN user task references undeclared candidate group '"
                        + candidateGroup + "'");
            }
        }
        Set<String> slaTargets = contract.slaTargetIds();
        for (String slaRef : orchestrationIndex.slaRefs()) {
            if (!dynamic(slaRef) && !slaTargets.contains(slaRef)) {
                throw invalid(key, "BPMN element references unknown SLA binding '" + slaRef + "'");
            }
        }
        validateAdHocActions(key, contract);
        Set<String> fields = contract.fields().keySet();
        Set<String> actions = new LinkedHashSet<>(Set.of("update", "cancel", "close", "claim",
                "complete", "comment", "add-document", "remove-document", "start-process"));
        contract.adHocActions().forEach(action -> actions.add(action.id()));
        Set<String> searchProfiles = contract.searchProfileIds();

        Object rawSections = presentation.get("sections");
        if (!(rawSections instanceof List<?> sections)) {
            throw invalid(key, "Presentation manifest requires sections");
        }
        for (Object raw : sections) {
            if (!(raw instanceof Map<?, ?> section)) continue;
            for (String field : strings(section.get("fields"))) {
                if (!field.startsWith("system:") && !fields.contains(field)) {
                    throw invalid(key, "Presentation references unknown canonical field '" + field + "'");
                }
            }
            String formId = text(section.get("formId"));
            if (formId != null && !forms.contains(formId)) {
                throw invalid(key, "Presentation references unknown form '" + formId + "'");
            }
            for (String action : strings(section.get("actions"))) {
                if (!actions.contains(action)) {
                    throw invalid(key, "Presentation references unknown action '" + action + "'");
                }
            }
            String searchProfile = text(section.get("searchProfileId"));
            if (searchProfile != null && !searchProfiles.contains(searchProfile)) {
                throw invalid(key, "Presentation references unknown search profile '"
                        + searchProfile + "'");
            }
        }
    }

    /**
     * Cross-checks each declared action against the rest of the contract.
     *
     * <p>Shape is no longer re-litigated here. The schema has already established that every
     * action has an id, a type and at least one role, and that a {@code PROCESS} carries a
     * process key and a {@code MESSAGE} a message name — the sealed variants make that a type
     * guarantee rather than a switch with a {@code default} that throws. What is left is the
     * only question this layer can answer: whether the names an action refers to are ones the
     * contract itself declares.
     */
    private static void validateAdHocActions(String key, ValidatedCaseContract contract) {
        for (ValidatedCaseContract.AdHocActionDefinition action : contract.adHocActions()) {
            for (String role : action.roles()) {
                if (!contract.roles().contains(role)) {
                    throw invalid(key, "Ad-hoc action '" + action.id()
                            + "' references undeclared role '" + role + "'");
                }
            }
            String form = action.formRef();
            if (form != null && !contract.forms().containsKey(form)) {
                throw invalid(key, "Ad-hoc action '" + action.id()
                        + "' references unknown form '" + form + "'");
            }
            for (String group : action.candidateGroups()) {
                if (!contract.candidateGroups().contains(group)) {
                    throw invalid(key, "Ad-hoc action '" + action.id()
                            + "' references undeclared candidate group '" + group + "'");
                }
            }
        }
    }

    /**
     * A bundle reaching a binding must say which side owns the process.
     *
     * <p>{@link JsonSchemaCaseContractValidator} deliberately treats an absent mode as the legacy
     * {@code PLAN_MODEL} default, so definitions published before BPMN-first keep loading
     * unchanged. Binding is new publication, and design §9.9 requires the mode to be declared
     * rather than inferred from which properties happen to be present — so the requirement is
     * enforced at this boundary rather than in the validator.
     */
    private static void requireDeclaredOrchestrationMode(String key,
                                                         CaseDefinitionRelease contractRelease) {
        Map<String, Object> raw = JsonCodec.toMap(
                new String(contractRelease.content(), StandardCharsets.UTF_8));
        if (text(raw.get("orchestrationMode")) == null) {
            throw invalid(key, "Contract must declare orchestrationMode explicitly; "
                    + "the platform does not infer it from which properties are present");
        }
    }

    private static List<String> strings(Object raw) {
        return raw instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of();
    }

    private static String text(Object raw) {
        return raw == null || raw.toString().isBlank() ? null : raw.toString();
    }

    private static boolean dynamic(String value) {
        return value.contains("${") || value.contains("#{");
    }

    private static InvalidCaseDefinitionException invalid(String key, String message) {
        return new InvalidCaseDefinitionException(key, message);
    }

    private CaseDefinitionRelease require(String id, String tenantId, String key,
                                          ReleaseKind expectedKind) {
        CaseDefinitionRelease release = releases.require(id, tenantId);
        if (release.kind() != expectedKind || !release.definitionKey().equals(key)) {
            throw new InvalidCaseDefinitionException(key, "Release '" + id + "' is not a "
                    + expectedKind.name().toLowerCase() + " release for definition '" + key + "'");
        }
        if (release.status() == ReleaseStatus.FAILED) {
            throw new InvalidCaseDefinitionException(key,
                    "Release '" + id + "' is failed and cannot be bound");
        }
        return release;
    }
}
