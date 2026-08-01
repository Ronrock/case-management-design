package org.casemgmt.repo;

import org.casemgmt.OracleTestBase;
import org.casemgmt.domain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class RuntimeRepositoriesTest extends OracleTestBase {

    private PlanItemRepository planItems;
    private CaseTaskRepository tasks;
    private ParticipantRepository participants;
    private CommentRepository comments;

    @BeforeEach
    void setUp() {
        // OracleTestBase already wipes every CM_ table before each test method; only the
        // seed data this class's own tests depend on needs inserting here.
        jdbc().sql("INSERT INTO CM_CASE_DEF (ID_, KEY_, VERSION_NO_, NAME_) VALUES ('d:1','d',1,'D')").update();

        new CaseRepository(jdbc()).insert(new CaseInstance("eng-a:1", "eng-a", "t1", "d:1", "d", 1,
                null, "T", CaseState.ACTIVE, CasePriority.MEDIUM, null, null, "alice", "NONE",
                null, null, Map.of(), 0L, OffsetDateTime.now(), OffsetDateTime.now(), null));

        planItems = new PlanItemRepository(jdbc());
        tasks = new CaseTaskRepository(jdbc());
        participants = new ParticipantRepository(jdbc());
        comments = new CommentRepository(jdbc());
    }

    private PlanItem item(String id, PlanItemState state) {
        return new PlanItem(id, "eng-a:1", "pd-1", PlanItemType.HUMAN_TASK, "Review", state,
                null, false, 1, null, null, null, 0L,
                OffsetDateTime.now(), OffsetDateTime.now(), null);
    }

    @Test
    void planItemsRoundTripAndUpdateStateOptimistically() {
        planItems.insert(item("pi-1", PlanItemState.AVAILABLE));

        PlanItem loaded = planItems.require("pi-1");
        PlanItem updated = planItems.updateState(loaded.withState(PlanItemState.ACTIVE), loaded.version());

        assertThat(updated.state()).isEqualTo(PlanItemState.ACTIVE);
        assertThat(updated.version()).isEqualTo(1L);
        assertThatThrownBy(() -> planItems.updateState(loaded.withState(PlanItemState.COMPLETED), 0L))
                .isInstanceOf(org.casemgmt.error.OptimisticLockException.class);
    }

    @Test
    void tasksAreFoundByEngineTaskIdAndByWorklist() {
        planItems.insert(item("pi-2", PlanItemState.ACTIVE));
        tasks.insert(new CaseTask("t-1", "eng-a:1", "pi-2", "engine-task-9", "Review", null,
                TaskState.OPEN, null, null, List.of("reviewers"), "reviewForm", 50, null, null,
                CaseTask.EngineSync.SYNCED, 0L, OffsetDateTime.now(), OffsetDateTime.now(), null));

        assertThat(tasks.findByEngineTaskId("engine-task-9")).isPresent();
        assertThat(tasks.worklist(null, List.of("reviewers"), 20))
                .extracting(CaseTask::id).containsExactly("t-1");
    }

    @Test
    void worklistExcludesTasksNotYetSyncedToTheEngine() {
        planItems.insert(item("pi-3", PlanItemState.ACTIVE));
        tasks.insert(new CaseTask("t-2", "eng-a:1", "pi-3", null, "Pending", null,
                TaskState.OPEN, null, null, List.of("reviewers"), null, 50, null, null,
                CaseTask.EngineSync.PENDING, 0L, OffsetDateTime.now(), OffsetDateTime.now(), null));

        assertThat(tasks.worklist(null, List.of("reviewers"), 20)).isEmpty();

        tasks.markSync("t-2", CaseTask.EngineSync.SYNCED, "engine-task-10");

        assertThat(tasks.worklist(null, List.of("reviewers"), 20))
                .extracting(CaseTask::id).containsExactly("t-2");
    }

    @Test
    void participantRolesAreResolvedForUserAndGroups() {
        participants.insert("p-1", "eng-a:1", "alice", null, "owner");
        participants.insert("p-2", "eng-a:1", null, "reviewers", "reviewer");

        assertThat(participants.rolesOf("eng-a:1", "alice", List.of())).containsExactly("owner");
        assertThat(participants.rolesOf("eng-a:1", "bob", List.of("reviewers"))).containsExactly("reviewer");
        assertThat(participants.rolesOf("eng-a:1", "carol", List.of())).isEmpty();
    }

    @Test
    void commentsFilterByVisibility() {
        comments.insert("c-1", "eng-a:1", "alice", "internal note", "internal");
        comments.insert("c-2", "eng-a:1", "alice", "dear customer", "external");

        assertThat(comments.findByCase("eng-a:1", "external")).hasSize(1);
        assertThat(comments.findByCase("eng-a:1", null)).hasSize(2);
    }
}
