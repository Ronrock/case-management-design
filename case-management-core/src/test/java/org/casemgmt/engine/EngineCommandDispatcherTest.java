package org.casemgmt.engine;

import org.casemgmt.OracleTestBase;
import org.casemgmt.repo.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EngineCommandDispatcherTest extends OracleTestBase {

    private EngineCommandRepository commands;

    @BeforeEach
    void setUp() {
        jdbc().sql("DELETE FROM CM_ENGINE_COMMAND").update();
        commands = new EngineCommandRepository(jdbc());
    }

    @Test
    void outboxGatewayEnqueuesInsteadOfCalling() {
        var outbox = new OutboxEngineGateway(commands, id -> {});

        EngineTaskRef ref = outbox.createHumanTask(new HumanTaskRequest(
                "eng-a:1", "pi-1", "Review", null, List.of("g"), null, Map.of()));

        // No engine id yet: the dispatcher supplies it after the engine confirms.
        assertThat(ref.engineTaskId()).isNull();
        assertThat(commands.claimDue(10)).hasSize(1)
                .allSatisfy(c -> assertThat(c.type()).isEqualTo(EngineCommand.Type.CREATE_TASK));
    }

    @Test
    void outboxGatewayNeverTouchesTheEngineOnTheRequestThread() {
        // ExplodingGateway fails the test if any engine call happens synchronously.
        var outbox = new OutboxEngineGateway(commands, id -> {});
        outbox.createHumanTask(new HumanTaskRequest("eng-a:9", "pi-9", "Review",
                null, List.of("g"), null, Map.of()));
        outbox.completeTask("engine-1", Map.of());
        outbox.cancelProcess("proc-1", "reason");

        // Nothing was delivered because no dispatcher ran.
        assertThat(new EngineCommandDispatcher(commands, new ExplodingGateway(), (t, s, e) -> {}))
                .isNotNull();
        assertThat(commands.claimDue(10)).hasSize(3);
    }

    static class ExplodingGateway extends RecordingGateway {
        @Override public EngineTaskRef createHumanTask(HumanTaskRequest r) {
            throw new AssertionError("engine must not be called from the request thread");
        }
        @Override public void completeTask(String id, Map<String, Object> v) {
            throw new AssertionError("engine must not be called from the request thread");
        }
        @Override public void cancelProcess(String id, String reason) {
            throw new AssertionError("engine must not be called from the request thread");
        }
    }

    @Test
    void dispatcherDeliversAndMarksTheTaskSynced() {
        var syncedTasks = new java.util.ArrayList<String>();
        var outbox = new OutboxEngineGateway(commands, syncedTasks::add);
        outbox.createHumanTask(new HumanTaskRequest("eng-a:2", "pi-2", "Review",
                null, List.of(), null, Map.of()));

        var delegate = new RecordingGateway();
        int processed = new EngineCommandDispatcher(commands, delegate, (taskId, sync, engineId) ->
                syncedTasks.add(taskId)).drainOnce();

        assertThat(processed).isEqualTo(1);
        assertThat(delegate.createdTasks).hasSize(1);
        assertThat(commands.claimDue(10)).isEmpty();
    }

    @Test
    void failedCommandsAreRetriedWithBackoffThenParkedAsDead() {
        var outbox = new OutboxEngineGateway(commands, id -> {});
        outbox.createHumanTask(new HumanTaskRequest("eng-a:3", "pi-3", "Fails",
                null, List.of(), null, Map.of()));

        var failing = new FailingGateway();
        var dispatcher = new EngineCommandDispatcher(commands, failing, (t, s, e) -> {});

        for (int attempt = 1; attempt <= 6; attempt++) {
            jdbc().sql("UPDATE CM_ENGINE_COMMAND SET NEXT_ATTEMPT_AT_ = SYSTIMESTAMP - INTERVAL '1' HOUR").update();
            dispatcher.drainOnce();
        }

        String status = jdbc().sql("SELECT STATUS_ FROM CM_ENGINE_COMMAND")
                .query(String.class).single();
        assertThat(status).isEqualTo("DEAD");
    }

    static class RecordingGateway implements EngineGateway {
        final java.util.List<HumanTaskRequest> createdTasks = new java.util.ArrayList<>();
        public EngineTaskRef createHumanTask(HumanTaskRequest r) {
            createdTasks.add(r);
            return new EngineTaskRef("engine-" + createdTasks.size(), r.name(), r.assignee(), r.caseId(), null);
        }
        public void claimTask(String id, String user) {}
        public void completeTask(String id, Map<String, Object> vars) {}
        public EngineProcessRef startProcess(StartProcessRequest r) {
            return new EngineProcessRef("proc-1", r.processDefinitionKey());
        }
        public void cancelProcess(String id, String reason) {}
        public List<EngineTaskRef> findTasks(EngineTaskQuery q) { return List.of(); }
    }

    static class FailingGateway extends RecordingGateway {
        @Override public EngineTaskRef createHumanTask(HumanTaskRequest r) {
            throw new EngineException("engine is down");
        }
    }
}
