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
 * {@link TaskAction}, {@link ProcessAction} or {@link MessageAction} rather than a map whose
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
        List<SlaBindingDefinition> slaBindings,
        List<AdHocActionDefinition> adHocActions,
        Set<String> candidateGroups,
        Set<String> roles,
        Set<String> searchProfileIds) {

    public ValidatedCaseContract {
        fields = immutable(fields);
        forms = immutable(forms);
        slaBindings = List.copyOf(slaBindings);
        adHocActions = List.copyOf(adHocActions);
        candidateGroups = immutable(candidateGroups);
        roles = immutable(roles);
        searchProfileIds = immutable(searchProfileIds);
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
     * {@code PLAN_MODEL} the form value <em>is</em> the JSON Schema. Both land here with
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
                                       String startAnchor, String meetAnchor, String cancelAnchor,
                                       List<String> warnings) {
        public SlaBindingDefinition {
            warnings = List.copyOf(warnings);
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
    }

    /** Creates a discretionary task. */
    public record TaskAction(String id, String name, List<String> roles, String formRef,
                             List<String> candidateGroups, String availabilityExpression)
            implements AdHocActionDefinition {
        public TaskAction {
            roles = List.copyOf(roles);
            candidateGroups = List.copyOf(candidateGroups);
        }
    }

    /** Starts an explicitly permitted related process. */
    public record ProcessAction(String id, String name, List<String> roles, String formRef,
                                List<String> candidateGroups, String availabilityExpression,
                                String processDefinitionKey)
            implements AdHocActionDefinition {
        public ProcessAction {
            roles = List.copyOf(roles);
            candidateGroups = List.copyOf(candidateGroups);
        }
    }

    /** Correlates a declared message through the orchestration. */
    public record MessageAction(String id, String name, List<String> roles, String formRef,
                                List<String> candidateGroups, String availabilityExpression,
                                String messageName)
            implements AdHocActionDefinition {
        public MessageAction {
            roles = List.copyOf(roles);
            candidateGroups = List.copyOf(candidateGroups);
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
