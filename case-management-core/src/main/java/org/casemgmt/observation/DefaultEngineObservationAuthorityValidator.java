package org.casemgmt.observation;

import org.casemgmt.domain.CaseInstance;
import org.casemgmt.orchestration.OrchestrationMode;
import org.casemgmt.release.BindingStatus;
import org.casemgmt.release.ReleaseStatus;
import org.casemgmt.repo.CaseDefinitionVersionBindingRepository;
import org.casemgmt.repo.LinkedProcessRepository;

import java.util.Objects;

/** WS2 binding-backed authority check for accepted BPMN lifecycle observations. */
public final class DefaultEngineObservationAuthorityValidator
        implements EngineObservationAuthorityValidator {

    private final CaseDefinitionVersionBindingRepository bindings;
    private final LinkedProcessRepository processes;
    private final String engineId;

    public DefaultEngineObservationAuthorityValidator(
            CaseDefinitionVersionBindingRepository bindings,
            LinkedProcessRepository processes,
            String engineId) {
        this.bindings = Objects.requireNonNull(bindings, "bindings");
        this.processes = Objects.requireNonNull(processes, "processes");
        if (engineId == null || engineId.isBlank()) {
            throw new IllegalArgumentException("engineId must not be blank");
        }
        this.engineId = engineId;
    }

    @Override
    public void validate(EngineObservation observation, CaseInstance caseInstance) {
        if (!Objects.equals(observation.tenantId(), caseInstance.tenantId())) {
            reject(observation, ObservationRejectionReason.TENANT_MISMATCH);
        }
        String observedEngineId = requiredAttribute(observation, "engineId",
                ObservationRejectionReason.ENGINE_MISMATCH);
        if (!engineId.equals(observedEngineId) || !engineId.equals(caseInstance.engineId())) {
            reject(observation, ObservationRejectionReason.ENGINE_MISMATCH);
        }

        var binding = bindings.find(caseInstance.caseDefId())
                .orElseThrow(() -> rejection(observation,
                        ObservationRejectionReason.BINDING_MISSING));
        if (!binding.caseDefinitionId().equals(caseInstance.caseDefId())
                || !binding.caseDefinitionKey().equals(caseInstance.caseDefKey())
                || !Objects.equals(binding.tenantId(), caseInstance.tenantId())) {
            reject(observation, ObservationRejectionReason.BINDING_IDENTITY_MISMATCH);
        }
        if (binding.orchestrationMode() != OrchestrationMode.BPMN) {
            reject(observation, ObservationRejectionReason.NON_BPMN_BINDING);
        }
        if ((binding.status() != BindingStatus.ACTIVE
                && binding.status() != BindingStatus.RETIRED)
                || binding.deploymentStatus() != ReleaseStatus.ACTIVE) {
            reject(observation, ObservationRejectionReason.BINDING_STATUS);
        }
        var identity = binding.engineIdentity();
        if (identity == null || !Objects.equals(identity.tenantId(), caseInstance.tenantId())) {
            reject(observation, ObservationRejectionReason.BINDING_IDENTITY_MISMATCH);
        }

        String observedDefinitionId = requiredAttribute(observation, "processDefinitionId",
                ObservationRejectionReason.PROCESS_DEFINITION_MISMATCH);
        String observedDefinitionKey = requiredAttribute(observation, "processDefinitionKey",
                ObservationRejectionReason.PROCESS_DEFINITION_MISMATCH);
        var linked = processes.findByCase(caseInstance.id()).stream()
                .filter(row -> Objects.equals(row.processInstanceId(),
                        observation.processInstanceId()))
                .findFirst()
                .orElseThrow(() -> rejection(observation,
                        ObservationRejectionReason.PROCESS_NOT_LINKED));
        boolean root = Objects.equals(caseInstance.rootProcessInstanceId(),
                observation.processInstanceId());
        if (root) {
            if (!identity.processDefinitionId().equals(observedDefinitionId)
                    || !identity.processDefinitionKey().equals(observedDefinitionKey)
                    || !linked.caseRoot()
                    || !identity.processDefinitionKey().equals(linked.processDefinitionKey())) {
                reject(observation, ObservationRejectionReason.PROCESS_DEFINITION_MISMATCH);
            }
        } else if (!linked.processDefinitionKey().equals(observedDefinitionKey)) {
            reject(observation, ObservationRejectionReason.PROCESS_DEFINITION_MISMATCH);
        }
    }

    private static String requiredAttribute(EngineObservation observation, String name,
                                            ObservationRejectionReason reason) {
        Object value = observation.attributes().get(name);
        if (!(value instanceof String string) || string.isBlank()) {
            reject(observation, reason);
        }
        return (String) value;
    }

    private static void reject(EngineObservation observation, ObservationRejectionReason reason) {
        throw rejection(observation, reason);
    }

    private static ObservationAuthorityException rejection(
            EngineObservation observation, ObservationRejectionReason reason) {
        return new ObservationAuthorityException(reason, observation.caseId());
    }
}
