package org.casemgmt.service;

import org.casemgmt.domain.CaseInstance;
import org.casemgmt.error.FormValidationException;
import org.casemgmt.release.BindingStatus;
import org.casemgmt.release.CaseContractValidator;
import org.casemgmt.release.CaseDefinitionRelease;
import org.casemgmt.release.CaseDefinitionVersionBinding;
import org.casemgmt.release.ReleaseKind;
import org.casemgmt.release.ValidatedCaseContract;
import org.casemgmt.repo.CaseDefinitionReleaseRepository;
import org.casemgmt.repo.CaseDefinitionVersionBindingRepository;
import org.casemgmt.repo.CaseRepository;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Resolves the exact contract release pinned to a case and maps its declared outputs only. */
public final class ContractCaseDataMappingService implements CaseDataMappingService {

    private final CaseRepository cases;
    private final CaseDefinitionVersionBindingRepository bindings;
    private final CaseDefinitionReleaseRepository releases;
    private final CaseContractValidator contracts;
    private final FormValidator fieldValidator = new FormValidator();

    public ContractCaseDataMappingService(
            CaseRepository cases,
            CaseDefinitionVersionBindingRepository bindings,
            CaseDefinitionReleaseRepository releases,
            CaseContractValidator contracts) {
        this.cases = Objects.requireNonNull(cases, "cases");
        this.bindings = Objects.requireNonNull(bindings, "bindings");
        this.releases = Objects.requireNonNull(releases, "releases");
        this.contracts = Objects.requireNonNull(contracts, "contracts");
    }

    @Override
    public CanonicalPatch mapTaskOutput(String caseId, String taskDefinitionKey,
                                        Map<String, Object> engineVariables) {
        requireNonBlank(caseId, "caseId");
        requireNonBlank(taskDefinitionKey, "taskDefinitionKey");
        Map<String, Object> variables = engineVariables == null ? Map.of() : engineVariables;
        CaseInstance c = cases.require(caseId);
        ValidatedCaseContract contract = boundContract(c);

        List<CanonicalPatch.FieldChange> changes = new ArrayList<>();
        for (int index = 0; index < contract.mappings().size(); index++) {
            ValidatedCaseContract.MappingDefinition mapping = contract.mappings().get(index);
            if (mapping.direction() != ValidatedCaseContract.MappingDirection.ENGINE_TO_CASE) {
                continue;
            }
            String path = "/mappings/" + index;
            ValidatedCaseContract.FieldDefinition field = contract.fields().get(mapping.target());
            if (field == null) {
                throw invalid(path + "/target", "does not name a canonical field");
            }
            if (mapping.transformRef() != null) {
                throw invalid(path + "/transformRef", "has no registered transform");
            }
            if (!variables.containsKey(mapping.source())) {
                if (mapping.required()) {
                    throw invalid(path + "/source", "is required but absent");
                }
                continue;
            }
            Object value = variables.get(mapping.source());
            validateType(path, mapping, field, value);
            Object expected = c.variables().get(mapping.target());
            Object finalValue = mapping.writeMode() == ValidatedCaseContract.MappingWriteMode.MERGE
                    ? merge(path, expected, value) : value;
            validateSchema(path, field, finalValue);
            changes.add(new CanonicalPatch.FieldChange(path, mapping.source(), mapping.target(),
                    writeMode(mapping.writeMode()), c.variables().containsKey(mapping.target()),
                    expected, finalValue, sensitive(field)));
        }
        return new CanonicalPatch(caseId, taskDefinitionKey, c.version(), changes);
    }

    @Override
    public PatchResult apply(CanonicalPatch patch) {
        return cases.applyCanonicalPatch(Objects.requireNonNull(patch, "patch"));
    }

    private ValidatedCaseContract boundContract(CaseInstance c) {
        CaseDefinitionVersionBinding binding = bindings.find(c.caseDefId())
                .orElseThrow(() -> new IllegalStateException(
                        "Case '" + c.id() + "' has no immutable contract binding"));
        CaseDefinitionRelease release = releases.require(binding.contractReleaseId(), c.tenantId());
        if (!c.caseDefId().equals(binding.caseDefinitionId())
                || !c.caseDefKey().equals(binding.caseDefinitionKey())
                || !Objects.equals(c.tenantId(), binding.tenantId())
                || (binding.status() != BindingStatus.ACTIVE
                    && binding.status() != BindingStatus.RETIRED)
                || !binding.contractReleaseId().equals(release.id())
                || !binding.contractSha256().equals(release.sha256())
                || release.kind() != ReleaseKind.CONTRACT
                || !c.caseDefKey().equals(release.definitionKey())
                || !Objects.equals(c.tenantId(), release.tenantId())) {
            throw new IllegalStateException(
                    "Published contract release does not match case '" + c.id()
                            + "' immutable binding");
        }
        return contracts.validate(c.caseDefKey(), release.content());
    }

    private void validateType(String path, ValidatedCaseContract.MappingDefinition mapping,
                              ValidatedCaseContract.FieldDefinition field, Object value) {
        ValidatedCaseContract.MappingType expected = mapping.type();
        if (expected == null) {
            Object schemaType = field.schema().get("type");
            if (schemaType instanceof String type) {
                try {
                    expected = ValidatedCaseContract.MappingType.valueOf(
                            type.toUpperCase(java.util.Locale.ROOT));
                } catch (IllegalArgumentException ignored) {
                    // The complete field schema below remains authoritative for composite types.
                }
            }
        }
        if (expected != null && !matches(expected, value)) {
            throw invalid(path + "/source", "must have type "
                    + expected.name().toLowerCase(java.util.Locale.ROOT));
        }
        if (mapping.writeMode() == ValidatedCaseContract.MappingWriteMode.MERGE
                && !(value instanceof Map<?, ?>)) {
            throw invalid(path + "/source", "must be an object for MERGE");
        }
    }

    private void validateSchema(String path, ValidatedCaseContract.FieldDefinition field,
                                Object value) {
        Map<String, Object> wrapper = new LinkedHashMap<>();
        wrapper.put("type", "object");
        wrapper.put("properties", Map.of(field.id(), field.schema()));
        wrapper.put("required", List.of(field.id()));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(field.id(), value);
        try {
            fieldValidator.validate(wrapper, payload);
        } catch (FormValidationException invalid) {
            throw invalid(path + "/target", "does not satisfy canonical field '"
                    + field.id() + "' schema");
        }
    }

    private static boolean matches(ValidatedCaseContract.MappingType type, Object value) {
        if (value == null) return false;
        return switch (type) {
            case STRING -> value instanceof String;
            case INTEGER -> integer(value);
            case NUMBER -> number(value);
            case BOOLEAN -> value instanceof Boolean;
            case OBJECT -> value instanceof Map<?, ?>;
            case ARRAY -> value instanceof List<?>;
        };
    }

    private static boolean integer(Object value) {
        if (value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long || value instanceof BigInteger) return true;
        if (value instanceof BigDecimal decimal) return decimal.stripTrailingZeros().scale() <= 0;
        if (!number(value)) return false;
        try {
            return new BigDecimal(value.toString()).stripTrailingZeros().scale() <= 0;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static boolean number(Object value) {
        if (!(value instanceof Number number)) return false;
        return !(number instanceof Double d && !Double.isFinite(d))
                && !(number instanceof Float f && !Float.isFinite(f));
    }

    private static CanonicalPatch.WriteMode writeMode(
            ValidatedCaseContract.MappingWriteMode writeMode) {
        return writeMode == ValidatedCaseContract.MappingWriteMode.MERGE
                ? CanonicalPatch.WriteMode.MERGE : CanonicalPatch.WriteMode.REPLACE;
    }

    private static Map<String, Object> merge(String path, Object expected, Object fragment) {
        if (!(expected instanceof Map<?, ?> prior)) {
            throw invalid(path + "/target", "cannot MERGE into a non-object canonical value");
        }
        Map<String, Object> merged = new LinkedHashMap<>();
        prior.forEach((key, value) -> merged.put(String.valueOf(key), value));
        ((Map<?, ?>) fragment).forEach((key, value) -> merged.put(String.valueOf(key), value));
        return merged;
    }

    private static boolean sensitive(ValidatedCaseContract.FieldDefinition field) {
        return Boolean.TRUE.equals(field.schema().get("writeOnly"))
                || Boolean.TRUE.equals(field.schema().get("x-sensitive"));
    }

    private static IllegalArgumentException invalid(String path, String reason) {
        return new IllegalArgumentException(path + " " + reason);
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
