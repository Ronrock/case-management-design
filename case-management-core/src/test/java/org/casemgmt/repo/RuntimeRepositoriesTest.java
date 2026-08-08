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

    /** Builds an OPEN, SYNCED task with the given assignee/candidate groups, backed by a fresh plan item. */
    private CaseTask openTask(String id, String assignee, List<String> groups) {
        planItems.insert(item(id + "-pi", PlanItemState.ACTIVE));
        return new CaseTask(id, "eng-a:1", id + "-pi", null, "T", null,
                TaskState.OPEN, assignee, null, groups, null, 50, null, null,
                CaseTask.EngineSync.SYNCED, 0L, OffsetDateTime.now(), OffsetDateTime.now(), null);
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
        assertThat(tasks.worklist(null, null, List.of("reviewers"), 20))
                .extracting(CaseTask::id).containsExactly("t-1");
    }

    @Test
    void worklistExcludesTasksNotYetSyncedToTheEngine() {
        planItems.insert(item("pi-3", PlanItemState.ACTIVE));
        tasks.insert(new CaseTask("t-2", "eng-a:1", "pi-3", null, "Pending", null,
                TaskState.OPEN, null, null, List.of("reviewers"), null, 50, null, null,
                CaseTask.EngineSync.PENDING, 0L, OffsetDateTime.now(), OffsetDateTime.now(), null));

        assertThat(tasks.worklist(null, null, List.of("reviewers"), 20)).isEmpty();

        tasks.markSync("t-2", CaseTask.EngineSync.SYNCED, "engine-task-10");

        assertThat(tasks.worklist(null, null, List.of("reviewers"), 20))
                .extracting(CaseTask::id).containsExactly("t-2");
    }

    @Test
    void worklistWithNoAssigneeAndNoGroupsReturnsNothing() {
        // A caller who is neither the assignee of anything nor a member of any candidate
        // group must see an empty worklist, not every task in the system. Dropping the
        // group predicate entirely when the groups list is empty (rather than matching
        // nothing) was the CRITICAL defect the reviewer caught.
        tasks.insert(openTask("t-none-1", "alice", List.of("reviewers")));
        tasks.insert(openTask("t-none-2", null, List.of("reviewers")));

        assertThat(tasks.worklist(null, null, List.of(), 20)).isEmpty();
    }

    @Test
    void worklistMatchesByAssigneeOnlyWhenNoGroupsSupplied() {
        tasks.insert(openTask("t-ao-1", "alice", List.of()));
        tasks.insert(openTask("t-ao-2", "bob", List.of()));

        assertThat(tasks.worklist(null, "alice", List.of(), 20))
                .extracting(CaseTask::id).containsExactly("t-ao-1");
    }

    @Test
    void worklistMatchesByGroupOnlyWhenNoAssigneeSupplied() {
        tasks.insert(openTask("t-go-1", null, List.of("reviewers")));
        tasks.insert(openTask("t-go-2", null, List.of("editors")));

        assertThat(tasks.worklist(null, null, List.of("reviewers"), 20))
                .extracting(CaseTask::id).containsExactly("t-go-1");
    }

    @Test
    void worklistOrsAssigneeAndCandidateGroupsRatherThanAnding() {
        // "My work OR work I could pick up" — the same OR semantics Task 23's
        // ActionPolicy.mayActOnTask already established for the same question. ANDing
        // the two (the second defect the reviewer caught) hides both a task assigned to
        // me whose groups I don't share, and an unassigned task whose group I do share.
        tasks.insert(openTask("t-or-mine", "alice", List.of("finance")));       // matches via assignee only
        tasks.insert(openTask("t-or-group", null, List.of("reviewers")));       // matches via group only
        tasks.insert(openTask("t-or-neither", "bob", List.of("finance")));      // matches neither

        assertThat(tasks.worklist(null, "alice", List.of("reviewers"), 20))
                .extracting(CaseTask::id)
                .containsExactlyInAnyOrder("t-or-mine", "t-or-group");
    }

    @Test
    void worklistIgnoresTaskWithEmptyCandidateGroupsArray() {
        tasks.insert(openTask("t-empty-cg", null, List.of()));

        assertThat(tasks.worklist(null, null, List.of("reviewers"), 20)).isEmpty();
    }

    @Test
    void worklistMatchesWhenAnyOfSeveralCandidateGroupsOverlaps() {
        tasks.insert(openTask("t-multi", null, List.of("a", "b", "c")));

        assertThat(tasks.worklist(null, null, List.of("x", "b", "y"), 20))
                .extracting(CaseTask::id).containsExactly("t-multi");
    }

    @Test
    void worklistExcludesFailedSyncAlongsidePending() {
        planItems.insert(item("t-failed-pi", PlanItemState.ACTIVE));
        tasks.insert(new CaseTask("t-failed", "eng-a:1", "t-failed-pi", null, "T", null,
                TaskState.OPEN, "alice", null, List.of("reviewers"), null, 50, null, null,
                CaseTask.EngineSync.FAILED, 0L, OffsetDateTime.now(), OffsetDateTime.now(), null));

        assertThat(tasks.worklist(null, "alice", List.of("reviewers"), 20)).isEmpty();
    }

    @Test
    void worklistRespectsLimit() {
        tasks.insert(openTask("t-lim-1", null, List.of("reviewers")));
        tasks.insert(openTask("t-lim-2", null, List.of("reviewers")));
        tasks.insert(openTask("t-lim-3", null, List.of("reviewers")));

        assertThat(tasks.worklist(null, null, List.of("reviewers"), 2)).hasSize(2);
    }

    @Test
    void markSyncIsFirstWriterWinsOnCamundaTaskIdUnderDuplicateRedelivery() {
        // Task 13's outbox dispatcher retries at-least-once. If the same CREATE_TASK command
        // is executed twice (e.g. a crash between the remote call succeeding and the command
        // being marked DONE), markSync gets called twice for the same CM_TASK row with two
        // DIFFERENT engine ids. The first engine id is the one CM_TASK must keep: overwriting
        // it would orphan the first (real) engine task with nothing pointing at it any more.
        planItems.insert(item("pi-idem", PlanItemState.ACTIVE));
        tasks.insert(new CaseTask("t-idem", "eng-a:1", "pi-idem", null, "Review", null,
                TaskState.OPEN, null, null, List.of(), null, 50, null, null,
                CaseTask.EngineSync.PENDING, 0L, OffsetDateTime.now(), OffsetDateTime.now(), null));

        tasks.markSync("t-idem", CaseTask.EngineSync.SYNCED, "engine-task-first");
        tasks.markSync("t-idem", CaseTask.EngineSync.SYNCED, "engine-task-second-duplicate");

        CaseTask loaded = tasks.require("t-idem");
        assertThat(loaded.engineTaskId()).isEqualTo("engine-task-first");
        assertThat(loaded.engineSync()).isEqualTo(CaseTask.EngineSync.SYNCED);
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
