package org.casemgmt.repo;

import org.casemgmt.OracleTestBase;
import org.casemgmt.observation.ObservationCursor;
import org.casemgmt.observation.ObservationStream;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** Oracle proof that each remote history stream advances independently and monotonically. */
class ObservationCheckpointRepositoryTest extends OracleTestBase {

    @Test
    void retainsIndependentTenantAndStreamCursorsAtEqualTimestamps() {
        ObservationCheckpointRepository checkpoints = new ObservationCheckpointRepository(jdbc());
        Instant at = Instant.parse("2026-08-29T10:00:00Z");

        checkpoints.advance("tenant-a", ObservationStream.TASKS,
                new ObservationCursor(at, "task-0500"));
        checkpoints.advance("tenant-a", ObservationStream.ACTIVITIES,
                new ObservationCursor(at, "activity-0500"));
        checkpoints.advance("tenant-a", ObservationStream.TASK_TERMINALS,
                new ObservationCursor(at, "task-terminal-0500"));
        checkpoints.advance("tenant-a", ObservationStream.ACTIVITY_TERMINALS,
                new ObservationCursor(at, "activity-terminal-0500"));
        checkpoints.advance("tenant-b", ObservationStream.TASKS,
                new ObservationCursor(at, "task-0001"));

        assertThat(checkpoints.find("tenant-a", ObservationStream.TASKS))
                .contains(new ObservationCursor(at, "task-0500"));
        assertThat(checkpoints.find("tenant-a", ObservationStream.ACTIVITIES))
                .contains(new ObservationCursor(at, "activity-0500"));
        assertThat(checkpoints.find("tenant-a", ObservationStream.TASK_TERMINALS))
                .contains(new ObservationCursor(at, "task-terminal-0500"));
        assertThat(checkpoints.find("tenant-a", ObservationStream.ACTIVITY_TERMINALS))
                .contains(new ObservationCursor(at, "activity-terminal-0500"));
        assertThat(checkpoints.find("tenant-b", ObservationStream.TASKS))
                .contains(new ObservationCursor(at, "task-0001"));
    }

    @Test
    void never_movesACursorBackwardsDuringOverlapReplay() {
        ObservationCheckpointRepository checkpoints = new ObservationCheckpointRepository(jdbc());
        Instant at = Instant.parse("2026-08-29T10:00:00Z");
        checkpoints.advance("tenant-a", ObservationStream.TASKS,
                new ObservationCursor(at, "task-0501"));

        checkpoints.advance("tenant-a", ObservationStream.TASKS,
                new ObservationCursor(at, "task-0500"));

        assertThat(checkpoints.find("tenant-a", ObservationStream.TASKS))
                .contains(new ObservationCursor(at, "task-0501"));
    }
}
