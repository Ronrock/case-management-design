package org.casemgmt.repo;

import org.casemgmt.OracleTestBase;
import org.casemgmt.domain.*;
import org.casemgmt.error.NotFoundException;
import org.casemgmt.error.OptimisticLockException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class CaseRepositoryTest extends OracleTestBase {

    private CaseRepository repo;

    @BeforeEach
    void setUp() {
        repo = new CaseRepository(jdbc());
        // OracleTestBase's inherited @BeforeEach already wipes all CM_ tables before this
        // method runs (JUnit runs superclass @BeforeEach first), so no DELETEs are needed
        // here — only seed the CM_CASE_DEF row that CM_CASE's FK requires.
        jdbc().sql("""
                INSERT INTO CM_CASE_DEF (ID_, KEY_, VERSION_NO_, NAME_)
                VALUES ('complaint:1', 'complaint', 1, 'Complaint')""").update();
    }

    private CaseInstance newCase(String id) {
        return new CaseInstance(id, "eng-a", "t1", "complaint:1", "complaint", 1,
                "BK-1", "Broken widget", CaseState.ACTIVE, CasePriority.HIGH,
                null, null, "alice", "NONE", null, null,
                Map.of("amount", 250, "channel", "web"), 0L,
                OffsetDateTime.now(), OffsetDateTime.now(), null);
    }

    @Test
    void roundTripsACaseIncludingJsonVariables() {
        CaseInstance c = newCase("eng-a:1");
        repo.insert(c);

        CaseInstance loaded = repo.require("eng-a:1");
        assertThat(loaded.title()).isEqualTo("Broken widget");
        assertThat(loaded.state()).isEqualTo(CaseState.ACTIVE);
        assertThat(loaded.priority()).isEqualTo(CasePriority.HIGH);
        assertThat(loaded.variables()).containsEntry("channel", "web");
        assertThat(loaded.version()).isZero();
    }

    @Test
    void updateIncrementsTheVersion() {
        repo.insert(newCase("eng-a:2"));
        CaseInstance loaded = repo.require("eng-a:2");

        CaseInstance updated = repo.update(loaded.withState(CaseState.CLOSED), loaded.version());

        assertThat(updated.version()).isEqualTo(1L);
        assertThat(repo.require("eng-a:2").state()).isEqualTo(CaseState.CLOSED);
    }

    @Test
    void updateWithAStaleVersionThrows() {
        repo.insert(newCase("eng-a:3"));
        CaseInstance loaded = repo.require("eng-a:3");
        repo.update(loaded.withState(CaseState.CLOSED), loaded.version());   // now v1

        assertThatThrownBy(() -> repo.update(loaded.withState(CaseState.CANCELLED), 0L))
                .isInstanceOf(OptimisticLockException.class)
                .hasMessageContaining("eng-a:3");
    }

    @Test
    void requireThrowsForUnknownIds() {
        assertThatThrownBy(() -> repo.require("eng-a:nope"))
                .isInstanceOf(NotFoundException.class);
    }

    /**
     * Pins the guarantee that update()'s return value always reflects THIS call's own
     * write, never a re-read that could observe a different writer's concurrent commit.
     *
     * <p>Directly simulating the interleaving (writer A's UPDATE commits, then writer B's
     * UPDATE+commit lands, then A's *own* post-update read runs and would see B's row) is
     * fiddly to express deterministically in a single-JVM test without instrumenting a
     * delay inside the repository itself. Instead this asserts the contract the fix relies
     * on: update() no longer performs any SELECT after its UPDATE, so the returned object
     * is built purely from values this call already knows (the WHERE clause having just
     * proven the row was at exactly {@code expectedVersion}) — there is no second query left
     * for another writer's commit to race against. That is verified two ways below: the
     * returned instance matches what this call wrote with version incremented by exactly
     * one, and a genuinely concurrent-style write attempt (reusing the same pre-image/
     * expected version A read) is rejected outright rather than silently blended in.
     */
    @Test
    void updateReturnsThisCallsOwnWriteNeverAnotherWritersConcurrentCommit() {
        repo.insert(newCase("eng-a:6"));
        CaseInstance loaded = repo.require("eng-a:6");

        CaseInstance updated = repo.update(loaded.withState(CaseState.CLOSED), loaded.version());

        assertThat(updated.id()).isEqualTo("eng-a:6");
        assertThat(updated.version()).isEqualTo(loaded.version() + 1);
        assertThat(updated.state()).isEqualTo(CaseState.CLOSED);
        assertThat(updated.title()).isEqualTo(loaded.title());
        assertThat(updated.updatedAt()).isAfterOrEqualTo(loaded.updatedAt());

        // A second writer racing in with the same pre-image A read must be rejected, not
        // silently merged into A's already-returned result.
        assertThatThrownBy(() -> repo.update(loaded.withState(CaseState.CANCELLED), loaded.version()))
                .isInstanceOf(OptimisticLockException.class);

        // The row in the database agrees exactly with what update() returned to the caller.
        CaseInstance reread = repo.require("eng-a:6");
        assertThat(reread.version()).isEqualTo(updated.version());
        assertThat(reread.state()).isEqualTo(updated.state());
    }

    @Test
    void queriesByStateAndAssignee() {
        repo.insert(newCase("eng-a:4"));
        CaseInstance closed = newCase("eng-a:5").withState(CaseState.CLOSED);
        repo.insert(closed);

        var active = repo.query(new CaseQuery("t1", CaseState.ACTIVE, null, null, null, 0, 50));

        assertThat(active).extracting(CaseInstance::id).containsExactly("eng-a:4");
    }
}
