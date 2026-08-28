# Workstream 1: Authority, Vocabulary, and Contracts Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make every published case-definition artifact unambiguous, machine-validatable, and consistent with the BPMN-first authority boundary.

**Architecture:** BPMN-first and legacy `PLAN_MODEL` bundles remain separate orchestration modes with separate validation rules. JSON Schema 2020-12 validates the complete contract shape before Java performs cross-artifact checks. Operaton-owned BPMN extensions use the Operaton namespace; platform metadata uses the case-management namespace.

**Tech Stack:** Java 21, Spring Boot 4.0.7, networknt JSON Schema Validator 1.5.8, DOM namespace-aware XML parsing, JSON Schema 2020-12, JUnit 5, AssertJ, Maven, BPMN modeler element templates.

**Spec:** [`docs/plans/2026-08-27-bpmn-first-production-readiness-design.md`](2026-08-27-bpmn-first-production-readiness-design.md), especially sections 7.1, 9.1, 9.9, 9.10, 10 comments 4/6/9, and acceptance Gate 1.

## Global constraints

- Preserve existing `PLAN_MODEL` behavior and public APIs.
- BPMN owns token flow, gateways, structured task activation, process timers, subprocesses, and compensation in BPMN-first mode.
- The contract owns canonical fields, forms, mappings, search, ad-hoc action declarations, presentation references, and policy references.
- Use `operaton` namespace URI `http://operaton.org/schema/1.0/bpmn` for engine attributes.
- Use `casemgmt` namespace URI `https://casemgmt.org/bpmn` for platform attributes.
- The SLA BPMN property is exactly `casemgmt:slaTargetId`.
- Reject unknown behavior-driving JSON properties unless they are inside a documented extension object.
- Validation errors must identify the artifact and JSON/XML path without returning secrets or full customer payloads.
- Use additive Liquibase changes only; do not edit an applied changeset.

---

## Problem statement

The current contract schema accepts open-ended structures, including `slaBindings`, and publication relies partly on handwritten checks. The BPMN validator finds attributes by local name, so an attribute in the wrong XML namespace can be accepted. Templates, samples, and guides use inconsistent attribute names. Some declarative documentation also implies that the contract controls lifecycle and task activation in BPMN-first mode, creating a second process authority.

If this remains unchanged, an artifact can be published successfully but fail when a live case reaches it. A model can also appear correct in the modeler while the runtime ignores or misinterprets its metadata.

## Scope

### In scope

- Closed JSON Schema definitions for contract fields, forms, mappings, search profiles, SLA bindings, and all ad-hoc action variants.
- An explicit orchestration-mode discriminator and mode-specific semantic validation.
- Schema validation before cross-artifact validation and before any release becomes selectable.
- Namespace-aware BPMN validation.
- One vocabulary across schema, code, template, samples, and guides.
- Characterization tests proving `PLAN_MODEL` still works.

### Out of scope

- Runtime SLA clock behavior; Workstream 6 owns it.
- Runtime ad-hoc execution; Workstream 7 owns it.
- Release activation states and exact Operaton identities; Workstream 2 owns them.
- Presentation redesign; the existing Scenario A decision remains unchanged.

## Technical design

### Contract validation boundary

Create a reusable validator with this interface:

```java
public interface CaseContractValidator {
    ValidatedCaseContract validate(String definitionKey, byte[] utf8Json);
}

public record ValidatedCaseContract(
        String key,
        OrchestrationMode orchestrationMode,
        Map<String, FieldDefinition> fields,
        Map<String, FormDefinition> forms,
        List<SlaBindingDefinition> slaBindings,
        List<AdHocActionDefinition> adHocActions,
        Set<String> candidateGroups) {}
```

`JsonSchemaCaseContractValidator` loads the classpath schema once, validates using the 2020-12 dialect, sorts violations by path, and throws `InvalidCaseDefinitionException` with bounded diagnostics. Java records provide a typed result for cross-reference checks; runtime services should stop repeatedly parsing arbitrary `Map<String,Object>` structures.

Validation order is fixed:

1. Decode UTF-8 and parse JSON.
2. Validate against JSON Schema.
3. Map to `ValidatedCaseContract`.
4. Apply mode-specific semantic rules.
5. Cross-check BPMN form, group, SLA, and action references.
6. Validate presentation references.

### Mode rules

For `BPMN`, reject declarative lifecycle, gateway, task-activation, process-timer, and sentry/criterion structures. For `PLAN_MODEL`, retain the existing plan-model fields and validation. Do not silently infer a mode from the presence of fields for newly published bundles; the combined bundle declares it explicitly. Existing stored definitions continue using their persisted `OrchestrationMode`.

### Namespace rules

Replace local-name lookup in `BpmnReleaseValidator` with namespace-specific access:

```java
private static final String OPERATON_NS = "http://operaton.org/schema/1.0/bpmn";
private static final String CASEMGMT_NS = "https://casemgmt.org/bpmn";

private static String operaton(Element element, String name) {
    return element.hasAttributeNS(OPERATON_NS, name)
            ? element.getAttributeNS(OPERATON_NS, name) : null;
}

private static String caseMgmt(Element element, String name) {
    return element.hasAttributeNS(CASEMGMT_NS, name)
            ? element.getAttributeNS(CASEMGMT_NS, name) : null;
}
```

Read `formKey`, `candidateGroups`, and engine decision properties from `OPERATON_NS`. Read the `stage` marker, `milestoneId`, and `slaTargetId` from `CASEMGMT_NS`. A subprocess remains identified by its BPMN `id`; `casemgmt:stage="true"` marks it for stage projection. Reject a supported local name in an unexpected namespace to give the author a useful error rather than silently ignoring it.

## File map

### Create

- `case-management-core/src/main/java/org/casemgmt/release/CaseContractValidator.java` — validation interface.
- `case-management-core/src/main/java/org/casemgmt/release/JsonSchemaCaseContractValidator.java` — schema execution and typed mapping.
- `case-management-core/src/main/java/org/casemgmt/release/ValidatedCaseContract.java` — typed publication model and sealed ad-hoc variants.
- `case-management-core/src/test/java/org/casemgmt/release/JsonSchemaCaseContractValidatorTest.java` — schema and mapping unit tests.
- `case-management-core/src/test/java/org/casemgmt/release/BpmnVocabularyTest.java` — namespace and vocabulary tests.
- `case-management-poc-app/src/test/java/org/casemgmt/poc/PlanModelCompatibilityIT.java` — legacy characterization coverage.
- `modeler-templates/test/case-management-template.test.mjs` — template/property round-trip test.
- `modeler-templates/package.json` and `modeler-templates/package-lock.json` — isolated modeler test dependencies and command.

### Modify

- `docs/schemas/case-contract-v1.schema.json` — close and type all behavior-driving structures.
- `case-management-core/pom.xml` — ensure the networknt validator is a runtime dependency.
- `case-management-core/src/main/java/org/casemgmt/release/BpmnReleaseValidator.java` — namespace-aware extraction and `slaTargetId` vocabulary.
- `case-management-core/src/main/java/org/casemgmt/service/CaseDefinitionVersionService.java` — consume typed contract and enforce cross-artifact rules.
- `case-management-core/src/main/java/org/casemgmt/service/CombinedCaseDefinitionDeploymentService.java` — validate all artifacts before publication/binding.
- `modeler-templates/case-management.json` — standard Operaton and case-management properties.
- `case-management-poc-app/src/main/resources/definitions/complaint-bpmn-contract.json` — closed-schema compliant sample.
- BPMN sample resources under `case-management-poc-app/src/main/resources` — standard namespaces and property names.
- `docs/bpmn-first-orchestration-proposal.md`, `docs/declarative-case-model-architecture.md`, `docs/guide/concepts.md`, `docs/guide/case-definitions.md`, and `docs/schemas/combined-case-definition-v1.md` — align the authority and vocabulary wording.
- `.github/workflows/ci.yml` — run modeler-template tests in addition to Java and web-component jobs.

## Test strategy

### Unit tests

- Valid minimal BPMN contract maps to `ValidatedCaseContract`.
- Every required field omission returns the precise JSON path.
- Unknown property under SLA, search, mapping, and each action variant is rejected.
- `TASK`, `PROCESS`, and `MESSAGE` discriminate to distinct Java record types.
- BPMN mode rejects plan lifecycle and activation constructs.
- `PLAN_MODEL` accepts the existing legacy fixture.
- XXE/DOCTYPE behavior remains rejected.
- Correct URI with a different prefix is accepted.
- Correct local name with the wrong namespace is rejected.
- Duplicate platform IDs and unresolved references are rejected.

### Integration tests

- Combined ZIP validation fails before any release or binding row is inserted.
- A valid combined ZIP publishes all three artifacts and reaches the release workflow.
- Modeler template JSON loads, applies properties, serializes BPMN, reloads it, and retains exact namespace URIs and values.
- Existing `PLAN_MODEL` create/start/task lifecycle remains unchanged.

### End-to-end tests

- Publish the complaint example, create a case, render the declared form, and verify candidate groups and SLA target references are discoverable.
- Attempt to publish a BPMN-first contract containing a declarative task activation rule; expect a stable 4xx problem response and no new active binding.

## Acceptance scenarios

| ID | Scenario | Expected result |
|---|---|---|
| WS1-AC1 | Publish a valid BPMN-first bundle using `operaton:formKey` and `casemgmt:slaTargetId`. | Publication validation succeeds and extracted indexes contain the form and SLA target. |
| WS1-AC2 | Use `foo:slaTargetId` with an unrecognised URI. | Publication fails with the BPMN element ID and expected namespace. |
| WS1-AC3 | Add a misspelled `warnngs` property to an SLA binding. | Schema validation fails at `/slaBindings/.../warnngs`; no release is active. |
| WS1-AC4 | Put lifecycle/task activation rules in a BPMN contract. | Mode validation rejects them and explains that BPMN is authoritative. |
| WS1-AC5 | Publish an unchanged legacy `PLAN_MODEL` fixture. | It remains valid and its runtime characterization tests pass. |
| WS1-AC6 | Open and save the sample through the modeler test. | Form, candidates, stage, milestone, and SLA metadata survive exactly. |
| WS1-AC7 | Reference an unknown form, candidate group, SLA target, or presentation section. | Binding fails before activation with all deterministic reference errors. |
| WS1-AC8 | Submit malformed/oversized or entity-bearing content. | Validation fails safely without external entity access or unbounded diagnostics. |

## Implementation tasks

### Task 1: Lock the authority and schema behavior with failing tests

**Files:**
- Create: `case-management-core/src/test/java/org/casemgmt/release/JsonSchemaCaseContractValidatorTest.java`
- Modify: `case-management-core/src/test/java/org/casemgmt/service/CaseDefinitionVersionServiceTest.java`

**Interfaces:** Produces the expected `CaseContractValidator.validate` behavior and the exact validation messages later tasks implement.

- [ ] Add parameterized tests for BPMN and `PLAN_MODEL` valid fixtures.
- [ ] Add one failing test for each closed behavior-driving object and each mode violation.
- [ ] Run `./mvnw -pl case-management-core -Dtest=JsonSchemaCaseContractValidatorTest,CaseDefinitionVersionServiceTest test` and confirm the new tests fail because the typed validator does not exist.
- [ ] Commit the test contract with `git commit -m "test: define case contract validation"`.

### Task 2: Implement JSON Schema validation and typed mapping

**Files:**
- Create: `case-management-core/src/main/java/org/casemgmt/release/CaseContractValidator.java`
- Create: `case-management-core/src/main/java/org/casemgmt/release/JsonSchemaCaseContractValidator.java`
- Create: `case-management-core/src/main/java/org/casemgmt/release/ValidatedCaseContract.java`
- Modify: `docs/schemas/case-contract-v1.schema.json`
- Modify: `case-management-core/pom.xml`

**Interfaces:** Implements `ValidatedCaseContract validate(String definitionKey, byte[] utf8Json)`; produces typed fields used by binding and runtime services.

- [ ] Define sealed `AdHocActionDefinition` variants and immutable records for fields, forms, mappings, search profiles, and SLA binding references.
- [ ] Close the JSON Schema with exact required fields, enums, formats, bounds, and discriminated action variants.
- [ ] Load and compile the schema once and return sorted, bounded errors.
- [ ] Run the Task 1 tests and correct only implementation defects until they pass.
- [ ] Run `./mvnw -pl case-management-core -Dtest=JsonSchemaCaseContractValidatorTest test` and expect all cases to pass.
- [ ] Commit with `git commit -m "feat: validate typed case contracts"`.

### Task 3: Make BPMN vocabulary namespace-aware

**Files:**
- Create: `case-management-core/src/test/java/org/casemgmt/release/BpmnVocabularyTest.java`
- Modify: `case-management-core/src/main/java/org/casemgmt/release/BpmnReleaseValidator.java`

**Interfaces:** `BpmnReleaseValidator.Index.slaRefs()` contains values from `casemgmt:slaTargetId` only.

- [ ] Write tests for correct URI/different prefix, wrong URI/same local name, legacy `slaRef`, and correct Operaton task properties.
- [ ] Run `./mvnw -pl case-management-core -Dtest=BpmnVocabularyTest test` and confirm wrong namespaces are currently accepted or the new property is missed.
- [ ] Replace local-name scans with URI-specific accessors and actionable wrong-namespace detection.
- [ ] Run `./mvnw -pl case-management-core -Dtest=BpmnVocabularyTest test` and expect pass.
- [ ] Commit with `git commit -m "fix: enforce BPMN extension namespaces"`.

### Task 4: Integrate validation into publication and binding

**Files:**
- Modify: `case-management-core/src/main/java/org/casemgmt/service/CombinedCaseDefinitionDeploymentService.java`
- Modify: `case-management-core/src/main/java/org/casemgmt/service/CaseDefinitionVersionService.java`
- Modify: `case-management-core/src/test/java/org/casemgmt/service/CaseDefinitionVersionServiceTest.java`
- Modify: `case-management-core/src/test/java/org/casemgmt/release/CombinedCaseDefinitionArchiveTest.java`

**Interfaces:** Publication receives a `ValidatedCaseContract`; binding consumes it for cross-references and never reparses arbitrary action/SLA maps.

- [ ] Write a transaction test proving invalid combined content inserts no release or binding rows.
- [ ] Inject the contract validator and execute it before release publication.
- [ ] Replace manual contract map checks with typed cross-reference validation.
- [ ] Run `./mvnw -pl case-management-core test` and expect pass.
- [ ] Commit with `git commit -m "feat: gate releases on contract validation"`.

### Task 5: Align templates, samples, and documentation

**Files:**
- Modify: `modeler-templates/case-management.json`
- Create: `modeler-templates/package.json`
- Create: `modeler-templates/test/case-management-template.test.mjs`
- Modify: example BPMN/JSON files and architecture/guide documents listed in the file map.

**Interfaces:** `npm test` under `modeler-templates` verifies the published modeling vocabulary.

- [ ] Add the modeler test dependency and a locked package file.
- [ ] Write a round-trip test covering form, groups, stage, milestone, and SLA target properties.
- [ ] Update the template and samples until the test passes.
- [ ] Update all documentation authority statements and vocabulary examples.
- [ ] Run `npm ci && npm test` from `modeler-templates` and `./mvnw -pl case-management-poc-app test`.
- [ ] Commit with `git commit -m "docs: align BPMN modeling contract"`.

### Task 6: Add compatibility and CI gates

**Files:**
- Create: `case-management-poc-app/src/test/java/org/casemgmt/poc/PlanModelCompatibilityIT.java`
- Modify: `.github/workflows/ci.yml`

**Interfaces:** CI exposes a required model-contract check and preserves the full Java reactor check.

- [ ] Add legacy definition, case start, plan item, task completion, and case completion characterization assertions.
- [ ] Add a modeler-template CI job using the repository's selected Node version.
- [ ] Run `./mvnw clean install`, `npm ci && npm test` in `modeler-templates`, and existing web-component tests.
- [ ] Commit with `git commit -m "ci: verify modeling contracts"`.

## Completion evidence

- Attach the schema-validation, modeler round-trip, full reactor, and legacy compatibility reports.
- Show one rejected wrong-namespace artifact and one rejected unknown-property artifact.
- Show a successfully published BPMN-first example and unchanged `PLAN_MODEL` example.
- Link the aligned schema, template, guide, and validator lines when resolving review comments 4, 6, and 9.
