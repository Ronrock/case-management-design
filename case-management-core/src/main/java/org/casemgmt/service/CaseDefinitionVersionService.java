package org.casemgmt.service;

import org.casemgmt.domain.CaseDefinition;
import org.casemgmt.error.InvalidCaseDefinitionException;
import org.casemgmt.release.CaseDefinitionRelease;
import org.casemgmt.release.CaseDefinitionVersionBinding;
import org.casemgmt.release.BindingStatus;
import org.casemgmt.release.ReleaseKind;
import org.casemgmt.release.ReleaseStatus;
import org.casemgmt.release.BpmnReleaseValidator;
import org.casemgmt.release.CaseContractValidator;
import org.casemgmt.release.JsonSchemaCaseContractValidator;
import org.casemgmt.release.ValidatedCaseContract;
import org.casemgmt.repo.CaseDefinitionReleaseRepository;
import org.casemgmt.repo.CaseDefinitionVersionBindingRepository;
import org.casemgmt.repo.JsonCodec;
import org.casemgmt.sla.SlaCalendarCatalog;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.transaction.annotation.Transactional;

public class CaseDefinitionVersionService {

    private static final int MAX_REPORTED_REFERENCE_ERRORS = 20;
    private static final int MAX_REFERENCE_ERROR_LENGTH = 240;

    private final CaseDefinitionReleaseRepository releases;
    private final CaseDefinitionVersionBindingRepository bindings;
    private final CaseDefinitionService definitions;
    private final CaseContractValidator contracts;
    private final SlaCalendarCatalog calendars;

    public CaseDefinitionVersionService(CaseDefinitionReleaseRepository releases,
                                        CaseDefinitionVersionBindingRepository bindings,
                                        CaseDefinitionService definitions,
                                        SlaCalendarCatalog calendars) {
        this(releases, bindings, definitions, new JsonSchemaCaseContractValidator(), calendars);
    }

    public CaseDefinitionVersionService(CaseDefinitionReleaseRepository releases,
                                        CaseDefinitionVersionBindingRepository bindings,
                                        CaseDefinitionService definitions,
                                        CaseContractValidator contracts,
                                        SlaCalendarCatalog calendars) {
        this.releases = releases;
        this.bindings = bindings;
        this.definitions = definitions;
        this.contracts = contracts;
        this.calendars = Objects.requireNonNull(calendars, "calendars");
    }

    @Transactional
    public CaseDefinitionVersionBinding bind(
            String key, String tenantId, String orchestrationReleaseId,
            String contractReleaseId, String presentationReleaseId, String boundBy) {
        return bindInternal(key, tenantId, orchestrationReleaseId, contractReleaseId,
                presentationReleaseId, boundBy, false);
    }

    @Transactional
    CaseDefinitionVersionBinding bindPendingDeployment(
            String key, String tenantId, String orchestrationReleaseId,
            String contractReleaseId, String presentationReleaseId, String boundBy) {
        return bindInternal(key, tenantId, orchestrationReleaseId, contractReleaseId,
                presentationReleaseId, boundBy, true);
    }

    private CaseDefinitionVersionBinding bindInternal(
            String key, String tenantId, String orchestrationReleaseId,
            String contractReleaseId, String presentationReleaseId, String boundBy,
            boolean pendingOrchestrationAllowed) {
        CaseDefinitionRelease orchestration = pendingOrchestrationAllowed
                ? requirePendingOrActiveOrchestration(orchestrationReleaseId, tenantId, key)
                : require(
                orchestrationReleaseId, tenantId, key, ReleaseKind.ORCHESTRATION);
        CaseDefinitionRelease contract = require(
                contractReleaseId, tenantId, key, ReleaseKind.CONTRACT);
        CaseDefinitionRelease presentation = require(
                presentationReleaseId, tenantId, key, ReleaseKind.PRESENTATION);
        validateBoundArtifacts(key, tenantId, orchestration, contract, presentation);
        if (orchestration.status() == ReleaseStatus.ACTIVE) {
            var identity = orchestration.engineIdentity();
            if (identity == null) {
                throw invalid(key, "Active BPMN orchestration release '" + orchestration.id()
                        + "' has no verified engine identity");
            }
            if (!key.equals(identity.processDefinitionKey())
                    || !Objects.equals(tenantId, identity.tenantId())) {
                throw invalid(key, "Verified engine identity for release '" + orchestration.id()
                        + "' does not match the binding key and tenant");
            }
        }
        CaseDefinition definition = definitions.deployBpmn(key,
                new String(contract.content(), StandardCharsets.UTF_8), boundBy, tenantId);
        boolean active = orchestration.status() == ReleaseStatus.ACTIVE;
        OffsetDateTime now = OffsetDateTime.now();
        CaseDefinitionVersionBinding draft = new CaseDefinitionVersionBinding(
                definition.id(), key, tenantId, orchestration.id(), orchestration.sha256(),
                contract.id(), contract.sha256(), presentation.id(), presentation.sha256(),
                orchestration.status(), org.casemgmt.orchestration.OrchestrationMode.BPMN,
                BindingStatus.DRAFT, null, null, now, null, null, boundBy);
        bindings.insert(draft);
        if (!active) return draft;

        CaseDefinitionVersionBinding activated = draft.activate(
                orchestration, contract, presentation, orchestration.engineIdentity(), now);
        bindings.activate(activated);
        return activated;
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
    private void validateBoundArtifacts(String key, String tenantId, CaseDefinitionRelease orchestration,
                                        CaseDefinitionRelease contractRelease,
                                        CaseDefinitionRelease presentationRelease) {
        validateArtifacts(key, tenantId, orchestration.content(), orchestration.mediaType(),
                contractRelease.content(), presentationRelease.content());
    }

    /**
     * Performs the complete, side-effect-free validation needed before any artifact in a combined
     * bundle is published or deployed. Binding calls the same gate again because it is also a
     * public entry point and releases can be published independently.
     */
    public void validateArtifacts(String key, String tenantId, byte[] orchestrationContent,
                                  String orchestrationMediaType,
                                  byte[] contractContent, byte[] presentationContent) {
        requireDeclaredOrchestrationMode(key, contractContent);
        ValidatedCaseContract contract = contracts.validate(key, contractContent);
        for (ValidatedCaseContract.SlaBindingDefinition binding : contract.slaBindings()) {
            try {
                calendars.require(tenantId, binding.calendarId(), binding.calendarRevision());
            } catch (org.casemgmt.error.NotFoundException missing) {
                throw invalid(key, "SLA binding '" + binding.id() + "' references missing calendar '"
                        + binding.calendarId() + "' revision " + binding.calendarRevision()
                        + " for tenant '" + tenantId + "'");
            }
        }
        if (contract.orchestrationMode() != org.casemgmt.orchestration.OrchestrationMode.BPMN) {
            throw invalid(key, "A BPMN release binding requires orchestrationMode BPMN");
        }

        BpmnReleaseValidator.Index orchestrationIndex = BpmnReleaseValidator.validate(key,
                orchestrationContent, orchestrationMediaType);
        Map<String, Object> presentation = JsonCodec.toMap(new String(presentationContent,
                StandardCharsets.UTF_8));
        ReferenceErrors referenceErrors = new ReferenceErrors();

        Set<String> forms = contract.forms().keySet();
        for (String formRef : orchestrationIndex.formRefs()) {
            if (!dynamic(formRef) && !forms.contains(formRef)) {
                referenceErrors.add("BPMN user task references unknown contract form '" + formRef + "'");
            }
        }
        Set<String> candidateGroups = contract.candidateGroups();
        for (String candidateGroup : orchestrationIndex.candidateGroups()) {
            if (!candidateGroups.contains(candidateGroup)) {
                referenceErrors.add("BPMN user task references undeclared candidate group '"
                        + candidateGroup + "'");
            }
        }
        Set<String> slaTargets = contract.slaTargetIds();
        for (BpmnReleaseValidator.SlaReference slaRef : orchestrationIndex.slaRefs()) {
            if (!slaTargets.contains(slaRef.targetId())) {
                referenceErrors.add("BPMN element '" + slaRef.elementId()
                        + "' references unknown SLA binding '" + slaRef.targetId() + "'");
            }
        }
        validateSlaReferences(contract, orchestrationIndex, referenceErrors);
        validateAdHocActions(contract, referenceErrors);
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
                    referenceErrors.add("Presentation references unknown canonical field '" + field + "'");
                }
            }
            String formId = text(section.get("formId"));
            if (formId != null && !forms.contains(formId)) {
                referenceErrors.add("Presentation references unknown form '" + formId + "'");
            }
            for (String action : strings(section.get("actions"))) {
                if (!actions.contains(action)) {
                    referenceErrors.add("Presentation references unknown action '" + action + "'");
                }
            }
            String searchProfile = text(section.get("searchProfileId"));
            if (searchProfile != null && !searchProfiles.contains(searchProfile)) {
                referenceErrors.add("Presentation references unknown search profile '"
                        + searchProfile + "'");
            }
        }
        rejectReferenceErrors(key, referenceErrors);
    }

    private static void validateSlaReferences(
            ValidatedCaseContract contract, BpmnReleaseValidator.Index orchestration,
            ReferenceErrors errors) {
        Map<String, ValidatedCaseContract.SlaBindingDefinition> bindings = new LinkedHashMap<>();
        contract.slaBindings().forEach(binding -> bindings.put(binding.id(), binding));
        Set<String> referencedBindings = orchestration.slaRefs().stream()
                .map(BpmnReleaseValidator.SlaReference::targetId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        for (ValidatedCaseContract.SlaBindingDefinition binding : contract.slaBindings()) {
            if (!referencedBindings.contains(binding.id())) {
                errors.add("SLA binding '" + binding.id()
                        + "' is not executable because no BPMN element references it");
            }
        }
        for (BpmnReleaseValidator.SlaReference reference : orchestration.slaRefs()) {
            ValidatedCaseContract.SlaBindingDefinition binding = bindings.get(reference.targetId());
            if (binding == null) continue;
            if (binding.scope() != ValidatedCaseContract.SlaScope.OCCURRENCE
                    && !binding.scope().name().equals(reference.kind().name())) {
                errors.add("SLA binding '" + binding.id() + "' has scope " + binding.scope()
                        + " but BPMN element '" + reference.elementId() + "' is "
                        + reference.kind());
            }
            if (binding.scope() == ValidatedCaseContract.SlaScope.OCCURRENCE) {
                if (binding.occurrenceKey() == null) {
                    errors.add("SLA binding '" + binding.id()
                            + "' with scope OCCURRENCE requires occurrenceKey");
                }
                if (reference.kind() == BpmnReleaseValidator.ElementKind.CASE) {
                    errors.add("SLA binding '" + binding.id()
                            + "' with scope OCCURRENCE requires a repeatable non-root "
                            + "TASK, STAGE, or MILESTONE element");
                } else if (!reference.repeatable()) {
                    errors.add("SLA binding '" + binding.id() + "' with scope OCCURRENCE "
                            + "references non-repeatable BPMN element '"
                            + reference.elementId() + "'");
                }
            }
            ValidatedCaseContract.SlaAnchorFamily expected = switch (reference.kind()) {
                case CASE -> ValidatedCaseContract.SlaAnchorFamily.CASE;
                case TASK -> ValidatedCaseContract.SlaAnchorFamily.TASK;
                case STAGE -> ValidatedCaseContract.SlaAnchorFamily.STAGE;
                case MILESTONE -> ValidatedCaseContract.SlaAnchorFamily.MILESTONE;
            };
            for (ValidatedCaseContract.SlaAnchor anchor : slaAnchors(binding)) {
                if (anchor.family() != expected) {
                    errors.add("SLA binding '" + binding.id() + "' on "
                            + reference.kind() + " element '" + reference.elementId()
                            + "' cannot use " + anchor + " from the " + anchor.family()
                            + " anchor family");
                }
            }
        }
    }

    private static List<ValidatedCaseContract.SlaAnchor> slaAnchors(
            ValidatedCaseContract.SlaBindingDefinition binding) {
        List<ValidatedCaseContract.SlaAnchor> anchors = new ArrayList<>();
        anchors.add(binding.startAnchor());
        anchors.add(binding.meetAnchor());
        if (binding.cancelAnchor() != null) anchors.add(binding.cancelAnchor());
        anchors.addAll(binding.pauseAnchors());
        anchors.addAll(binding.resumeAnchors());
        return anchors;
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
    private static void validateAdHocActions(ValidatedCaseContract contract,
                                             ReferenceErrors errors) {
        for (ValidatedCaseContract.AdHocActionDefinition action : contract.adHocActions()) {
            for (String role : action.roles()) {
                if (!contract.roles().contains(role)) {
                    errors.add("Ad-hoc action '" + action.id()
                            + "' references undeclared role '" + role + "'");
                }
            }
            String form = action.formRef();
            if (form != null && !contract.forms().containsKey(form)) {
                errors.add("Ad-hoc action '" + action.id()
                        + "' references unknown form '" + form + "'");
            }
            for (String group : action.candidateGroups()) {
                if (!contract.candidateGroups().contains(group)) {
                    errors.add("Ad-hoc action '" + action.id()
                            + "' references undeclared candidate group '" + group + "'");
                }
            }
        }
    }

    private static void rejectReferenceErrors(String key, ReferenceErrors errors) {
        if (errors.isEmpty()) return;
        StringBuilder message = new StringBuilder("Case-definition artifact references are invalid:");
        errors.retained().forEach(error -> message.append("\n  ").append(error));
        if (errors.additionalFindings() > 0) {
            message.append("\n  ...and ").append(errors.additionalFindings())
                    .append(" additional reference findings");
        }
        throw invalid(key, message.toString());
    }

    /**
     * Keeps validation diagnostics useful without allowing a hostile bundle to make memory use
     * grow with every broken reference. Retaining the lexicographically smallest messages makes
     * the result stable even if a source collection changes iteration order.
     */
    private static final class ReferenceErrors {
        private final NavigableSet<String> retained = new TreeSet<>();
        private int findings;

        void add(String error) {
            findings++;
            String bounded = error.length() <= MAX_REFERENCE_ERROR_LENGTH
                    ? error
                    : error.substring(0, MAX_REFERENCE_ERROR_LENGTH) + "...";
            retained.add(bounded);
            if (retained.size() > MAX_REPORTED_REFERENCE_ERRORS) {
                retained.pollLast();
            }
        }

        boolean isEmpty() {
            return findings == 0;
        }

        Set<String> retained() {
            return Collections.unmodifiableNavigableSet(retained);
        }

        int additionalFindings() {
            return findings - retained.size();
        }
    }

    /**
     * A bundle reaching a binding must say which side owns the process.
     *
     * <p>Binding is a publication boundary: the release must declare its orchestration mode
     * rather than leave the system to infer one from the available properties.
     */
    private static void requireDeclaredOrchestrationMode(String key, byte[] contractContent) {
        Map<String, Object> raw = JsonCodec.toMap(
                new String(contractContent, StandardCharsets.UTF_8));
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
        if (release.status() != ReleaseStatus.ACTIVE) {
            throw new InvalidCaseDefinitionException(key, "Release '" + id + "' is "
                    + release.status() + "; public binding requires every constituent release "
                    + "to be ACTIVE");
        }
        return release;
    }

    private CaseDefinitionRelease requirePendingOrActiveOrchestration(
            String id, String tenantId, String key) {
        CaseDefinitionRelease release = releases.require(id, tenantId);
        if (release.kind() != ReleaseKind.ORCHESTRATION
                || !release.definitionKey().equals(key)) {
            throw new InvalidCaseDefinitionException(key, "Release '" + id
                    + "' is not an orchestration release for definition '" + key + "'");
        }
        if (release.status() != ReleaseStatus.DEPLOYING
                && release.status() != ReleaseStatus.ACTIVE) {
            throw new InvalidCaseDefinitionException(key, "Release '" + id + "' is "
                    + release.status() + "; combined publication requires DEPLOYING or ACTIVE");
        }
        return release;
    }
}
