package org.casemgmt.service;

import org.casemgmt.domain.*;
import org.casemgmt.engine.EngineGateway;
import org.casemgmt.engine.EngineTaskRef;
import org.casemgmt.engine.HumanTaskRequest;
import org.casemgmt.event.CaseEvent;
import org.casemgmt.event.EventPublisher;
import org.casemgmt.event.EventTypes;
import org.casemgmt.repo.CaseTaskRepository;
import org.casemgmt.repo.MilestoneRepository;
import org.casemgmt.repo.PlanItemRepository;
import org.casemgmt.rules.CaseSnapshot;
import org.casemgmt.rules.Transition;

import java.time.OffsetDateTime;
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
 */
public class TransitionApplier {

    private final PlanItemRepository planItems;
    private final CaseTaskRepository tasks;
    private final MilestoneRepository milestones;
    private final EngineGateway engine;
    private final EventPublisher publisher;

    public TransitionApplier(PlanItemRepository planItems, CaseTaskRepository tasks,
                             MilestoneRepository milestones, EngineGateway engine,
                             EventPublisher publisher) {
        this.planItems = planItems;
        this.tasks = tasks;
        this.milestones = milestones;
        this.engine = engine;
        this.publisher = publisher;
    }

    public void apply(CaseSnapshot snapshot, List<Transition> transitions, Actor actor) {
        for (Transition t : transitions) {
            PlanItem item = planItems.require(t.planItemId());
            PlanItem updated = planItems.updateState(item.withState(t.to()), item.version());
            PlanItemDefinition def = snapshot.definitionOf(item);

            if (t.to() == PlanItemState.ACTIVE && def.type() == PlanItemType.HUMAN_TASK) {
                createHumanTask(snapshot, updated, def, actor);
            }
            if (t.to() == PlanItemState.COMPLETED && def.type() == PlanItemType.MILESTONE) {
                achieveMilestone(snapshot, updated, actor);
            }
            publisher.publish(event(snapshot, EventTypes.PLAN_ITEM_TRANSITIONED, Map.of(
                    "planItemId", updated.id(), "defKey", def.defKey(),
                    "from", t.from().name(), "to", t.to().name(), "reason", t.reason())));
        }
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
