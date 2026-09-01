package org.casemgmt.release;

import org.casemgmt.orchestration.OrchestrationMode;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A contract release that has passed schema and mode validation, in the shape callers actually
 * need.
 *
 * <p>Everything here is immutable and already resolved: an ad-hoc action is a
 * {@link ProcessAction} or {@link MessageAction} rather than a map whose
 * {@code type} string every caller has to re-switch on, and an SLA binding carries the id it was
 * keyed under so a cross-reference check needs no second pass over the raw document.
 *
 * <p>Declaration order is preserved throughout. A validation diagnostic that lists actions or
 * fields should read in the order the author wrote them.
 */
public record ValidatedCaseContract(
        String key,
        OrchestrationMode orchestrationMode,
        Map<String, FieldDefinition> fields,
        Map<String, FormDefinition> forms,
        List<MappingDefinition> mappings,
        List<SlaBindingDefinition> slaBindings,
        List<AdHocActionDefinition> adHocActions,
        Set<String> candidateGroups,
        Set<String> roles,
        Set<String> searchProfileIds) {

    public ValidatedCaseContract {
        fields = immutable(fields);
        forms = immutable(forms);
        mappings = List.copyOf(mappings);
        slaBindings = List.copyOf(slaBindings);
        adHocActions = List.copyOf(adHocActions);
        candidateGroups = immutable(candidateGroups);
        roles = immutable(roles);
        searchProfileIds = immutable(searchProfileIds);
    }

    /** Source-compatible constructor for callers compiled against the pre-mapping typed shape. */
    public ValidatedCaseContract(
            String key,
            OrchestrationMode orchestrationMode,
            Map<String, FieldDefinition> fields,
            Map<String, FormDefinition> forms,
            List<SlaBindingDefinition> slaBindings,
            List<AdHocActionDefinition> adHocActions,
            Set<String> candidateGroups,
            Set<String> roles,
            Set<String> searchProfileIds) {
        this(key, orchestrationMode, fields, forms, List.of(), slaBindings, adHocActions,
                candidateGroups, roles, searchProfileIds);
    }

    /** The ad-hoc action with {@code id}, or {@code null} if the contract declares none. */
    public AdHocActionDefinition action(String id) {
        return adHocActions.stream().filter(action -> action.id().equals(id)).findFirst()
                .orElse(null);
    }

    /** The SLA target ids a BPMN {@code casemgmt:slaTargetId} attribute may resolve against. */
    public Set<String> slaTargetIds() {
        Set<String> ids = new LinkedHashSet<>();
        slaBindings.forEach(binding -> ids.add(binding.id()));
        return Collections.unmodifiableSet(ids);
    }

    /** What a business deadline is measured against. */
    public enum SlaScope { CASE, STAGE, TASK, MILESTONE, OCCURRENCE }

    /** The lifecycle event families the engine observation boundary can emit. */
    public enum SlaAnchorFamily { CASE, TASK, STAGE, MILESTONE }

    /** One supported lifecycle event that may drive an SLA transition. */
    public enum SlaAnchor {
        CASE_CREATED(SlaAnchorFamily.CASE),
        CASE_CLOSED(SlaAnchorFamily.CASE),
        CASE_CANCELLED(SlaAnchorFamily.CASE),
        USER_TASK_CREATED(SlaAnchorFamily.TASK),
        USER_TASK_COMPLETED(SlaAnchorFamily.TASK),
        USER_TASK_DELETED(SlaAnchorFamily.TASK),
        ACTIVITY_STARTED(SlaAnchorFamily.STAGE),
        ACTIVITY_COMPLETED(SlaAnchorFamily.STAGE),
        ACTIVITY_CANCELLED(SlaAnchorFamily.STAGE),
        MILESTONE_REACHED(SlaAnchorFamily.MILESTONE),
        MILESTONE_CANCELLED(SlaAnchorFamily.MILESTONE);

        private final SlaAnchorFamily family;

        SlaAnchor(SlaAnchorFamily family) {
            this.family = family;
        }

        public SlaAnchorFamily family() {
            return family;
        }
    }

    /** The complete set of breach side effects implemented by the SLA sweeper. */
    public enum SlaBreachAction { EMIT_EVENT, ESCALATE }

    /** Which side supplies and receives a declared canonical mapping. */
    public enum MappingDirection { CASE_TO_ENGINE, ENGINE_TO_CASE }

    /** JSON value kind declared by a mapping. */
    public enum MappingType { STRING, INTEGER, NUMBER, BOOLEAN, OBJECT, ARRAY }

    /** How an engine output is combined with an existing canonical field. */
    public enum MappingWriteMode { REPLACE, MERGE }

    /** One explicit contract-approved movement between canonical data and engine variables. */
    public record MappingDefinition(
            MappingDirection direction,
            String source,
            String target,
            MappingType type,
            MappingWriteMode writeMode,
            boolean required,
            String transformRef,
            List<String> submitRoles,
            Map<String, Object> extensions) {
        public MappingDefinition {
            submitRoles = List.copyOf(submitRoles);
            extensions = immutable(extensions);
        }
    }

    /**
     * A canonical case field. {@code schema} is the author's JSON Schema, kept as data rather
     * than compiled here: the field catalogue is published metadata, and compiling it is the
     * concern of whoever validates a submission against it.
     */
    public record FieldDefinition(String id, Map<String, Object> schema,
                                  List<String> readRoles, List<String> writeRoles) {
        public FieldDefinition {
            schema = immutable(schema);
            readRoles = List.copyOf(readRoles);
            writeRoles = List.copyOf(writeRoles);
        }
    }

    /**
     * A form. In {@code BPMN} mode the author writes {@code {schema, uiSchema}}; in
     * The form value is supplied as a JSON Schema and lands here with
     * {@code schema} holding the schema, so callers need not know which mode produced it.
     */
    public record FormDefinition(String id, Map<String, Object> schema,
                                 Map<String, Object> uiSchema) {
        public FormDefinition {
            schema = immutable(schema);
            uiSchema = immutable(uiSchema);
        }
    }

    /**
     * One SLA target, keyed by the id BPMN references. Runtime clock behaviour — calendars,
     * pause and resume, breach actions — is Workstream 6's; what is fixed here is that a
     * published target names its scope, calendar, anchors and a duration or due-date rule, so a
     * half-specified target cannot reach a running case.
     */
    public record SlaBindingDefinition(String id, SlaScope scope, String calendarId,
                                       String duration, String dueDateExpression,
                                       SlaAnchor startAnchor, SlaAnchor meetAnchor,
                                       SlaAnchor cancelAnchor,
                                       List<String> warnings, Integer targetVersion,
                                       String occurrenceKey, Integer calendarRevision,
                                       List<SlaAnchor> pauseAnchors, List<SlaAnchor> resumeAnchors,
                                       List<SlaBreachAction> breachActions) {
        public SlaBindingDefinition {
            warnings = List.copyOf(warnings);
            pauseAnchors = List.copyOf(pauseAnchors);
            resumeAnchors = List.copyOf(resumeAnchors);
            breachActions = List.copyOf(breachActions);
        }

        /** Compatibility constructor for the original published binding shape. */
        public SlaBindingDefinition(String id, SlaScope scope, String calendarId,
                                    String duration, String dueDateExpression,
                                    String startAnchor, String meetAnchor, String cancelAnchor,
                                    List<String> warnings) {
            this(id, scope, calendarId, duration, dueDateExpression, anchor(startAnchor),
                    anchor(meetAnchor), anchor(cancelAnchor), warnings, 1, null, 1,
                    List.of(), List.of(), List.of());
        }

        private static SlaAnchor anchor(String value) {
            return value == null ? null : SlaAnchor.valueOf(value);
        }
    }

    /**
     * Discretionary work declared outside the BPMN token flow.
     *
     * <p>Sealed, so adding a variant forces every caller that switches on one to be revisited.
     * That is the point: an unhandled variant should be a compile error, not an action that
     * silently does nothing at runtime.
     */
    public sealed interface AdHocActionDefinition {
        String id();
        String name();
        List<String> roles();
        String formRef();
        List<String> candidateGroups();
        String availabilityExpression();
        /** Explicitly declared data movement for this action; no submitted field is implicit. */
        default List<MappingDefinition> mappings() { return List.of(); }
    }

    /** Starts an explicitly permitted related process. */
    public record ProcessAction(String id, String name, List<String> roles, String formRef,
                                List<String> candidateGroups, String availabilityExpression,
                                String processDefinitionKey, String orchestrationReleaseId,
                                List<MappingDefinition> mappings)
            implements AdHocActionDefinition {
        public ProcessAction {
            roles = List.copyOf(roles);
            candidateGroups = List.copyOf(candidateGroups);
            mappings = List.copyOf(mappings);
        }
        public ProcessAction(String id, String name, List<String> roles, String formRef,
                             List<String> candidateGroups, String availabilityExpression,
                             String processDefinitionKey) {
            this(id, name, roles, formRef, candidateGroups, availabilityExpression,
                    processDefinitionKey, null, List.of());
        }
    }

    /** Correlates a declared message through the orchestration. */
    public record MessageAction(String id, String name, List<String> roles, String formRef,
                                List<String> candidateGroups, String availabilityExpression,
                                String messageName, List<String> correlationKeys,
                                List<MappingDefinition> mappings)
            implements AdHocActionDefinition {
        public MessageAction {
            roles = List.copyOf(roles);
            candidateGroups = List.copyOf(candidateGroups);
            correlationKeys = List.copyOf(correlationKeys);
            mappings = List.copyOf(mappings);
        }
        public MessageAction(String id, String name, List<String> roles, String formRef,
                             List<String> candidateGroups, String availabilityExpression,
                             String messageName) {
            this(id, name, roles, formRef, candidateGroups, availabilityExpression,
                    messageName, List.of(), List.of());
        }
    }

    /**
     * Copies preserving iteration order. {@code Map.copyOf}/{@code Set.copyOf} are not used for
     * the author-supplied structures: they do not preserve order, and a JSON Schema fragment may
     * legitimately contain a null value that they reject.
     */
    private static <K, V> Map<K, V> immutable(Map<K, V> value) {
        return value == null || value.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(value));
    }

    private static <T> Set<T> immutable(Set<T> value) {
        return value == null || value.isEmpty()
                ? Set.of()
                : Collections.unmodifiableSet(new LinkedHashSet<>(value));
    }
}
