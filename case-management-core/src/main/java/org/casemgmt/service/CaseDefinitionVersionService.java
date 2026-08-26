package org.casemgmt.service;

import org.casemgmt.domain.CaseDefinition;
import org.casemgmt.error.InvalidCaseDefinitionException;
import org.casemgmt.release.CaseDefinitionRelease;
import org.casemgmt.release.CaseDefinitionVersionBinding;
import org.casemgmt.release.ReleaseKind;
import org.casemgmt.release.ReleaseStatus;
import org.casemgmt.release.BpmnReleaseValidator;
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

    public CaseDefinitionVersionService(CaseDefinitionReleaseRepository releases,
                                        CaseDefinitionVersionBindingRepository bindings,
                                        CaseDefinitionService definitions) {
        this.releases = releases;
        this.bindings = bindings;
        this.definitions = definitions;
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

    @SuppressWarnings("unchecked")
    private static void validateBoundArtifacts(String key, CaseDefinitionRelease orchestration,
                                               CaseDefinitionRelease contractRelease,
                                               CaseDefinitionRelease presentationRelease) {
        BpmnReleaseValidator.Index orchestrationIndex = BpmnReleaseValidator.validate(key,
                orchestration.content(), orchestration.mediaType());
        Map<String, Object> contract = JsonCodec.toMap(new String(contractRelease.content(),
                StandardCharsets.UTF_8));
        Map<String, Object> presentation = JsonCodec.toMap(new String(presentationRelease.content(),
                StandardCharsets.UTF_8));

        if (!key.equals(text(contract.get("key")))) {
            throw invalid(key, "Contract key must equal case-definition key '" + key + "'");
        }

        Set<String> forms = keys(contract.get("forms"));
        for (String formRef : orchestrationIndex.formRefs()) {
            if (!dynamic(formRef) && !forms.contains(formRef)) {
                throw invalid(key, "BPMN user task references unknown contract form '" + formRef + "'");
            }
        }
        Set<String> candidateGroups = new LinkedHashSet<>(strings(contract.get("candidateGroups")));
        for (String candidateGroup : orchestrationIndex.candidateGroups()) {
            if (!candidateGroups.contains(candidateGroup)) {
                throw invalid(key, "BPMN user task references undeclared candidate group '"
                        + candidateGroup + "'");
            }
        }
        Set<String> slaBindings = keys(contract.get("slaBindings"));
        for (String slaRef : orchestrationIndex.slaRefs()) {
            if (!dynamic(slaRef) && !slaBindings.contains(slaRef)) {
                throw invalid(key, "BPMN element references unknown SLA binding '" + slaRef + "'");
            }
        }
        validateAdHocActions(key, contract, forms, candidateGroups);
        Set<String> fields = catalogIds(contract.getOrDefault("fields", contract.get("fieldCatalog")));
        Set<String> actions = new LinkedHashSet<>(Set.of("update", "cancel", "close", "claim",
                "complete", "comment", "add-document", "remove-document", "start-process"));
        actions.addAll(actionIds(contract.get("adHocActions")));
        Set<String> searchProfiles = keys(contract.get("searchProfiles"));

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

    private static void validateAdHocActions(String key, Map<String, Object> contract,
                                             Set<String> forms, Set<String> candidateGroups) {
        Set<String> roles = new LinkedHashSet<>(strings(contract.get("roles")));
        for (Map<String, Object> action : actionDocuments(contract.get("adHocActions"))) {
            String id = text(action.get("id"));
            String type = text(action.get("type"));
            if (id == null || type == null || strings(action.get("roles")).isEmpty()) {
                throw invalid(key, "Every ad-hoc action requires id, type, and at least one role");
            }
            for (String role : strings(action.get("roles"))) {
                if (!roles.contains(role)) {
                    throw invalid(key, "Ad-hoc action '" + id + "' references undeclared role '"
                            + role + "'");
                }
            }
            String form = text(action.getOrDefault("formRef", action.get("formKey")));
            if (form != null && !forms.contains(form)) {
                throw invalid(key, "Ad-hoc action '" + id + "' references unknown form '" + form + "'");
            }
            for (String group : strings(action.get("candidateGroups"))) {
                if (!candidateGroups.contains(group)) {
                    throw invalid(key, "Ad-hoc action '" + id
                            + "' references undeclared candidate group '" + group + "'");
                }
            }
            switch (type.toUpperCase(Locale.ROOT)) {
                case "TASK" -> { }
                case "PROCESS" -> requireActionField(key, id, action, "processDefinitionKey");
                case "MESSAGE" -> requireActionField(key, id, action, "messageName");
                default -> throw invalid(key, "Ad-hoc action '" + id
                        + "' has unsupported type '" + type + "'");
            }
        }
    }

    private static void requireActionField(String key, String id, Map<String, Object> action,
                                           String field) {
        if (text(action.get(field)) == null) {
            throw invalid(key, "Ad-hoc action '" + id + "' requires " + field);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> actionDocuments(Object raw) {
        if (raw instanceof List<?> list) {
            return list.stream().filter(Map.class::isInstance)
                    .map(item -> (Map<String, Object>) item).toList();
        }
        if (raw instanceof Map<?, ?> map) {
            List<Map<String, Object>> result = new ArrayList<>();
            map.forEach((id, value) -> {
                if (value instanceof Map<?, ?> document) {
                    Map<String, Object> copy = new LinkedHashMap<>((Map<String, Object>) document);
                    copy.putIfAbsent("id", String.valueOf(id));
                    result.add(copy);
                }
            });
            return result;
        }
        return List.of();
    }

    private static Set<String> catalogIds(Object raw) {
        if (raw instanceof Map<?, ?> map) return map.keySet().stream().map(String::valueOf)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (raw instanceof List<?> list) {
            Set<String> ids = new LinkedHashSet<>();
            for (Object item : list) {
                if (item instanceof String text) ids.add(text);
                else if (item instanceof Map<?, ?> map && map.get("id") != null) {
                    ids.add(String.valueOf(map.get("id")));
                }
            }
            return ids;
        }
        return Set.of();
    }

    private static Set<String> actionIds(Object raw) {
        if (raw instanceof Map<?, ?> map) return map.keySet().stream().map(String::valueOf)
                .collect(java.util.stream.Collectors.toSet());
        if (raw instanceof List<?> list) return list.stream().filter(Map.class::isInstance)
                .map(Map.class::cast).map(value -> value.get("id")).filter(Objects::nonNull)
                .map(String::valueOf).collect(java.util.stream.Collectors.toSet());
        return Set.of();
    }

    private static Set<String> keys(Object raw) {
        return raw instanceof Map<?, ?> map ? map.keySet().stream().map(String::valueOf)
                .collect(java.util.stream.Collectors.toSet()) : Set.of();
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
