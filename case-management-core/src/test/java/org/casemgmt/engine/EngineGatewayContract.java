package org.casemgmt.engine;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * One suite, both implementations (spec §9). If embedded and remote disagree here,
 * the interface is lying about one of them.
 *
 * <p>The rule this file enforces: every behaviour {@link EngineGateway} promises must be
 * asserted here, not merely exercised. A field or effect that is touched but never checked
 * is exactly where embedded and remote are free to silently diverge — the calls will look
 * green while one implementation quietly does less than the other (e.g. populating a field
 * on create but dropping it on read, or swallowing a parse failure into a null). If you add
 * a new capability to the interface, add its assertion here too.
 */
public abstract class EngineGatewayContract {

    protected abstract EngineGateway gateway();

    /** Deploys the test BPMN process and returns its definition key. */
    protected abstract String deployTestProcess();

    @Test
    void createsAHumanTaskCarryingTheCaseId() {
        EngineTaskRef ref = gateway().createHumanTask(new HumanTaskRequest(
                "eng-a:1", "pi-1", "Review", null, List.of("reviewers"), "reviewForm",
                Map.of("amount", 100)));

        assertThat(ref.engineTaskId()).isNotBlank();
        assertThat(ref.name()).isEqualTo("Review");
        assertThat(ref.caseId()).isEqualTo("eng-a:1");
        // createdAt must be honestly populated on create, not silently dropped or left null
        // (e.g. by a remote gateway swallowing a timestamp-parse failure).
        assertThat(ref.createdAt()).isNotNull();
        assertThat(ref.createdAt().toInstant())
                .isCloseTo(Instant.now(), within(1, ChronoUnit.MINUTES));
    }

    @Test
    void findsCreatedTasksByCandidateGroup() {
        gateway().createHumanTask(new HumanTaskRequest(
                "eng-a:2", "pi-2", "Grouped", null, List.of("special-group"), null, Map.of()));

        List<EngineTaskRef> found = gateway().findTasks(
                new EngineTaskQuery(null, List.of("special-group"), null, 10));

        assertThat(found).isNotEmpty();
        assertThat(found).allSatisfy(t -> {
            assertThat(t.engineTaskId()).isNotBlank();
            // A gateway cannot be allowed to populate createdAt on create and drop it on query.
            assertThat(t.createdAt()).isNotNull();
        });
    }

    @Test
    void findsTasksByCaseId() {
        gateway().createHumanTask(new HumanTaskRequest(
                "eng-a:3", "pi-3", "ByCase", null, List.of(), null, Map.of()));

        assertThat(gateway().findTasks(new EngineTaskQuery(null, null, "eng-a:3", 10)))
                .hasSize(1)
                .allSatisfy(t -> {
                    assertThat(t.caseId()).isEqualTo("eng-a:3");
                    assertThat(t.createdAt()).isNotNull();
                });
    }

    @Test
    void claimAssignsTheTask() {
        EngineTaskRef ref = gateway().createHumanTask(new HumanTaskRequest(
                "eng-a:4", "pi-4", "Claimable", null, List.of("reviewers"), null, Map.of()));

        gateway().claimTask(ref.engineTaskId(), "alice");

        List<EngineTaskRef> found = gateway().findTasks(
                new EngineTaskQuery("alice", null, "eng-a:4", 10));

        assertThat(found).extracting(EngineTaskRef::assignee).containsExactly("alice");
        // Claiming must not drop createdAt from the record read back afterward.
        assertThat(found).allSatisfy(t -> assertThat(t.createdAt()).isNotNull());
    }

    @Test
    void completeRemovesTheTaskFromTheWorklist() {
        EngineTaskRef ref = gateway().createHumanTask(new HumanTaskRequest(
                "eng-a:5", "pi-5", "Completable", "alice", List.of(), null, Map.of()));

        gateway().completeTask(ref.engineTaskId(), Map.of("outcome", "approve"));

        assertThat(gateway().findTasks(new EngineTaskQuery(null, null, "eng-a:5", 10))).isEmpty();
    }

    @Test
    void completingAnUnknownTaskFailsWithEngineException() {
        assertThatThrownBy(() -> gateway().completeTask("no-such-task", Map.of()))
                .isInstanceOf(EngineException.class);
    }

    @Test
    void startsAProcessCorrelatedToTheCase() {
        String key = deployTestProcess();

        EngineProcessRef ref = gateway().startProcess(new StartProcessRequest(
                "eng-a:6", "pi-6", key, Map.of("reason", "test")));

        assertThat(ref.processInstanceId()).isNotBlank();
        assertThat(ref.processDefinitionKey()).isEqualTo(key);
    }

    @Test
    void cancelsARunningProcess() {
        String key = deployTestProcess();
        EngineProcessRef ref = gateway().startProcess(new StartProcessRequest(
                "eng-a:7", "pi-7", key, Map.of()));

        gateway().cancelProcess(ref.processInstanceId(), "no longer needed");

        // Cancelling twice must fail rather than silently succeed.
        assertThatThrownBy(() -> gateway().cancelProcess(ref.processInstanceId(), "again"))
                .isInstanceOf(EngineException.class);
    }
}
