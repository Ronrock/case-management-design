package org.casemgmt.service;

import org.casemgmt.OracleTestBase;
import org.casemgmt.domain.CaseInstance;
import org.casemgmt.domain.CasePriority;
import org.casemgmt.domain.CaseState;
import org.casemgmt.domain.CaseTask;
import org.casemgmt.domain.TaskState;
import org.casemgmt.engine.EngineCommandStatus;
import org.casemgmt.event.EventPublisher;
import org.casemgmt.repo.AuditRepository;
import org.casemgmt.repo.CaseRepository;
import org.casemgmt.repo.CaseTaskRepository;
import org.casemgmt.repo.EngineCommandRepository;
import org.casemgmt.repo.EventRepository;
import org.casemgmt.repo.WebhookRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EngineOperationServiceTest extends OracleTestBase {

    @Test
    void remoteClaimPersistsCanonicalIntentWithoutMutatingTheConfirmedTask() {
        JdbcClient jdbc = jdbc();
        CaseRepository cases = new CaseRepository(jdbc);
        CaseTaskRepository tasks = new CaseTaskRepository(jdbc);
        CaseInstance instance = instance();
        CaseTask task = task();
        cases.insert(instance);
        tasks.insert(task);
        EngineCommandRepository commands = new EngineCommandRepository(dataSource());
        EventPublisher events = new EventPublisher(new EventRepository(jdbc), new AuditRepository(jdbc),
                new WebhookRepository(jdbc), "org.example.cm", "engine-a");
        EngineOperationService service = new EngineOperationService(commands, events,
                Clock.fixed(Instant.parse("2026-08-30T10:00:00Z"), ZoneOffset.UTC));

        EngineOperationService.Operation operation = service.submitClaim(instance, task, task.version(),
                new Actor("alice", List.of()), "claim-1");
        EngineOperationService.Operation replay = service.submitClaim(instance, task, task.version(),
                new Actor("alice", List.of()), "claim-1");

        assertThat(operation.status()).isEqualTo("PENDING");
        assertThat(replay.id()).isEqualTo(operation.id());
        assertThat(operation.commandType()).isEqualTo("CLAIM_TASK");
        assertThat(tasks.require(task.id())).isEqualTo(task);
        assertThat(commands.require("tenant-a", operation.id()).state().committedDecision().status())
                .isEqualTo(EngineCommandStatus.PENDING);
        assertThat(commands.require("tenant-a", operation.id()).canonicalPatchJson())
                .isEqualTo("{\"requestedAssignee\":\"alice\"}");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM CM_AUDIT_LOG WHERE ACTION_='engine-operation.requested'")
                .query(Long.class).single()).isEqualTo(1L);

        EngineOperationService.Operation cancelled = service.support("tenant-a", operation.id(),
                operation.version(), EngineOperationService.SupportAction.CANCEL,
                new Actor("ops", List.of("admin")), "cancel-1", "audit-cancel-1", null);

        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM CM_AUDIT_LOG WHERE ACTION_='engine-operation.cancel'")
                .query(Long.class).single()).isEqualTo(1L);
    }

    private static CaseInstance instance() {
        OffsetDateTime now = OffsetDateTime.parse("2026-08-30T10:00:00Z");
        return new CaseInstance("case-1", "engine-a", "tenant-a", "definition:1",
                "definition", 1, null, "Example", CaseState.ACTIVE, CasePriority.MEDIUM,
                null, null, "alice", "NONE", null, null, Map.of(), 3L, now, now, null);
    }

    private static CaseTask task() {
        OffsetDateTime now = OffsetDateTime.parse("2026-08-30T10:00:00Z");
        return new CaseTask("task-1", "case-1", "item-1", "engine-task-1", "Review",
                null, TaskState.OPEN, null, null, List.of("handlers"), null, 50, null, null,
                CaseTask.EngineSync.SYNCED, 7L, now, now, null);
    }
}
