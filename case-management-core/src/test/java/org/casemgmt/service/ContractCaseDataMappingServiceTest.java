package org.casemgmt.service;

import org.casemgmt.domain.CaseInstance;
import org.casemgmt.domain.CasePriority;
import org.casemgmt.domain.CaseState;
import org.casemgmt.orchestration.OrchestrationMode;
import org.casemgmt.release.BindingStatus;
import org.casemgmt.release.CaseContractValidator;
import org.casemgmt.release.CaseDefinitionRelease;
import org.casemgmt.release.CaseDefinitionVersionBinding;
import org.casemgmt.release.JsonSchemaCaseContractValidator;
import org.casemgmt.release.ReleaseKind;
import org.casemgmt.release.ReleaseStatus;
import org.casemgmt.release.ValidatedCaseContract;
import org.casemgmt.repo.CaseDefinitionReleaseRepository;
import org.casemgmt.repo.CaseDefinitionVersionBindingRepository;
import org.casemgmt.repo.CaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ContractCaseDataMappingServiceTest {

    private CaseRepository cases;
    private CaseDefinitionVersionBindingRepository bindings;
    private CaseDefinitionReleaseRepository releases;
    private ContractCaseDataMappingService service;

    @BeforeEach
    void setUp() {
        cases = mock(CaseRepository.class);
        bindings = mock(CaseDefinitionVersionBindingRepository.class);
        releases = mock(CaseDefinitionReleaseRepository.class);
        service = new ContractCaseDataMappingService(cases, bindings, releases,
                new JsonSchemaCaseContractValidator());
    }

    @Test
    void mapsOnlyDeclaredEngineOutputsAndKeepsTaskKeyAsProvenance() {
        bind(contract("""
                "mappings": [
                  {"direction":"ENGINE_TO_CASE", "source":"decisionVar", "target":"decision",
                   "type":"string", "required":true},
                  {"direction":"CASE_TO_ENGINE", "source":"amount", "target":"amountVar",
                   "type":"integer"}
                ]
                """), Map.of("decision", "pending", "amount", 25), 7L);

        CanonicalPatch patch = service.mapTaskOutput("case-1", "reviewTask",
                Map.of("decisionVar", "approved", "amountVar", 999,
                        "undeclaredEngineVariable", "engine-only"));

        assertThat(patch.caseId()).isEqualTo("case-1");
        assertThat(patch.taskDefinitionKey()).isEqualTo("reviewTask");
        assertThat(patch.expectedCaseVersion()).isEqualTo(7L);
        assertThat(patch.changes()).singleElement().satisfies(change -> {
            assertThat(change.mappingPath()).isEqualTo("/mappings/0");
            assertThat(change.source()).isEqualTo("decisionVar");
            assertThat(change.fieldId()).isEqualTo("decision");
            assertThat(change.expectedValue()).isEqualTo("pending");
            assertThat(change.value()).isEqualTo("approved");
        });
        assertThat(patch.changes()).extracting(CanonicalPatch.FieldChange::fieldId)
                .doesNotContain("amount", "undeclaredEngineVariable");
    }

    @Test
    void topLevelOutputMappingsAreContractWideRatherThanInventingTaskScope() {
        bind(contract("""
                "mappings": [
                  {"direction":"ENGINE_TO_CASE", "source":"decisionVar", "target":"decision"}
                ]
                """), Map.of("decision", "pending"), 2L);

        CanonicalPatch firstTask = service.mapTaskOutput("case-1", "reviewTask",
                Map.of("decisionVar", "approved"));
        CanonicalPatch anotherTask = service.mapTaskOutput("case-1", "closeTask",
                Map.of("decisionVar", "approved"));

        assertThat(firstTask.changes()).hasSize(1);
        assertThat(anotherTask.changes()).hasSize(1);
        assertThat(firstTask.taskDefinitionKey()).isEqualTo("reviewTask");
        assertThat(anotherTask.taskDefinitionKey()).isEqualTo("closeTask");
    }

    @Test
    void rejectsMissingRequiredAndWrongTypedSourcesWithSafeMappingPaths() {
        bind(contract("""
                "mappings": [
                  {"direction":"ENGINE_TO_CASE", "source":"decisionVar", "target":"decision",
                   "type":"string", "required":true}
                ]
                """), Map.of("decision", "pending"), 3L);

        assertThatThrownBy(() -> service.mapTaskOutput("case-1", "reviewTask", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("/mappings/0/source")
                .hasMessageContaining("required");

        assertThatThrownBy(() -> service.mapTaskOutput("case-1", "reviewTask",
                Map.of("decisionVar", 42)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("/mappings/0/source")
                .hasMessageContaining("string")
                .hasMessageNotContaining("42");
    }

    @Test
    void validatesMappedValuesAgainstTheCanonicalFieldSchema() {
        bind(contract("""
                "mappings": [
                  {"direction":"ENGINE_TO_CASE", "source":"decisionVar", "target":"decision",
                   "type":"string"}
                ]
                """), Map.of("decision", "pending"), 4L);

        assertThatThrownBy(() -> service.mapTaskOutput("case-1", "reviewTask",
                Map.of("decisionVar", "not-a-published-enum-value")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("/mappings/0/target")
                .hasMessageContaining("decision")
                .hasMessageNotContaining("not-a-published-enum-value");
    }

    @Test
    void acceptsAnIntegralEngineNumberForAJsonIntegerMapping() {
        bind(contract("""
                "mappings": [
                  {"direction":"ENGINE_TO_CASE", "source":"amountVar", "target":"amount",
                   "type":"integer"}
                ]
                """), Map.of("amount", 25), 4L);

        CanonicalPatch patch = service.mapTaskOutput("case-1", "reviewTask",
                Map.of("amountVar", 42.0d));

        assertThat((BigDecimal) patch.changes().getFirst().value())
                .isEqualByComparingTo("42");
    }

    @Test
    void redactsSensitiveValuesFromGeneralAuditSummaries() {
        bind(contract("""
                "mappings": [
                  {"direction":"ENGINE_TO_CASE", "source":"publicVar", "target":"decision"},
                  {"direction":"ENGINE_TO_CASE", "source":"secretVar", "target":"secret"}
                ]
                """), Map.of("decision", "pending", "secret", "old-secret"), 5L);

        CanonicalPatch patch = service.mapTaskOutput("case-1", "reviewTask",
                Map.of("publicVar", "approved", "secretVar", "new-secret"));

        assertThat(patch.auditSummary()).containsExactly(
                new CanonicalPatch.AuditChange("decision", "publicVar", "/mappings/0",
                        CanonicalPatch.WriteMode.REPLACE, "pending", "approved", false),
                new CanonicalPatch.AuditChange("secret", "secretVar", "/mappings/1",
                        CanonicalPatch.WriteMode.REPLACE, CanonicalPatch.REDACTED,
                        CanonicalPatch.REDACTED, true));
        assertThat(patch.auditSummary().toString())
                .doesNotContain("old-secret", "new-secret");
    }

    @Test
    void rejectsAReleaseThatNoLongerMatchesTheCasesImmutableBinding() {
        CaseInstance c = caseInstance(Map.of(), 1L);
        when(cases.require("case-1")).thenReturn(c);
        when(bindings.find("sample-case:1")).thenReturn(Optional.of(binding("expected-sha")));
        byte[] content = contract("\"mappings\": []").getBytes(StandardCharsets.UTF_8);
        when(releases.require("contract-1", "tenant-a")).thenReturn(new CaseDefinitionRelease(
                "contract-1", "sample-case", "tenant-a", ReleaseKind.CONTRACT,
                "application/json", content, "different-sha", ReleaseStatus.ACTIVE,
                null, null, null, null, null, null, OffsetDateTime.now(), "publisher"));

        assertThatThrownBy(() -> service.mapTaskOutput("case-1", "reviewTask", Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("immutable binding")
                .hasMessageNotContaining(new String(content, StandardCharsets.UTF_8));
    }

    @Test
    void preservesAnExplicitNullWhenTheCanonicalSchemaAllowsIt() {
        bind("""
                {
                  "key":"sample-case",
                  "orchestrationMode":"BPMN",
                  "fields":{"nullableField":{"schema":{}}},
                  "forms":{},
                  "mappings":[
                    {"direction":"ENGINE_TO_CASE", "source":"nullableVar",
                     "target":"nullableField"}
                  ]
                }
                """, Map.of(), 6L);
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("nullableVar", null);

        CanonicalPatch patch = service.mapTaskOutput("case-1", "reviewTask", variables);

        assertThat(patch.changes()).singleElement().satisfies(change -> {
            assertThat(change.expectedPresent()).isFalse();
            assertThat(change.value()).isNull();
        });
    }

    @Test
    void keepsUsingTheCasesPinnedContractAfterANewerVersionRetiresItsBinding() {
        String contract = contract("""
                "mappings": [
                  {"direction":"ENGINE_TO_CASE", "source":"decisionVar", "target":"decision"}
                ]
                """);
        when(cases.require("case-1")).thenReturn(caseInstance(Map.of("decision", "pending"), 8L));
        when(bindings.find("sample-case:1")).thenReturn(Optional.of(
                binding("contract-sha", BindingStatus.RETIRED)));
        when(releases.require("contract-1", "tenant-a")).thenReturn(new CaseDefinitionRelease(
                "contract-1", "sample-case", "tenant-a", ReleaseKind.CONTRACT,
                "application/json", contract.getBytes(StandardCharsets.UTF_8), "contract-sha",
                ReleaseStatus.RETIRED, null, null, null, null, null, null,
                OffsetDateTime.now(), "publisher"));

        CanonicalPatch patch = service.mapTaskOutput("case-1", "reviewTask",
                Map.of("decisionVar", "approved"));

        assertThat(patch.expectedCaseVersion()).isEqualTo(8L);
        assertThat(patch.changes()).singleElement()
                .extracting(CanonicalPatch.FieldChange::value).isEqualTo("approved");
    }

    @Test
    void mergeBuildsAndValidatesOneCompletePostMergeCanonicalValue() {
        bind("""
                {
                  "key":"sample-case",
                  "orchestrationMode":"BPMN",
                  "fields":{"profile":{"schema":{
                    "type":"object",
                    "required":["name","language"],
                    "additionalProperties":false,
                    "properties":{
                      "name":{"type":"string"},
                      "language":{"type":"string","enum":["nl","en"]}
                    }
                  }}},
                  "forms":{},
                  "mappings":[
                    {"direction":"ENGINE_TO_CASE", "source":"profileVar", "target":"profile",
                     "type":"object", "writeMode":"MERGE"}
                  ]
                }
                """, Map.of("profile", Map.of("name", "Alice", "language", "nl")), 9L);

        CanonicalPatch patch = service.mapTaskOutput("case-1", "reviewTask",
                Map.of("profileVar", Map.of("language", "en")));

        assertThat(patch.changes()).singleElement().satisfies(change -> {
            assertThat(change.writeMode()).isEqualTo(CanonicalPatch.WriteMode.MERGE);
            assertThat(change.expectedValue())
                    .isEqualTo(Map.of("name", "Alice", "language", "nl"));
            assertThat(change.value())
                    .isEqualTo(Map.of("name", "Alice", "language", "en"));
        });
        assertThat(patch.auditSummary()).containsExactly(new CanonicalPatch.AuditChange(
                "profile", "profileVar", "/mappings/0", CanonicalPatch.WriteMode.MERGE,
                Map.of("name", "Alice", "language", "nl"),
                Map.of("name", "Alice", "language", "en"), false));
    }

    @Test
    void defensivelyRejectsDuplicateEngineOutputsFromACustomContractValidator() {
        bind(contract("\"mappings\":[]"), Map.of("decision", "pending"), 4L);
        ValidatedCaseContract.FieldDefinition decision =
                new ValidatedCaseContract.FieldDefinition("decision", Map.of("type", "string"),
                        List.of(), List.of());
        ValidatedCaseContract.MappingDefinition first = mapping("firstDecision", "decision");
        ValidatedCaseContract.MappingDefinition duplicate = mapping("finalDecision", "decision");
        ValidatedCaseContract duplicateContract = new ValidatedCaseContract(
                "sample-case", OrchestrationMode.BPMN, Map.of("decision", decision), Map.of(),
                List.of(first, duplicate), List.of(), List.of(), Set.of(), Set.of(), Set.of());
        CaseContractValidator customValidator = mock(CaseContractValidator.class);
        when(customValidator.validate("sample-case", releases.require("contract-1", "tenant-a")
                .content())).thenReturn(duplicateContract);
        ContractCaseDataMappingService customService = new ContractCaseDataMappingService(
                cases, bindings, releases, customValidator);

        assertThatThrownBy(() -> customService.mapTaskOutput("case-1", "reviewTask",
                Map.of("firstDecision", "approved", "finalDecision", "rejected")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("/mappings/1/target")
                .hasMessageContaining("duplicate ENGINE_TO_CASE target")
                .hasMessageContaining("/mappings/0/target");
    }

    private static ValidatedCaseContract.MappingDefinition mapping(String source, String target) {
        return new ValidatedCaseContract.MappingDefinition(
                ValidatedCaseContract.MappingDirection.ENGINE_TO_CASE, source, target,
                ValidatedCaseContract.MappingType.STRING,
                ValidatedCaseContract.MappingWriteMode.REPLACE, false, null, List.of(), Map.of());
    }

    private void bind(String contract, Map<String, Object> variables, long version) {
        CaseInstance c = caseInstance(variables, version);
        when(cases.require("case-1")).thenReturn(c);
        when(bindings.find("sample-case:1")).thenReturn(Optional.of(binding("contract-sha")));
        when(releases.require("contract-1", "tenant-a")).thenReturn(new CaseDefinitionRelease(
                "contract-1", "sample-case", "tenant-a", ReleaseKind.CONTRACT,
                "application/json", contract.getBytes(StandardCharsets.UTF_8), "contract-sha",
                ReleaseStatus.ACTIVE, null, null, null, null, null, null,
                OffsetDateTime.now(), "publisher"));
    }

    private static CaseDefinitionVersionBinding binding(String contractSha) {
        return binding(contractSha, BindingStatus.ACTIVE);
    }

    private static CaseDefinitionVersionBinding binding(String contractSha, BindingStatus status) {
        return new CaseDefinitionVersionBinding("sample-case:1", "sample-case", "tenant-a",
                "orchestration-1", "orchestration-sha", "contract-1", contractSha,
                "presentation-1", "presentation-sha", ReleaseStatus.ACTIVE,
                OrchestrationMode.BPMN, status, null, null,
                OffsetDateTime.now(), OffsetDateTime.now(), null, "publisher");
    }

    private static CaseInstance caseInstance(Map<String, Object> variables, long version) {
        OffsetDateTime now = OffsetDateTime.now();
        return new CaseInstance("case-1", "engine-a", "tenant-a", "sample-case:1",
                "sample-case", 1, "BK-1", "Sample", CaseState.ACTIVE, CasePriority.MEDIUM,
                null, null, "alice", "NONE", null, null, variables, version, now, now, null);
    }

    private static String contract(String mappings) {
        return """
                {
                  "key":"sample-case",
                  "orchestrationMode":"BPMN",
                  "fields":{
                    "amount":{"schema":{"type":"integer"}},
                    "decision":{"schema":{"type":"string","enum":["pending","approved","rejected"]}},
                    "secret":{"schema":{"type":"string","writeOnly":true}}
                  },
                  "forms":{},
                  %s
                }
                """.formatted(mappings);
    }
}
