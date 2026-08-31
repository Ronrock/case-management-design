package org.casemgmt.release;

import org.casemgmt.error.InvalidCaseDefinitionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The BPMN extension vocabulary contract for {@link BpmnReleaseValidator} (Workstream 1, Task 3).
 *
 * <p>Two defects motivate this suite. Both are invisible in a modeler and only surface when a
 * live case reaches the element:
 *
 * <ol>
 *   <li><b>Namespace was not checked.</b> Extraction matched attributes by local name, so
 *       {@code foo:formKey} in any namespace at all was indexed as if the author had written
 *       {@code operaton:formKey}. A typo in a namespace prefix therefore published cleanly and
 *       changed meaning at runtime. Namespace URI — not prefix, and not local name alone — is
 *       what identifies a property (design §9.1, review comment 9).</li>
 *   <li><b>The SLA property had two spellings.</b> The index was populated from a legacy
 *       {@code slaRef}, while the contract schema, guides and templates resolve
 *       {@code casemgmt:slaTargetId}. One vocabulary, spelled the same everywhere, is the
 *       point of this workstream.</li>
 * </ol>
 *
 * <p>The rule these tests fix is that a <em>recognised local name in an unexpected namespace is
 * an error, not a silent miss</em>. Silently ignoring it is what let a wrong-prefix model reach
 * production; the author gets the element id and the namespace that was expected instead
 * (WS1-AC2).
 *
 * <p>Prefixes are deliberately varied across these cases. A prefix is arbitrary in XML, so
 * binding {@code https://casemgmt.org/bpmn} to {@code cm} must behave exactly like binding it to
 * {@code casemgmt} — asserting that is what proves the lookup is URI-based.
 */
class BpmnVocabularyTest {

    private static final String KEY = "vocabulary-case";
    private static final String OPERATON_NS = "http://operaton.org/schema/1.0/bpmn";
    private static final String CASEMGMT_NS = "https://casemgmt.org/bpmn";

    // ------------------------------------------------------- correct vocabulary

    @Test
    void readsOperatonTaskPropertiesUnderTheStandardPrefix() {
        var index = validate("""
                xmlns:operaton="%s\"""".formatted(OPERATON_NS), """
                <bpmn:userTask id="review" operaton:formKey="reviewForm"
                               operaton:candidateGroups="reviewers,handlers"/>""");

        assertThat(index.formRefs()).containsExactly("reviewForm");
        assertThat(index.candidateGroups()).containsExactlyInAnyOrder("reviewers", "handlers");
    }

    /** The URI identifies the property; the prefix bound to it is the author's choice. */
    @Test
    void readsOperatonTaskPropertiesUnderADifferentPrefix() {
        var index = validate("""
                xmlns:op="%s\"""".formatted(OPERATON_NS), """
                <bpmn:userTask id="review" op:formKey="reviewForm" op:candidateGroups="reviewers"/>""");

        assertThat(index.formRefs()).containsExactly("reviewForm");
        assertThat(index.candidateGroups()).containsExactly("reviewers");
    }

    @Test
    void readsCaseManagementPropertiesUnderADifferentPrefix() {
        var index = validate("""
                xmlns:cm="%s\"""".formatted(CASEMGMT_NS), """
                <bpmn:intermediateThrowEvent id="acknowledged" cm:milestoneId="acknowledged"/>
                <bpmn:userTask id="review" cm:slaTargetId="review-sla"/>""");

        assertThat(index.milestoneIds()).containsExactly("acknowledged");
        assertThat(index.slaRefs()).containsExactly(new BpmnReleaseValidator.SlaReference(
                "review-sla", "review", BpmnReleaseValidator.ElementKind.TASK));
    }

    @Test
    void rejectsStageMarkerInTheWrongNamespace() {
        assertThatThrownBy(() -> validate("""
                xmlns:foo="https://example.invalid/bpmn\"""", """
                <bpmn:subProcess id="intake" foo:stage="true"/>"""))
                .isInstanceOf(InvalidCaseDefinitionException.class)
                .hasMessageContaining("BPMN element 'intake'")
                .hasMessageContaining("'stage'")
                .hasMessageContaining(CASEMGMT_NS);
    }

    /**
     * The Task 3 interface contract: {@code slaRefs()} carries {@code casemgmt:slaTargetId}
     * values and nothing else, so binding cross-references one spelling.
     */
    @Test
    void indexesSlaTargetIdIntoSlaRefs() {
        var index = validate("xmlns:casemgmt=\"" + CASEMGMT_NS + "\"", """
                <bpmn:userTask id="first" casemgmt:slaTargetId="first-sla"/>
                <bpmn:userTask id="second" casemgmt:slaTargetId="second-sla"/>""");

        assertThat(index.slaRefs()).containsExactlyInAnyOrder(
                new BpmnReleaseValidator.SlaReference(
                        "first-sla", "first", BpmnReleaseValidator.ElementKind.TASK),
                new BpmnReleaseValidator.SlaReference(
                        "second-sla", "second", BpmnReleaseValidator.ElementKind.TASK));
    }

    @Test
    void indexesEveryRuntimeObservableSlaElementKind() {
        var index = validate("""
                xmlns:casemgmt="%s\"""".formatted(CASEMGMT_NS), """
                <bpmn:userTask id="review" casemgmt:slaTargetId="task-sla"/>
                <bpmn:subProcess id="assessment" casemgmt:stage="true"
                                 casemgmt:slaTargetId="stage-sla"/>
                <bpmn:intermediateThrowEvent id="accepted" casemgmt:milestoneId="accepted"
                                             casemgmt:slaTargetId="milestone-sla"/>""");

        assertThat(index.slaRefs()).containsExactlyInAnyOrder(
                new BpmnReleaseValidator.SlaReference(
                        "task-sla", "review", BpmnReleaseValidator.ElementKind.TASK),
                new BpmnReleaseValidator.SlaReference(
                        "stage-sla", "assessment", BpmnReleaseValidator.ElementKind.STAGE),
                new BpmnReleaseValidator.SlaReference(
                        "milestone-sla", "accepted", BpmnReleaseValidator.ElementKind.MILESTONE));
    }

    @Test
    void rejectsSlaTargetIdOnAnElementWithoutRuntimeLifecycleObservations() {
        assertThatThrownBy(() -> validate("xmlns:casemgmt=\"" + CASEMGMT_NS + "\"", """
                <bpmn:serviceTask id="automate" casemgmt:slaTargetId="automation-sla"/>"""))
                .isInstanceOf(InvalidCaseDefinitionException.class)
                .hasMessageContaining("automate")
                .hasMessageContaining("slaTargetId")
                .hasMessageContaining("runtime lifecycle observation");
    }

    /** Plain BPMN attributes carry no namespace and must stay unaffected by the new rule. */
    @Test
    void leavesUnprefixedBpmnAttributesAlone() {
        var index = validate("", """
                <bpmn:userTask id="review" name="Review"/>""");

        assertThat(index.processIds()).containsExactly(KEY);
        assertThat(index.formRefs()).isEmpty();
    }

    // ------------------------------------------------------ wrong namespace

    /**
     * WS1-AC2. The diagnostic must name the element and the namespace the author should have
     * used — "nothing happened at runtime" is not a diagnosis a modeler can act on.
     */
    @Test
    void rejectsSlaTargetIdInAnUnrecognisedNamespace() {
        assertThatThrownBy(() -> validate("""
                xmlns:foo="https://example.org/not-ours\"""", """
                <bpmn:userTask id="review" foo:slaTargetId="review-sla"/>"""))
                .isInstanceOf(InvalidCaseDefinitionException.class)
                .hasMessageContaining("review")
                .hasMessageContaining("slaTargetId")
                .hasMessageContaining(CASEMGMT_NS);
    }

    @ParameterizedTest(name = "{0} in a foreign namespace is rejected")
    @CsvSource({
            "formKey,          reviewForm,   " + OPERATON_NS,
            "candidateGroups,  reviewers,    " + OPERATON_NS,
            "milestoneId,      acknowledged, " + CASEMGMT_NS,
            "slaTargetId,      review-sla,   " + CASEMGMT_NS
    })
    void rejectsARecognisedLocalNameInAForeignNamespace(String localName, String value,
                                                        String expectedNamespace) {
        assertThatThrownBy(() -> validate("""
                xmlns:foo="https://example.org/not-ours\"""", """
                <bpmn:userTask id="review" foo:%s="%s"/>""".formatted(localName, value)))
                .isInstanceOf(InvalidCaseDefinitionException.class)
                .hasMessageContaining("review")
                .hasMessageContaining(localName)
                .hasMessageContaining(expectedNamespace);
    }

    /**
     * An unprefixed platform property is the most common authoring slip — hand-edited XML where
     * the prefix was dropped. It is in no namespace, which is not the expected one.
     */
    @Test
    void rejectsAPlatformPropertyWithNoNamespace() {
        assertThatThrownBy(() -> validate("", """
                <bpmn:userTask id="review" formKey="reviewForm"/>"""))
                .isInstanceOf(InvalidCaseDefinitionException.class)
                .hasMessageContaining("review")
                .hasMessageContaining("formKey")
                .hasMessageContaining(OPERATON_NS);
    }

    // ------------------------------------------------------- legacy spelling

    /**
     * {@code slaRef} was the old index key. It must not resolve silently: an author who kept it
     * would get an SLA that never binds, which is exactly the publish-clean/fail-live failure
     * this workstream removes.
     */
    @Test
    void rejectsTheLegacySlaRefAttribute() {
        assertThatThrownBy(() -> validate("""
                xmlns:casemgmt="%s\"""".formatted(CASEMGMT_NS), """
                <bpmn:userTask id="review" casemgmt:slaRef="review-sla"/>"""))
                .isInstanceOf(InvalidCaseDefinitionException.class)
                .hasMessageContaining("review")
                .hasMessageContaining("slaRef")
                .hasMessageContaining("slaTargetId");
    }

    // ---------------------------------------------------------------- helper

    private static BpmnReleaseValidator.Index validate(String namespaceDeclarations, String body) {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                  %s
                                  id="defs" targetNamespace="https://casemgmt.org/test">
                  <bpmn:process id="%s" name="Vocabulary" isExecutable="true">
                    <bpmn:startEvent id="start"/>
                    %s
                    <bpmn:endEvent id="end"/>
                  </bpmn:process>
                </bpmn:definitions>
                """.formatted(namespaceDeclarations, KEY, body);
        return BpmnReleaseValidator.validate(KEY, xml.getBytes(StandardCharsets.UTF_8),
                "application/bpmn+xml");
    }
}
