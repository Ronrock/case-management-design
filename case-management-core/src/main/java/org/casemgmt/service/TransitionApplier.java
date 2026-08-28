package org.casemgmt.service;

import org.casemgmt.domain.*;
import org.casemgmt.engine.EngineGateway;
import org.casemgmt.engine.EngineProcessRef;
import org.casemgmt.engine.EngineTaskRef;
import org.casemgmt.engine.HumanTaskRequest;
import org.casemgmt.engine.StartProcessByKeyRequest;
import org.casemgmt.error.InvalidCaseDefinitionException;
import org.casemgmt.event.CaseEvent;
import org.casemgmt.event.EventPublisher;
import org.casemgmt.event.EventTypes;
import org.casemgmt.repo.CaseTaskRepository;
import org.casemgmt.repo.LinkedProcessRepository;
import org.casemgmt.repo.MilestoneRepository;
import org.casemgmt.repo.PlanItemRepository;
import org.casemgmt.rules.CaseSnapshot;
import org.casemgmt.rules.Transition;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persists what the (pure) evaluator decided, and performs the side effects each
 * transition implies: engine tasks for activated human tasks, milestone rows for
 * achieved milestones, one event per transition. Runs in the caller's transaction
 * (see {@code CaseService} — this class is deliberately not itself {@code @Transactional},
 * for the same self-invocation reason {@link EventPublisher} isn't).
 *
 * <p>Reads each plan item fresh from the repository ({@link #apply}) rather than off the
 * {@code CaseSnapshot} passed in: {@code PlanModelEvaluator.evaluate} can return several
 * transitions for the SAME plan item across its internal rounds (e.g. AVAILABLE -&gt; ACTIVE
 * in round 1, ACTIVE -&gt; COMPLETED in round 2), all flattened into one list. The snapshot is a
 * single point-in-time read; re-reading before each {@code updateState} call is what keeps the
 * optimistic-lock {@code version} correct across a chain of transitions for one item within a
 * single {@code apply} call.
 *
 * <p><b>{@link #persist}/{@link #sideEffects} split (Task 16):</b> {@link #apply} persists and
 * then reacts, in one step per transition, which is exactly right for its callers (the evaluator
 * hands it transitions for rows nobody has written yet). {@code PlanItemService}'s manual
 * enable/start/complete/terminate actions are different: the caller already knows the exact
 * {@code expectedVersion} for the item it just read and has to enforce a legal-transition check
 * against it, so it does its own {@code planItems.updateState} rather than let {@code apply}
 * blindly re-read-and-write. Calling {@code apply} afterward would persist the SAME item a
 * second time — a redundant UPDATE, a second version bump, and (since the caller would then no
 * longer know the final version without a re-read) a violation of the "build the return value
 * from your own successful write, don't re-read" rule Task 4 established. {@link #sideEffects}
 * is {@link #apply}'s per-transition body minus the write: it takes the already-persisted
 * {@link PlanItem} and only does what {@link #apply} does after its own {@code updateState}
 * call — engine task creation, milestone achievement, the transitioned event. This keeps every
 * transition, manual or evaluator-driven, going through exactly one write path.
 */
public class TransitionApplier {

    private final PlanItemRepository planItems;
    private final CaseTaskRepository tasks;
    private final LinkedProcessRepository linkedProcesses;
    private final MilestoneRepository milestones;
    private final EngineGateway engine;
    private final EventPublisher publisher;

    public TransitionApplier(PlanItemRepository planItems, CaseTaskRepository tasks,
                             LinkedProcessRepository linkedProcesses, MilestoneRepository milestones,
                             EngineGateway engine, EventPublisher publisher) {
        this.planItems = planItems;
        this.tasks = tasks;
        this.linkedProcesses = linkedProcesses;
        this.milestones = milestones;
        this.engine = engine;
        this.publisher = publisher;
    }

    public void apply(CaseSnapshot snapshot, List<Transition> transitions, Actor actor) {
        for (Transition t : transitions) {
            PlanItem item = planItems.require(t.planItemId());
            PlanItem updated = planItems.updateState(item.withState(t.to()), item.version());
            sideEffects(snapshot, t, updated, actor);
        }
    }

    /**
     * Everything a transition implies beyond persisting the state itself: an engine task for a
     * freshly-ACTIVE human task, a milestone row for a freshly-COMPLETED milestone, and always
     * the {@code case.planitem.transitioned} event. Takes the already-persisted {@link PlanItem}
     * (post-{@code updateState}, so {@code updated.version()} is the new version) rather than
     * persisting it itself — see the class Javadoc for why {@code PlanItemService} needs this
     * split instead of calling {@link #apply}.
     */
    public void sideEffects(CaseSnapshot snapshot, Transition t, PlanItem updated, Actor actor) {
        PlanItemDefinition def = snapshot.definitionOf(updated);

        if (t.to() == PlanItemState.ACTIVE && def.type() == PlanItemType.HUMAN_TASK) {
            createHumanTask(snapshot, updated, def, actor);
        }
        if (t.to() == PlanItemState.ACTIVE && def.type() == PlanItemType.PROCESS_TASK) {
            startProcessTask(snapshot, updated, def, actor);
        }
        if (t.to() == PlanItemState.COMPLETED && def.type() == PlanItemType.MILESTONE) {
            achieveMilestone(snapshot, updated, actor);
        }
        publisher.publish(event(snapshot, EventTypes.PLAN_ITEM_TRANSITIONED, Map.of(
                "planItemId", updated.id(), "defKey", def.defKey(),
                "from", t.from().name(), "to", t.to().name(), "reason", t.reason())));
    }

    private void createHumanTask(CaseSnapshot snapshot, PlanItem item, PlanItemDefinition def, Actor actor) {
        String taskId = CaseIds.newId();
        EngineTaskRef ref = engine.createHumanTask(new HumanTaskRequest(
                snapshot.caseInstance().id(), item.id(), def.name(), null,
                def.candidateGroups(), def.formKey(), snapshot.caseInstance().variables()));

        CaseTask.EngineSync sync = ref.engineTaskId() == null
                ? CaseTask.EngineSync.PENDING      // remote mode: the dispatcher confirms later
                : CaseTask.EngineSync.SYNCED;

        OffsetDateTime now = OffsetDateTime.now();
        tasks.insert(new CaseTask(taskId, snapshot.caseInstance().id(), item.id(),
                ref.engineTaskId(), def.name(), null, TaskState.OPEN, null, null,
                def.candidateGroups(), def.formKey(), 50, null, null, sync, 0L, now, now, null));

        if (ref.engineTaskId() != null) {
            planItems.bindEngineTask(item.id(), ref.engineTaskId());
        }
        publisher.publish(event(snapshot, EventTypes.TASK_CREATED, Map.of(
                "taskId", taskId, "planItemId", item.id(), "name", def.name(),
                "engineSync", sync.name())));
    }

    private void startProcessTask(CaseSnapshot snapshot, PlanItem item, PlanItemDefinition def,
                                  Actor actor) {
        if (def.processDefinitionKey() == null || def.processDefinitionKey().isBlank()) {
            throw new InvalidCaseDefinitionException(snapshot.caseInstance().caseDefKey(),
                    "PROCESS_TASK '" + def.defKey() + "' has no processDefinitionKey");
        }

        String id = CaseIds.newId();
        EngineProcessRef ref = engine.startProcessByKey(new StartProcessByKeyRequest(
                snapshot.caseInstance().id(), item.id(), def.processDefinitionKey(),
                snapshot.caseInstance().variables(), id));
        String instanceId = ref.processInstanceId();
        CaseTask.EngineSync sync = instanceId == null
                ? CaseTask.EngineSync.PENDING
                : CaseTask.EngineSync.SYNCED;

        linkedProcesses.insert(id, snapshot.caseInstance().id(), item.id(), instanceId,
                def.processDefinitionKey(), sync);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("linkedProcessId", id);
        details.put("planItemId", item.id());
        details.put("processDefinitionKey", def.processDefinitionKey());
        details.put("engineSync", sync.name());
        if (instanceId != null) {
            details.put("processInstanceId", instanceId);
        }
        publisher.publish(event(snapshot, EventTypes.PROCESS_STARTED, details));

        Map<String, Object> auditDetails = new LinkedHashMap<>();
        auditDetails.put("planItemId", item.id());
        auditDetails.put("processDefinitionKey", def.processDefinitionKey());
        if (instanceId != null) {
            auditDetails.put("processInstanceId", instanceId);
        }
        publisher.audit(snapshot.caseInstance().id(), snapshot.caseInstance().tenantId(),
                actor.userId(), "process.start", "LinkedProcess", id, null,
                auditDetails);
    }

    private void achieveMilestone(CaseSnapshot snapshot, PlanItem item, Actor actor) {
        String milestoneId = milestones.findByPlanItem(item.id())
                .map(MilestoneRepository.MilestoneRow::id)
                .orElseGet(() -> {
                    String id = CaseIds.newId();
                    milestones.insert(id, snapshot.caseInstance().id(), item.id(), item.name());
                    return id;
                });
        milestones.achieve(milestoneId, actor.userId());
        publisher.publish(event(snapshot, EventTypes.MILESTONE_ACHIEVED, Map.of(
                "milestoneId", milestoneId, "planItemId", item.id(), "name", item.name())));
    }

    private CaseEvent event(CaseSnapshot snapshot, String type, Map<String, Object> data) {
        return new CaseEvent(CaseIds.newId(), publisher.engineId(), type,
                snapshot.caseInstance().id(), snapshot.caseInstance().tenantId(),
                OffsetDateTime.now(), data);
    }
}
