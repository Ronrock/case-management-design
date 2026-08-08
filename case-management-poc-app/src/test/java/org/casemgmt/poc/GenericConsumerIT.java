package org.casemgmt.poc;

import org.casemgmt.poc.support.PocAppEmbeddedTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O3: proves the API is model-driven, not case-type-aware, by driving a whole case to closure
 * with <b>zero case-type constants</b> — no {@code complaint}, no plan-item {@code defKey}
 * ({@code registerComplaint}, {@code assessComplaint}, ...), no form field name
 * ({@code channel}, {@code summary}, {@code outcome}, ...) anywhere in this file's control flow.
 *
 * <p>Everything this consumer does, it learns from three sources, all of them generic
 * vocabulary any case type exposes the same way:
 * <ol>
 *   <li>{@code GET /case-definitions} — which case type(s) exist at all, and
 *       {@code GET /case-definitions/{key}} for each plan item's generic metadata
 *       ({@code type}, {@code required}, {@code manualActivation}, {@code formKey}) — see
 *       {@code CaseDefinitionController.get}'s own Javadoc: "what makes a generic consumer
 *       possible without case-type constants anywhere in this module";</li>
 *   <li>{@code availableActions[]} on every case/task/plan-item/milestone response — each entry
 *       already carries its own {@code href} and {@code method} (spec §8 obligation 2: "no
 *       second call to discover how"), so this consumer never builds a URL itself beyond
 *       resolving {@code href} against the API's own base path (the one piece of configuration
 *       it needs — exactly {@code openapi-specs.md}'s {@code servers: [{url: /case-api/v2}]},
 *       not a case-type fact);</li>
 *   <li>the JSON Schema (draft 2020-12) behind a task's {@code formKey}, fetched from
 *       {@code GET /case-definitions/{key}/forms/{formKey}} and satisfied generically by
 *       {@link #synthesize} — the first enum value for an enum, the schema's {@code minimum}
 *       (or 0) for a number, a placeholder string otherwise. It never reads a property's NAME,
 *       only its JSON Schema SHAPE.</li>
 * </ol>
 *
 * <p><b>Why this never touches the discretionary {@code investigation} branch, without
 * knowing its name — corrected (Fix round 1, review Important 2).</b> An earlier draft of this
 * class computed a {@code manualActivation} lookup INSIDE the driving loop, but it sat behind a
 * {@code STAGE}/{@code HUMAN_TASK} type guard that already excludes every item this case type
 * ever marks discretionary ({@code investigation} is a {@code STAGE}, {@code investigateAspect}
 * a {@code HUMAN_TASK}) — the lookup was dead code, reachable but never able to change the
 * outcome, exactly the "looks protective, isn't" shape flagged in review. Removed. What actually
 * keeps this consumer off the discretionary branch, for this case type, is simpler and fully
 * generic: it never calls {@code enable} or {@code start} on anything, at all — only {@code
 * claim}/{@code complete} on what a worklist task or an {@code availableActions[]} entry already
 * offers it. Since a discretionary item requires an explicit {@code start} to ever leave {@code
 * ENABLED} (see the note on {@code targetOnEntry} below), never calling {@code start} is
 * sufficient on its own.
 *
 * <p>The {@code manualActivation} flag still earns a real, load-bearing place in this file: the
 * assertion at the end reads it — generically, via {@code GET /case-definitions/{key}}, never a
 * hardcoded name — to independently verify that whatever the DEFINITION marks discretionary was
 * in fact never started.
 *
 * <p><b>How that assertion was proved non-vacuous (restated accurately in Task 27; the earlier
 * wording described a run that did not happen as written).</b> A {@code POST .../enable} strip was
 * tried FIRST and failed for the WRONG reason — {@code investigation} declares no entry criteria,
 * so it is already {@code ENABLED} the instant the case exists and {@code enable} is not a legal
 * transition from there. That attempt proved nothing and was discarded. The strip that counts
 * replaced it: a raw forced {@code POST .../start} on the {@code investigation} plan item,
 * inserted right after case creation and bypassing the consumer's own logic. It made the assertion
 * fail with the item's real observed state, {@code COMPLETED} (started, then completed by the
 * stage/close machinery), against the expected {@code AVAILABLE}/{@code ENABLED} — the right
 * failure for the right reason. The strip was then removed and the file confirmed byte-identical.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
class GenericConsumerIT extends PocAppEmbeddedTestBase {

    private static final int MAX_ROUNDS = 25;

    @Test
    void aGenericConsumerDrivesAWholeCaseToClosureWithNoCaseTypeKnowledge() {
        RestClient http = client("alice");

        // ---- discover which case type exists, and what its plan model says (generically) ----
        List<Map<String, Object>> definitions = list(http, "/case-definitions?tenantId=t1");
        assertThat(definitions).as("exactly one case type is deployed in this PoC").hasSize(1);
        String definitionKey = (String) definitions.get(0).get("key");

        Map<String, Object> definitionDetail = map(http, "/case-definitions/" + definitionKey);
        List<Map<String, Object>> planItemDefs =
                (List<Map<String, Object>>) definitionDetail.get("planItems");
        // planItemDefs' "defKey" is the value a live PlanItem's own "name" field actually holds
        // (PlanModelInstantiator sets it that way — a real quirk of this API, not an assumption
        // invented here), which is why the final assertion below correlates on defKey rather
        // than the definition's separate, human-readable display "name".

        // ---- create a case of whatever type was discovered — no literal type name here ----
        Map<String, Object> created = (Map<String, Object>) http.post().uri(resolve("/cases"))
                .header("Idempotency-Key", "generic-consumer-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("caseDefinitionKey", definitionKey, "tenantId", "t1",
                        "title", "Generic consumer probe"))
                .retrieve().toEntity(Map.class).getBody();
        String caseId = (String) created.get("id");

        // ---- drive the case using nothing but availableActions[] and form schemas ----
        boolean closed = false;
        // Captured the instant "close" becomes available — i.e. the state this consumer's own
        // actions actually produced, before CaseService.close's own sweep terminates whatever
        // was still open (spec: closing sweeps leftover plan items, including untouched
        // discretionary ones — a real state change this consumer didn't cause and must not be
        // blamed for when proving it never called "enable" on a discretionary item).
        List<Map<String, Object>> itemsAtCloseTime = null;
        for (int round = 0; round < MAX_ROUNDS && !closed; round++) {
            Map<String, Object> caseNow = map(http, "/cases/" + caseId);
            if (hasAction(caseNow, "close")) {
                itemsAtCloseTime = list(http, "/cases/" + caseId + "/plan-items");
                invoke(http, action(caseNow, "close"), null);
                closed = true;
                break;
            }

            boolean progressed = false;

            List<Map<String, Object>> tasks = list(http, "/cases/" + caseId + "/tasks");
            for (Map<String, Object> task : tasks) {
                if (hasAction(task, "claim")) {
                    invoke(http, action(task, "claim"), null);
                    progressed = true;
                }
            }

            tasks = list(http, "/cases/" + caseId + "/tasks");
            for (Map<String, Object> task : tasks) {
                if (hasAction(task, "complete")) {
                    Object formKey = task.get("formKey");
                    Map<String, Object> variables = formKey == null
                            ? Map.of()
                            : synthesize(map(http, "/case-definitions/" + definitionKey
                                    + "/forms/" + formKey));
                    invoke(http, action(task, "complete"), Map.of("variables", variables));
                    progressed = true;
                }
            }

            // A plan item this consumer completes DIRECTLY, rather than through a worklist
            // task — generically, by TYPE, never by name or by cross-referencing the task list
            // (a cross-reference taken before this round's own task completions raced against
            // tasks THEY create as a side effect — e.g. completing "Register complaint" above
            // synchronously creates the "Assess complaint" task in the very same round — see
            // this test's own report for the exact repro). HUMAN_TASK items are always driven
            // through their worklist task (claim/complete above), never here — completing one
            // directly would bypass its form validation and race the task exactly as described.
            // STAGE items are excluded too: they complete themselves once their own required
            // children finish (proved by CaseApiIT, which never calls "complete" on a stage at
            // all and still reaches CLOSED) — completing one directly, as this test did before
            // this fix, force-completes it even while a required child ("assessComplaint") is
            // still open, which the API allows (ActionPolicy.listForPlanItem does not consult
            // StageCompletion.blockingItems the way the case-level "close" action does — a real
            // gap, reported rather than fixed here, see this task's report). That leaves exactly
            // PROCESS_TASK as the type this consumer ever completes directly in this case type —
            // discovered from the generic "type" field, not hardcoded as such.
            List<Map<String, Object>> items = list(http, "/cases/" + caseId + "/plan-items");
            for (Map<String, Object> item : items) {
                String type = (String) item.get("type");
                if ("STAGE".equals(type) || "HUMAN_TASK".equals(type)) {
                    continue;
                }
                if (hasAction(item, "complete")) {
                    invoke(http, action(item, "complete"), null);
                    progressed = true;
                }
            }

            if (!progressed) {
                sleep(200); // remote-mode-shaped grace period; harmless here too
            }
        }

        assertThat(closed).as("the generic consumer closed the case within " + MAX_ROUNDS + " rounds").isTrue();

        Map<String, Object> finalCase = map(http, "/cases/" + caseId);
        assertThat(finalCase.get("state")).isEqualTo("CLOSED");

        // Every milestone the case declares was achieved — read generically, no milestone name.
        List<Map<String, Object>> milestones = list(http, "/cases/" + caseId + "/milestones");
        assertThat(milestones).isNotEmpty();
        assertThat(milestones).allSatisfy(m -> assertThat(m.get("achieved")).isEqualTo(true));

        // Every plan item the DEFINITION flags manualActivation was never manually STARTED by
        // this consumer. Found the hard way while writing this test: entry-criteria admission
        // (AVAILABLE -> ENABLED) happens automatically regardless of manualActivation — "manual"
        // specifically gates the NEXT step, ENABLED -> ACTIVE, which needs an explicit "start"
        // (PlanModelEvaluator.targetOnEntry only ever returns ENABLED, never AVAILABLE, for an
        // item whose entry criteria already hold). So "investigation" (no entryCriteria of its
        // own, hence vacuously eligible from the moment the case is created) reaches ENABLED on
        // its own — this consumer never calls "start", so it is a legitimate, expected state
        // here, not a sign the discretionary-skip rule failed. The real, provable claim is
        // narrower and still non-vacuous: the item never reached ACTIVE or COMPLETED, which is
        // exactly what calling "start"/"complete" on it would have produced.
        // (The state used here is the PRE-close snapshot captured above, not a fresh post-close
        // read: CaseService.close sweeps every still-open plan item to TERMINATED, discretionary
        // or not, which is the engine's own doing on closing, not a signal this consumer ever
        // drove the item forward.)
        Map<String, String> stateByDefKey = itemsAtCloseTime.stream()
                .collect(Collectors.toMap(i -> (String) i.get("name"), i -> (String) i.get("state"),
                        (a, b) -> a));
        List<String> discretionaryDefKeys = planItemDefs.stream()
                .filter(p -> Boolean.TRUE.equals(p.get("manualActivation")))
                .map(p -> (String) p.get("defKey"))
                .toList();
        assertThat(discretionaryDefKeys).as("this case type declares at least one discretionary item")
                .isNotEmpty();
        for (String defKey : discretionaryDefKeys) {
            assertThat(stateByDefKey.get(defKey))
                    .as("discretionary item '%s' was never manually started by the generic consumer", defKey)
                    .isIn("AVAILABLE", "ENABLED");
        }
    }

    // ---- generic JSON-Schema-driven form filling: shape only, never a field's name ----

    @SuppressWarnings("unchecked")
    private static Map<String, Object> synthesize(Map<String, Object> schema) {
        Map<String, Object> variables = new LinkedHashMap<>();
        List<String> required = (List<String>) schema.getOrDefault("required", List.of());
        Map<String, Object> properties = (Map<String, Object>) schema.getOrDefault("properties", Map.of());
        for (String field : required) {
            Object propertySchema = properties.get(field);
            variables.put(field, propertySchema instanceof Map
                    ? synthesizeValue((Map<String, Object>) propertySchema)
                    : "generic-consumer-value");
        }
        return variables;
    }

    @SuppressWarnings("unchecked")
    private static Object synthesizeValue(Map<String, Object> propertySchema) {
        Object enumValues = propertySchema.get("enum");
        if (enumValues instanceof List<?> values && !values.isEmpty()) {
            return values.get(0); // pick the first legal value — the schema names none of them
        }
        Object type = propertySchema.get("type");
        if ("integer".equals(type) || "number".equals(type)) {
            Object minimum = propertySchema.get("minimum");
            return minimum instanceof Number number ? number : 0;
        }
        if ("boolean".equals(type)) {
            return Boolean.TRUE;
        }
        return "generic-consumer-value";
    }

    // ---- generic HTTP/discovery plumbing: hrefs and methods only, never a hand-built URL ----

    private static boolean hasAction(Map<String, Object> resource, String action) {
        return actionOrNull(resource, action) != null;
    }

    private static Map<String, Object> action(Map<String, Object> resource, String action) {
        Map<String, Object> found = actionOrNull(resource, action);
        if (found == null) {
            throw new AssertionError("Action '" + action + "' is not available on " + resource);
        }
        return found;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> actionOrNull(Map<String, Object> resource, String action) {
        List<Map<String, Object>> actions = (List<Map<String, Object>>) resource.get("availableActions");
        if (actions == null) {
            return null;
        }
        return actions.stream().filter(a -> action.equals(a.get("action"))).findFirst().orElse(null);
    }

    /**
     * Follows an {@code availableActions[]} entry verbatim: its {@code href} and {@code method},
     * resolved against the API's own base path. That base path is the one fact this consumer is
     * configured with (exactly {@code openapi-specs.md}'s {@code servers: [{url: /case-api/v2}]}
     * — a property of the API's deployment, not of any case type), never a case-shaped URL built
     * by hand.
     */
    private Map<String, Object> invoke(RestClient http, Map<String, Object> action, Map<String, Object> body) {
        String method = (String) action.get("method");
        String href = (String) action.get("href");
        var spec = "PATCH".equals(method)
                ? http.patch().uri(resolve(href)).contentType(MediaType.valueOf("application/merge-patch+json"))
                : http.post().uri(resolve(href)).header("If-Match", "*").contentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> response = (body == null ? spec : spec.body(body))
                .retrieve().toEntity(Map.class);
        assertThat(response.getStatusCode().is2xxSuccessful())
                .as("invoking discovered action %s -> %s %s returned %s: %s", action.get("action"), method,
                        href, response.getStatusCode(), response.getBody())
                .isTrue();
        return response.getBody();
    }

    private static String resolve(String href) {
        return "/case-api/v2" + href;
    }

    private static Map<String, Object> map(RestClient http, String path) {
        return (Map<String, Object>) http.get().uri(resolve(path)).retrieve().toEntity(Map.class).getBody();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(RestClient http, String path) {
        List<?> body = http.get().uri(resolve(path)).retrieve().toEntity(List.class).getBody();
        return (List<Map<String, Object>>) body;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}
