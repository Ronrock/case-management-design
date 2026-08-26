package org.casemgmt.service;

import org.casemgmt.domain.*;
import org.casemgmt.engine.EngineGateway;
import org.casemgmt.error.CaseConflictException;
import org.casemgmt.error.InvalidCaseDefinitionException;
import org.casemgmt.error.OptimisticLockException;
import org.casemgmt.event.CaseEvent;
import org.casemgmt.event.EventPublisher;
import org.casemgmt.event.EventTypes;
import org.casemgmt.orchestration.OrchestrationMode;
import org.casemgmt.repo.CaseDefinitionRepository;
import org.casemgmt.repo.CaseRepository;
import org.casemgmt.repo.CaseTaskRepository;
import org.casemgmt.repo.PlanItemRepository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Worklist, claim and complete for {@code CM_TASK} rows — the human side of the case (spec
 * §4.5/§4.6). Completing a task validates its payload against the declared form schema, tells
 * the engine, marks the row COMPLETED, and completes the plan item behind it so the model
 * re-evaluates (a task never outlives its plan item, and the plan item is what {@code
 * PlanModelEvaluator} actually reasons about).
 *
 * <p><b>Claim/complete legality must agree with {@code ActionPolicy.listForTask}</b>
 * (case-management-rest, Task 23) — checked directly against it while writing this class:
 * <ul>
 *   <li>{@code claim}: legal only from {@code OPEN}, and only once {@code engineSync == SYNCED}
 *   (an unsynced task's engine counterpart may not exist yet — see {@code CaseTaskRepository
 *   .worklist}'s Javadoc for the same rule applied to visibility). Both match {@code
 *   ActionPolicy.listForTask}, which offers {@code claim} only for {@code OPEN} tasks and offers
 *   nothing at all once {@code engineSync != SYNCED}.</li>
 *   <li>{@code complete}: legal only from {@code CLAIMED}. {@code ActionPolicy.listForTask} only
 *   ever advertises {@code complete} for a {@code CLAIMED} task assigned to the calling user —
 *   this class enforces the state half of that (the identity half — "assigned to the calling
 *   user" — is authorization, which is {@code ActionPolicy}'s job at the REST boundary, the same
 *   division {@code PlanItemService} uses for its own actions). Requiring {@code CLAIMED} here
 *   (not merely "not already ended") keeps the service exactly as permissive as the policy that
 *   gates it: the policy never offers {@code complete} on an {@code OPEN} task, so the service
 *   must not silently accept one either.</li>
 * </ul>
 *
 * <p><b>Completion order (brief, spec §4.6):</b> validate the payload -&gt; complete the engine
 * task -&gt; mark {@code CM_TASK} completed -&gt; complete the backing plan item -&gt; re-evaluate
 * (the last two happen inside {@link PlanItemService#complete}). If the engine call fails, the
 * whole transaction rolls back in embedded mode; in remote mode the command outbox retries it,
 * and the task stays {@code CLAIMED} until it succeeds.
 */
public class CaseTaskService {

    private final CaseTaskRepository tasks;
    private final CaseRepository cases;
    private final CaseDefinitionRepository definitions;
    private final EngineGateway engine;
    private final FormValidator formValidator;
    private final PlanItemService planItems;
    private final PlanItemRepository planItemRepo;
    private final EventPublisher publisher;

    public CaseTaskService(CaseTaskRepository tasks, CaseRepository cases,
                           CaseDefinitionRepository definitions, EngineGateway engine,
                           FormValidator formValidator, PlanItemService planItems,
                           PlanItemRepository planItemRepo, EventPublisher publisher) {
        this.tasks = tasks;
        this.cases = cases;
        this.definitions = definitions;
        this.engine = engine;
        this.formValidator = formValidator;
        this.planItems = planItems;
        this.planItemRepo = planItemRepo;
        this.publisher = publisher;
    }

    /**
     * "My work OR work I could pick up" — see {@code CaseTaskRepository.worklist}'s Javadoc for
     * the OR-not-AND, empty-means-nothing rules this delegates straight through to. Passes the
     * caller's own user id as {@code assignee} (not {@code null}) so a task already claimed by
     * this caller stays visible even if it no longer matches any of the caller's current
     * candidate groups.
     *
     * <p>{@code tenantId} is required by the caller, not derived here: identity groups are
     * global, so without it a user of one tenant sees another tenant's tasks purely by being in
     * a similarly-named group (Task 24 fix round 1). The REST layer derives it from the
     * authenticated principal, never from the request.
     */
    public List<CaseTask> worklist(String tenantId, Actor actor, int limit) {
        return tasks.worklist(tenantId, actor.userId(), actor.groups(), limit);
    }

    public List<CaseTask> forCase(String caseId) {
        return tasks.findByCase(caseId);
    }

    @Transactional
    public CaseTask claim(String taskId, long expectedVersion, Actor actor) {
        CaseTask task = tasks.require(taskId);
        if (task.state() != TaskState.OPEN) {
            throw new CaseConflictException("task-not-open",
                    "Task is " + task.state() + (task.assignee() == null ? "" : " (assignee " + task.assignee() + ")"),
                    task.state() == TaskState.CLAIMED ? List.of("complete") : List.of());
        }
        if (task.engineSync() != CaseTask.EngineSync.SYNCED) {
            throw new CaseConflictException("engine-sync-pending",
                    "Task is not yet created on the engine (sync state " + task.engineSync() + ")",
                    List.of());
        }

        engine.claimTask(task.engineTaskId(), actor.userId());

        CaseTask claimed = withState(task, TaskState.CLAIMED, actor.userId(), task.outcome());
        CaseTask saved = save(claimed, expectedVersion);

        CaseInstance c = cases.require(task.caseId());
        publisher.publish(event(c, EventTypes.TASK_CLAIMED,
                Map.of("taskId", taskId, "assignee", actor.userId())));
        publisher.audit(task.caseId(), c.tenantId(), actor.userId(), "task.claim", "Task", taskId,
                Map.of("state", task.state().name()), Map.of("state", "CLAIMED", "assignee", actor.userId()));
        return saved;
    }

    @Transactional
    public CaseTask complete(String taskId, long expectedVersion,
                             Map<String, Object> variables, Actor actor) {
        CaseTask task = tasks.require(taskId);
        if (task.state() != TaskState.CLAIMED) {
            throw new CaseConflictException("task-not-claimed",
                    "Task is " + task.state() + "; only a claimed task can be completed", List.of());
        }

        CaseInstance c = cases.require(task.caseId());
        if (task.formKey() != null) {
            // Review fix (Minor): a declared formKey with no matching schema used to be
            // silently treated as "nothing to validate" — exactly the silent form-contract
            // failure the schema mechanism exists to prevent (spec §4.6). A typo'd formKey in
            // a case definition (deploy time does not currently cross-check formKey against the
            // "forms" map) would otherwise disable this task's entire safety net with no signal
            // anywhere. Failing loudly here, the same way CaseSnapshot.definitionOf treats an
            // unresolvable reference, surfaces the inconsistency instead of masking it.
            //
            // Task 24 (carried finding C2): the type thrown here used to be a bare
            // IllegalStateException, which ProblemDetailHandler maps nowhere and Spring
            // therefore ships as an opaque 500. See InvalidCaseDefinitionException's Javadoc
            // for why a definition-authoring typo is 400-shaped rather than a server fault.
            // The condition detected, and the message, are unchanged.
            //
            // Final whole-branch review (Important 1): resolved through the case's own PINNED
            // definition id, not through the latest form schema for the key. Even the
            // tenant-scoped discovery lookup is wrong for this write path because it is not
            // version-pinned:
            //   * version drift — deploying v2 with a new `required` field re-validated every
            //     in-flight v1 case's task completion against v2, the exact failure versioned
            //     definitions exist to prevent (every other definition lookup in this codebase
            //     already resolves the pinned row: CaseService.snapshot uses
            //     definitions.require(instance.caseDefId()));
            //   * cross-tenant — tenant t1 at v3 and tenant t2 at v1 meant t2's tasks were
            //     validated against t1's schema.
            // CM_CASE.CASE_DEF_ID_ exists precisely to pin the definition; c.caseDefKey() is
            // kept only for the error message, which names the key a human recognises.
            Map<String, Object> schema =
                    definitions.formSchemaOfDefinition(c.caseDefId(), task.formKey())
                    .orElseThrow(() -> new InvalidCaseDefinitionException(c.caseDefKey(),
                            "Task " + taskId + " declares formKey '"
                            + task.formKey() + "' but case definition '" + c.caseDefKey()
                            + "' (version " + c.caseDefVersion()
                            + ") has no matching form schema"));
            formValidator.validate(schema, variables);
        }

        engine.completeTask(task.engineTaskId(), variables);

        CaseTask completed = withState(task, TaskState.COMPLETED, task.assignee(), outcomeOf(variables));
        CaseTask saved = save(completed, expectedVersion);

        publisher.publish(event(c, EventTypes.TASK_COMPLETED,
                Map.of("taskId", taskId, "outcome", saved.outcome() == null ? "" : saved.outcome())));
        publisher.audit(task.caseId(), c.tenantId(), actor.userId(), "task.complete", "Task", taskId,
                Map.of("state", task.state().name()), Map.of("state", "COMPLETED"));

        // Legacy tasks drive their plan model explicitly. For BPMN cases, Operaton's task
        // completion event has already terminalized the projected task and plan item in this
        // same transaction; invoking PlanItemService would try to complete that ended item a
        // second time and, more importantly, would let the legacy evaluator participate in a
        // lifecycle owned by the root BPMN process.
        if (definitions.require(c.caseDefId()).orchestrationMode() == OrchestrationMode.PLAN_MODEL) {
            PlanItem planItem = planItemRepo.require(task.planItemId());
            planItems.complete(task.caseId(), planItem.id(), planItem.version(), actor);
        }

        return saved;
    }

    /**
     * Review fix: {@code variables.get("outcome")} returns Java {@code null} when the key is
     * simply absent (as opposed to present with a null value — {@code Map.of} payloads can't
     * even express that), and {@code String.valueOf(null)} silently turns that into the
     * four-character string {@code "null"} — a real value that persists into {@code
     * OUTCOME_} and ships in the {@code TASK_COMPLETED} event, defeating any later
     * {@code WHERE OUTCOME_ IS NULL} query. This checks the extracted value for null itself
     * before stringifying, so an absent or null {@code outcome} stays a real {@code null}.
     */
    private String outcomeOf(Map<String, Object> variables) {
        if (variables == null) {
            return null;
        }
        Object outcome = variables.get("outcome");
        return outcome == null ? null : String.valueOf(outcome);
    }

    private CaseTask save(CaseTask task, long expectedVersion) {
        try {
            return tasks.update(task, expectedVersion);
        } catch (OptimisticLockException e) {
            throw new CaseConflictException("version-conflict", e.getMessage(), List.of());
        }
    }

    private CaseTask withState(CaseTask t, TaskState state, String assignee, String outcome) {
        return new CaseTask(t.id(), t.caseId(), t.planItemId(), t.engineTaskId(), t.name(),
                t.description(), state, assignee, t.delegatedBy(), t.candidateGroups(),
                t.formKey(), t.priority(), t.dueAt(), outcome, t.engineSync(), t.version(),
                t.createdAt(), t.updatedAt(), t.completedAt());
    }

    private CaseEvent event(CaseInstance c, String type, Map<String, Object> data) {
        return new CaseEvent(CaseIds.newId(), publisher.engineId(), type, c.id(), c.tenantId(),
                OffsetDateTime.now(), data);
    }
}
