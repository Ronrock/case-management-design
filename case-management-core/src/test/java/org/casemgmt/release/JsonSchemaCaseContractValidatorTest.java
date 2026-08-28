package org.casemgmt.release;

import org.casemgmt.error.InvalidCaseDefinitionException;
import org.casemgmt.orchestration.OrchestrationMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The publication-time contract for {@link CaseContractValidator} (Workstream 1, Task 1).
 *
 * <p>These tests are written before the validator exists. They fix three things that later
 * tasks may not quietly renegotiate:
 *
 * <ol>
 *   <li><b>Where validation happens.</b> A behaviour-driving structure is rejected by JSON
 *       Schema 2020-12 at publication, not by a handwritten check at runtime (design §9.9,
 *       review comment 6). Every closed object here proves an unknown property fails.</li>
 *   <li><b>Which side owns lifecycle.</b> In {@code BPMN} mode the contract may not declare
 *       lifecycle, gateways, task activation or process timers; BPMN running in Operaton owns
 *       them (design §7.1, review comment 4). {@code PLAN_MODEL} keeps those constructs and
 *       keeps its existing permissive shape — the two modes are separate schemas, not one
 *       schema with a union of properties.</li>
 *   <li><b>What a caller gets back.</b> A typed {@link ValidatedCaseContract}, so binding and
 *       runtime services stop re-parsing {@code Map<String,Object>} and re-deciding what an
 *       ad-hoc action means.</li>
 * </ol>
 *
 * <p>Assertions deliberately pin the JSON path of each violation. The path is the part of the
 * diagnostic a model author acts on, and §9.9 requires "unknown fields are rejected with a
 * path". Assertions on message prose are kept to the words that carry the explanation.
 */
class JsonSchemaCaseContractValidatorTest {

    private static final String KEY = "sample-case";

    private final CaseContractValidator validator = new JsonSchemaCaseContractValidator();

    // ---------------------------------------------------------------- mapping

    @Test
    void mapsAMinimalBpmnContract() {
        ValidatedCaseContract contract = validate("""
                {
                  "key": "sample-case",
                  "orchestrationMode": "BPMN",
                  "fields": {},
                  "forms": {}
                }""");

        assertThat(contract.key()).isEqualTo(KEY);
        assertThat(contract.orchestrationMode()).isEqualTo(OrchestrationMode.BPMN);
        assertThat(contract.fields()).isEmpty();
        assertThat(contract.forms()).isEmpty();
        assertThat(contract.slaBindings()).isEmpty();
        assertThat(contract.adHocActions()).isEmpty();
        assertThat(contract.roles()).isEmpty();
        assertThat(contract.candidateGroups()).isEmpty();
        assertThat(contract.searchProfileIds()).isEmpty();
    }

    @Test
    void mapsEveryDeclaredVocabularyOfAFullBpmnContract() {
        ValidatedCaseContract contract = validate(fullBpmnContract());

        assertThat(contract.roles()).containsExactlyInAnyOrder("handler", "reviewer");
        assertThat(contract.candidateGroups()).containsExactly("handlers");
        assertThat(contract.searchProfileIds()).containsExactly("complaints");

        assertThat(contract.fields()).containsOnlyKeys("amount", "outcome");
        var amount = contract.fields().get("amount");
        assertThat(amount.id()).isEqualTo("amount");
        assertThat(amount.schema()).containsEntry("type", "integer");
        assertThat(amount.writeRoles()).containsExactly("handler");

        assertThat(contract.forms()).containsOnlyKeys("reviewForm");
        var reviewForm = contract.forms().get("reviewForm");
        assertThat(reviewForm.id()).isEqualTo("reviewForm");
        assertThat(reviewForm.schema()).containsEntry("type", "object");
        assertThat(reviewForm.uiSchema()).isNotNull();
    }

    /**
     * {@code slaBindings} stays keyed by SLA target id, because that is the vocabulary a BPMN
     * {@code casemgmt:slaTargetId} attribute resolves against. The typed result is a list whose
     * elements carry their key back as {@code id()}, so a cross-reference check needs no second
     * pass over the raw map.
     */
    @Test
    void mapsSlaBindingsKeyedByTargetId() {
        ValidatedCaseContract contract = validate(fullBpmnContract());

        assertThat(contract.slaBindings()).singleElement().satisfies(binding -> {
            assertThat(binding.id()).isEqualTo("resolution");
            assertThat(binding.scope()).isEqualTo(ValidatedCaseContract.SlaScope.CASE);
            assertThat(binding.calendarId()).isEqualTo("nl-business");
            assertThat(binding.duration()).isEqualTo("P5D");
            assertThat(binding.startAnchor()).isEqualTo("CASE_CREATED");
            assertThat(binding.meetAnchor()).isEqualTo("CASE_CLOSED");
            assertThat(binding.warnings()).containsExactly("P4D");
        });
    }

    @Test
    void discriminatesEachAdHocActionVariantIntoItsOwnType() {
        ValidatedCaseContract contract = validate(fullBpmnContract());

        assertThat(contract.adHocActions()).hasSize(3);
        assertThat(contract.adHocActions().get(0))
                .isInstanceOfSatisfying(ValidatedCaseContract.TaskAction.class, action -> {
                    assertThat(action.id()).isEqualTo("investigate");
                    assertThat(action.roles()).containsExactly("handler");
                    assertThat(action.formRef()).isEqualTo("reviewForm");
                    assertThat(action.candidateGroups()).containsExactly("handlers");
                    assertThat(action.availabilityExpression())
                            .isEqualTo("${case.state == 'ACTIVE'}");
                });
        assertThat(contract.adHocActions().get(1))
                .isInstanceOfSatisfying(ValidatedCaseContract.ProcessAction.class, action ->
                        assertThat(action.processDefinitionKey()).isEqualTo("escalation"));
        assertThat(contract.adHocActions().get(2))
                .isInstanceOfSatisfying(ValidatedCaseContract.MessageAction.class, action ->
                        assertThat(action.messageName()).isEqualTo("complaint-withdrawn"));
    }

    // ------------------------------------------------------- required properties

    /**
     * Each case removes exactly one required property. JSON Schema reports a {@code required}
     * violation against the <em>containing</em> object, which leaves an author reading
     * {@code /fields/amount} and guessing; the validator appends the missing property so the
     * reported path is the one they have to edit.
     */
    @ParameterizedTest(name = "[{index}] missing {0}")
    @CsvSource(delimiter = '|', textBlock = """
            /key                                | {"orchestrationMode":"BPMN","fields":{},"forms":{}}
            /fields/amount/schema               | {"key":"sample-case","orchestrationMode":"BPMN","fields":{"amount":{}},"forms":{}}
            /forms/reviewForm/schema            | {"key":"sample-case","orchestrationMode":"BPMN","fields":{},"forms":{"reviewForm":{}}}
            /slaBindings/resolution/calendarId  | {"key":"sample-case","orchestrationMode":"BPMN","fields":{},"forms":{},"slaBindings":{"resolution":{"scope":"CASE","duration":"P5D","startAnchor":"CASE_CREATED","meetAnchor":"CASE_CLOSED"}}}
            /adHocActions/0/roles               | {"key":"sample-case","orchestrationMode":"BPMN","fields":{},"forms":{},"adHocActions":[{"id":"a","type":"TASK"}]}
            /adHocActions/0/processDefinitionKey| {"key":"sample-case","orchestrationMode":"BPMN","fields":{},"forms":{},"adHocActions":[{"id":"a","type":"PROCESS","roles":["handler"]}]}
            /adHocActions/0/messageName         | {"key":"sample-case","orchestrationMode":"BPMN","fields":{},"forms":{},"adHocActions":[{"id":"a","type":"MESSAGE","roles":["handler"]}]}
            /mappings/0/target                  | {"key":"sample-case","orchestrationMode":"BPMN","fields":{},"forms":{},"mappings":[{"direction":"ENGINE_TO_CASE","source":"outcome"}]}
            """)
    void reportsTheFullPathOfEveryOmittedRequiredProperty(String path, String json) {
        assertThatThrownBy(() -> validate(json))
                .isInstanceOf(InvalidCaseDefinitionException.class)
                .hasMessageContaining(path);
    }

    // -------------------------------------------------------- closed structures

    /**
     * WS1-AC3 and design §9.9: every behaviour-driving object is closed, so a typo becomes a
     * publication failure at a path instead of a silently ignored property that surfaces when a
     * live case reaches it.
     */
    @ParameterizedTest(name = "[{index}] unknown property at {0}")
    @CsvSource(delimiter = '|', textBlock = """
            /slaBindings/resolution/warnngs | {"key":"sample-case","orchestrationMode":"BPMN","fields":{},"forms":{},"slaBindings":{"resolution":{"scope":"CASE","calendarId":"nl-business","duration":"P5D","startAnchor":"CASE_CREATED","meetAnchor":"CASE_CLOSED","warnngs":["P4D"]}}}
            /searchProfiles/complaints/scopez | {"key":"sample-case","orchestrationMode":"BPMN","fields":{},"forms":{},"searchProfiles":{"complaints":{"scopes":["cases"],"scopez":["documents"]}}}
            /mappings/0/directon | {"key":"sample-case","orchestrationMode":"BPMN","fields":{},"forms":{},"mappings":[{"direction":"ENGINE_TO_CASE","source":"outcome","target":"outcome","directon":"CASE_TO_ENGINE"}]}
            /fields/amount/wrteRoles | {"key":"sample-case","orchestrationMode":"BPMN","fields":{"amount":{"schema":{"type":"integer"},"wrteRoles":["handler"]}},"forms":{}}
            /forms/reviewForm/uiSchma | {"key":"sample-case","orchestrationMode":"BPMN","fields":{},"forms":{"reviewForm":{"schema":{"type":"object"},"uiSchma":{}}}}
            /adHocActions/0/formRefs | {"key":"sample-case","orchestrationMode":"BPMN","fields":{},"forms":{},"adHocActions":[{"id":"a","type":"TASK","roles":["handler"],"formRefs":"reviewForm"}]}
            """)
    void rejectsAnUnknownPropertyInEveryClosedObject(String path, String json) {
        assertThatThrownBy(() -> validate(json))
                .isInstanceOf(InvalidCaseDefinitionException.class)
                .hasMessageContaining(path);
    }

    /**
     * The discriminator closes each variant against the <em>other</em> variants' properties too.
     * A {@code TASK} carrying {@code messageName} is an authoring mistake that would otherwise
     * be dropped on the floor.
     */
    @ParameterizedTest(name = "[{index}] {0} may not carry {1}")
    @CsvSource({
            "TASK,messageName,\"messageName\":\"m\"",
            "TASK,processDefinitionKey,\"processDefinitionKey\":\"p\"",
            "PROCESS,messageName,\"messageName\":\"m\"",
            "MESSAGE,processDefinitionKey,\"processDefinitionKey\":\"p\""
    })
    void rejectsPropertiesBelongingToAnotherAdHocActionVariant(String type, String property,
                                                               String extra) {
        String required = switch (type) {
            case "PROCESS" -> ",\"processDefinitionKey\":\"escalation\"";
            case "MESSAGE" -> ",\"messageName\":\"withdrawn\"";
            default -> "";
        };
        String json = """
                {"key":"sample-case","orchestrationMode":"BPMN","fields":{},"forms":{},
                 "adHocActions":[{"id":"a","type":"%s","roles":["handler"]%s,%s}]}"""
                .formatted(type, required, extra);

        assertThatThrownBy(() -> validate(json))
                .isInstanceOf(InvalidCaseDefinitionException.class)
                .hasMessageContaining("/adHocActions/0")
                .hasMessageContaining(property);
    }

    @Test
    void rejectsAnUnsupportedAdHocActionType() {
        assertThatThrownBy(() -> validate("""
                {"key":"sample-case","orchestrationMode":"BPMN","fields":{},"forms":{},
                 "adHocActions":[{"id":"a","type":"SCRIPT","roles":["handler"]}]}"""))
                .isInstanceOf(InvalidCaseDefinitionException.class)
                .hasMessageContaining("/adHocActions/0/type");
    }

    @Test
    void rejectsDuplicateAdHocActionIds() {
        assertThatThrownBy(() -> validate("""
                {"key":"sample-case","orchestrationMode":"BPMN","fields":{},"forms":{},
                 "adHocActions":[{"id":"a","type":"TASK","roles":["handler"]},
                                 {"id":"a","type":"MESSAGE","roles":["handler"],"messageName":"m"}]}"""))
                .isInstanceOf(InvalidCaseDefinitionException.class)
                .hasMessageContaining("Duplicate ad-hoc action id 'a'");
    }

    @Test
    void rejectsAnUnsupportedSlaScope() {
        assertThatThrownBy(() -> validate("""
                {"key":"sample-case","orchestrationMode":"BPMN","fields":{},"forms":{},
                 "slaBindings":{"resolution":{"scope":"GALAXY","calendarId":"nl-business",
                   "duration":"P5D","startAnchor":"CASE_CREATED","meetAnchor":"CASE_CLOSED"}}}"""))
                .isInstanceOf(InvalidCaseDefinitionException.class)
                .hasMessageContaining("/slaBindings/resolution/scope");
    }

    // ------------------------------------------------------------- mode rules

    /**
     * WS1-AC4. Each construct here decides sequence or task activation. In {@code BPMN} mode the
     * process model already decides them, and two authorities that can disagree is exactly the
     * defect review comment 4 raised — so the diagnostic must say which side wins, not merely
     * that a property is unknown.
     */
    @ParameterizedTest(name = "[{index}] BPMN mode rejects {0}")
    @ValueSource(strings = {
            "\"planItems\":[{\"defKey\":\"intake\",\"type\":\"STAGE\"}]",
            "\"sentries\":[{\"id\":\"s1\",\"onPart\":\"sampleTask\"}]",
            "\"entryCriteria\":[\"${items.intake.state == 'COMPLETED'}\"]",
            "\"exitCriteria\":[\"${case.state == 'CLOSED'}\"]",
            "\"taskActivation\":{\"review\":\"${amount > 100}\"}",
            "\"timers\":[{\"id\":\"t1\",\"after\":\"P3D\"}]",
            "\"lifecycle\":{\"states\":[\"OPEN\",\"CLOSED\"]}"
    })
    void rejectsPlanModelLifecycleConstructsInBpmnMode(String construct) {
        String json = """
                {"key":"sample-case","orchestrationMode":"BPMN","fields":{},"forms":{},%s}"""
                .formatted(construct);
        String property = construct.substring(1, construct.indexOf('"', 1));

        assertThatThrownBy(() -> validate(json))
                .isInstanceOf(InvalidCaseDefinitionException.class)
                .hasMessageContaining("/" + property)
                .hasMessageContaining("BPMN orchestration is authoritative");
    }

    /**
     * WS1-AC5. The legacy shape is deliberately different — forms hold a JSON Schema directly
     * rather than a {@code {schema, uiSchema}} wrapper, there is no field catalogue, and plan
     * items carry the lifecycle. Closing the BPMN contract must not retroactively invalidate it.
     */
    @Test
    void acceptsAnUnchangedLegacyPlanModelContract() {
        ValidatedCaseContract contract = validate("""
                {
                  "key": "sample-case",
                  "name": "Sample Handling",
                  "tenantId": "t1",
                  "slaPolicyId": "sla-sample",
                  "roles": ["owner", "handler"],
                  "attachmentCategories": ["evidence"],
                  "forms": {
                    "sampleForm": {
                      "type": "object",
                      "required": ["channel"],
                      "properties": {"channel": {"type": "string", "ui:widget": "textarea"}}
                    }
                  },
                  "planItems": [
                    {"defKey": "intake", "type": "STAGE", "name": "Intake", "sortOrder": 10},
                    {"defKey": "sampleTask", "type": "HUMAN_TASK", "required": true,
                     "parentStageKey": "intake", "formKey": "sampleForm",
                     "candidateGroups": ["intake"],
                     "entryCriteria": ["${items.intake.state == 'ACTIVE'}"], "sortOrder": 20}
                  ]
                }""");

        assertThat(contract.orchestrationMode()).isEqualTo(OrchestrationMode.PLAN_MODEL);
        assertThat(contract.forms()).containsOnlyKeys("sampleForm");
        assertThat(contract.forms().get("sampleForm").schema())
                .containsEntry("type", "object");
        assertThat(contract.roles()).containsExactlyInAnyOrder("owner", "handler");
    }

    /**
     * An absent mode is the legacy default, and that is the only inference allowed: a mode is
     * never derived from which properties happen to be present. A document that declares
     * {@code BPMN} and then carries plan items fails (above) rather than being re-read as legacy.
     */
    @Test
    void treatsAnUndeclaredModeAsPlanModelRatherThanInferringItFromContent() {
        ValidatedCaseContract contract = validate("""
                {"key":"sample-case","forms":{}}""");

        assertThat(contract.orchestrationMode()).isEqualTo(OrchestrationMode.PLAN_MODEL);
    }

    // ------------------------------------------------------- safety and identity

    @Test
    void rejectsAContractWhoseKeyDiffersFromTheDefinitionKey() {
        assertThatThrownBy(() -> validate("""
                {"key":"other-case","orchestrationMode":"BPMN","fields":{},"forms":{}}"""))
                .isInstanceOf(InvalidCaseDefinitionException.class)
                .hasMessageContaining("sample-case");
    }

    @ParameterizedTest(name = "[{index}] malformed content")
    @ValueSource(strings = {"", "   ", "not json", "[]", "{\"key\":", "null"})
    void rejectsMalformedContentAsAnInvalidDefinitionRatherThanAParserFailure(String json) {
        assertThatThrownBy(() -> validate(json))
                .isInstanceOf(InvalidCaseDefinitionException.class);
    }

    @Test
    void rejectsContentThatIsNotUtf8() {
        byte[] latin1 = {(byte) 0x7B, (byte) 0x22, (byte) 0xFF, (byte) 0x22, (byte) 0x7D};

        assertThatThrownBy(() -> validator.validate(KEY, latin1))
                .isInstanceOf(InvalidCaseDefinitionException.class);
    }

    @ParameterizedTest(name = "[{index}] duplicate JSON key")
    @ValueSource(strings = {
            "{\"key\":\"sample-case\",\"key\":\"other-case\","
                    + "\"orchestrationMode\":\"BPMN\",\"fields\":{},\"forms\":{}}",
            "{\"key\":\"sample-case\",\"orchestrationMode\":\"BPMN\","
                    + "\"fields\":{\"amount\":{\"schema\":{\"type\":\"integer\","
                    + "\"type\":\"string\"}}},\"forms\":{}}"
    })
    void rejectsDuplicateJsonKeysAtRootAndNestedLevels(String json) {
        assertThatThrownBy(() -> validate(json))
                .isInstanceOf(InvalidCaseDefinitionException.class)
                .hasMessageContaining("Contract release is not well-formed JSON")
                .hasMessageNotContaining("other-case");
    }

    @Test
    void rejectsContentAfterTheSingleJsonDocument() {
        String trailingSecret = "customer-secret-after-root";
        String json = """
                {"key":"sample-case","orchestrationMode":"BPMN","fields":{},"forms":{}}
                {"secret":"%s"}
                """.formatted(trailingSecret);

        assertThatThrownBy(() -> validate(json))
                .isInstanceOf(InvalidCaseDefinitionException.class)
                .hasMessageContaining("Contract release is not well-formed JSON")
                .hasMessageNotContaining(trailingSecret);
    }

    @Test
    void rejectsAnSlaBindingWithBothDurationAndDueDateExpression() {
        assertThatThrownBy(() -> validate("""
                {"key":"sample-case","orchestrationMode":"BPMN","fields":{},"forms":{},
                 "slaBindings":{"resolution":{"scope":"CASE","calendarId":"nl-business",
                   "duration":"P5D","dueDateExpression":"${case.targetDate}",
                   "startAnchor":"CASE_CREATED","meetAnchor":"CASE_CLOSED"}}}
                """))
                .isInstanceOf(InvalidCaseDefinitionException.class)
                .hasMessageContaining("/slaBindings/resolution");
    }

    @Test
    void rejectsOversizedContractBeforeParsingIt() {
        byte[] oversized = new byte[JsonSchemaCaseContractValidator.MAX_CONTRACT_BYTES + 1];
        java.util.Arrays.fill(oversized, (byte) ' ');

        assertThatThrownBy(() -> validator.validate(KEY, oversized))
                .isInstanceOf(InvalidCaseDefinitionException.class)
                .hasMessageContaining("exceeds")
                .hasMessageContaining(String.valueOf(JsonSchemaCaseContractValidator.MAX_CONTRACT_BYTES));
    }

    /**
     * WS1-AC8. A diagnostic is read by an author and stored in logs, so it must stay bounded and
     * must not echo submitted content back — a contract can carry customer-shaped defaults.
     */
    @Test
    void boundsDiagnosticsAndDoesNotEchoSubmittedValues() {
        String secret = "x".repeat(5_000);
        StringBuilder actions = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            actions.append(i == 0 ? "" : ",")
                    .append("{\"id\":\"a").append(i).append("\",\"type\":\"TASK\",")
                    .append("\"roles\":[\"handler\"],\"note\":\"").append(secret).append("\"}");
        }
        String json = """
                {"key":"sample-case","orchestrationMode":"BPMN","fields":{},"forms":{},
                 "adHocActions":[%s]}""".formatted(actions);

        assertThatThrownBy(() -> validate(json))
                .isInstanceOf(InvalidCaseDefinitionException.class)
                .satisfies(thrown -> {
                    assertThat(thrown.getMessage()).doesNotContain(secret);
                    assertThat(thrown.getMessage().length()).isLessThan(4_000);
                });
    }

    /** Violations are ordered by path so two runs over the same document read identically. */
    @Test
    void sortsViolationsByPath() {
        String message = catchMessage("""
                {"key":"sample-case","orchestrationMode":"BPMN",
                 "forms":{"reviewForm":{}},
                 "fields":{"amount":{}}}""");

        assertThat(message.indexOf("/fields/amount"))
                .isLessThan(message.indexOf("/forms/reviewForm"));
    }

    /**
     * The schema is executed from the classpath, not read from {@code docs/}: a deployed
     * artifact validates with the schema it shipped with, not with whatever the working tree
     * happens to contain.
     */
    @Test
    void executesTheSchemaPublishedOnTheClasspath() {
        assertThat(getClass().getResourceAsStream("/schemas/case-contract-v1.schema.json"))
                .as("case-contract-v1.schema.json must ship on the core classpath")
                .isNotNull();
    }

    /**
     * {@code docs/schemas} is what model authors and tooling read. It is a copy of the executed
     * schema rather than the source of it, so this asserts the copy has not drifted — a
     * documented rule the runtime does not actually enforce is worse than no rule.
     */
    @Test
    void keepsThePublishedDocumentationCopyIdenticalToTheExecutedSchema() throws Exception {
        Path published = Path.of("..", "docs", "schemas", "case-contract-v1.schema.json");
        assumeTrue(Files.exists(published), "docs/ is not present in this checkout");

        try (var executed = getClass()
                .getResourceAsStream("/schemas/case-contract-v1.schema.json")) {
            assertThat(Files.readString(published, StandardCharsets.UTF_8))
                    .as("docs/schemas/case-contract-v1.schema.json has drifted from the "
                            + "schema on the classpath")
                    .isEqualTo(new String(executed.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    // ------------------------------------------------------------------ helpers

    private ValidatedCaseContract validate(String json) {
        return validator.validate(KEY, json.getBytes(StandardCharsets.UTF_8));
    }

    private String catchMessage(String json) {
        try {
            validate(json);
            throw new AssertionError("Expected " + json + " to be rejected");
        } catch (InvalidCaseDefinitionException expected) {
            return expected.getMessage();
        }
    }

    private static String fullBpmnContract() {
        return """
                {
                  "key": "sample-case",
                  "version": "1.0",
                  "orchestrationMode": "BPMN",
                  "roles": ["handler", "reviewer"],
                  "candidateGroups": ["handlers"],
                  "fields": {
                    "amount": {"schema": {"type": "integer"}, "writeRoles": ["handler"]},
                    "outcome": {"schema": {"type": "string"}, "readRoles": ["reviewer"]}
                  },
                  "forms": {
                    "reviewForm": {
                      "schema": {"type": "object", "properties": {"outcome": {"type": "string"}}},
                      "uiSchema": {"outcome": {"widget": "textarea"}}
                    }
                  },
                  "searchProfiles": {"complaints": {"scopes": ["cases", "documents"]}},
                  "slaBindings": {
                    "resolution": {
                      "scope": "CASE",
                      "calendarId": "nl-business",
                      "duration": "P5D",
                      "startAnchor": "CASE_CREATED",
                      "meetAnchor": "CASE_CLOSED",
                      "warnings": ["P4D"]
                    }
                  },
                  "mappings": [
                    {"direction": "ENGINE_TO_CASE", "source": "outcome", "target": "outcome"}
                  ],
                  "adHocActions": [
                    {"id": "investigate", "type": "TASK", "name": "Investigate aspect",
                     "roles": ["handler"], "formRef": "reviewForm",
                     "candidateGroups": ["handlers"],
                     "availabilityExpression": "${case.state == 'ACTIVE'}"},
                    {"id": "escalate", "type": "PROCESS", "roles": ["handler"],
                     "processDefinitionKey": "escalation"},
                    {"id": "withdraw", "type": "MESSAGE", "roles": ["handler"],
                     "messageName": "complaint-withdrawn"}
                  ]
                }""";
    }
}
