package org.casemgmt.engine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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

    private String testPrefix;

    protected abstract EngineGateway gateway();

    /** Deploys one test BPMN version and returns the exact engine definition identity. */
    protected abstract DeployedProcess deployTestProcess(int version);

    public record DeployedProcess(String id, String key, String tenantId) {}

    @BeforeEach
    void isolateContractIds() {
        testPrefix = UUID.randomUUID().toString();
    }

    private String caseId(String suffix) {
        return "eng-a:" + testPrefix + ":" + suffix;
    }

    private String planItemId(String suffix) {
        return "pi-" + testPrefix + "-" + suffix;
    }

    private String group(String suffix) {
        return "group-" + testPrefix + "-" + suffix;
    }

    @Test
    void createsAHumanTaskCarryingTheCaseId() {
        EngineTaskRef ref = gateway().createHumanTask(new HumanTaskRequest(
                caseId("1"), planItemId("1"), "Review", null, List.of(group("reviewers")), "reviewForm",
                Map.of("amount", 100)));

        assertThat(ref.engineTaskId()).isNotBlank();
        assertThat(ref.name()).isEqualTo("Review");
        assertThat(ref.caseId()).isEqualTo(caseId("1"));
        // createdAt must be honestly populated on create, not silently dropped or left null
        // (e.g. by a remote gateway swallowing a timestamp-parse failure).
        assertThat(ref.createdAt()).isNotNull();
        assertThat(ref.createdAt().toInstant())
                .isCloseTo(Instant.now(), within(1, ChronoUnit.MINUTES));
    }

    @Test
    void findsCreatedTasksByCandidateGroup() {
        gateway().createHumanTask(new HumanTaskRequest(
                caseId("2"), planItemId("2"), "Grouped", null, List.of(group("special")), null, Map.of()));

        List<EngineTaskRef> found = gateway().findTasks(
                new EngineTaskQuery(null, List.of(group("special")), null, 10));

        assertThat(found).isNotEmpty();
        assertThat(found).allSatisfy(t -> {
            assertThat(t.engineTaskId()).isNotBlank();
            // A gateway cannot be allowed to populate createdAt on create and drop it on query.
            assertThat(t.createdAt()).isNotNull();
            // This query has NO caseId filter (null). A gateway that echoes the *query's*
            // caseId into each result (rather than reading the task's actual caseId) would
            // pass every other assertion here while silently returning null — exactly the
            // "populated on create, dropped on read" failure mode this file's class javadoc
            // warns about, and exactly the query shape (candidateGroups only) that would
            // never notice it without this line.
            assertThat(t.caseId()).isEqualTo(caseId("2"));
        });
    }

    @Test
    void findsTasksByCandidateGroupAndCaseIdTogether() {
        gateway().createHumanTask(new HumanTaskRequest(
                caseId("9"), planItemId("9"), "DualMatch", null, List.of(group("dual")), null, Map.of()));
        gateway().createHumanTask(new HumanTaskRequest(
                caseId("10"), planItemId("10"), "SameGroupOtherCase", null, List.of(group("dual")), null, Map.of()));

        // Both tasks share a candidate group; only one matches the caseId. A query combining
        // both filters must AND them, not let the caseId OR-scoping (task-variable vs.
        // process-variable, see findsTasksByCaseId / findsTheUserTaskSpawnedByAStartedProcess)
        // leak into widening the candidateGroups filter too.
        List<EngineTaskRef> found = gateway().findTasks(
                new EngineTaskQuery(null, List.of(group("dual")), caseId("9"), 10));

        assertThat(found).hasSize(1);
        assertThat(found).allSatisfy(t -> {
            assertThat(t.caseId()).isEqualTo(caseId("9"));
            assertThat(t.name()).isEqualTo("DualMatch");
        });
    }

    @Test
    void findsTasksByCaseId() {
        gateway().createHumanTask(new HumanTaskRequest(
                caseId("3"), planItemId("3"), "ByCase", null, List.of(), null, Map.of()));

        assertThat(gateway().findTasks(new EngineTaskQuery(null, null, caseId("3"), 10)))
                .hasSize(1)
                .allSatisfy(t -> {
                    assertThat(t.caseId()).isEqualTo(caseId("3"));
                    assertThat(t.createdAt()).isNotNull();
                });
    }

    @Test
    void claimAssignsTheTask() {
        EngineTaskRef ref = gateway().createHumanTask(new HumanTaskRequest(
                caseId("4"), planItemId("4"), "Claimable", null, List.of(group("reviewers")), null, Map.of()));

        gateway().claimTask(ref.engineTaskId(), "alice");

        List<EngineTaskRef> found = gateway().findTasks(
                new EngineTaskQuery("alice", null, caseId("4"), 10));

        assertThat(found).extracting(EngineTaskRef::assignee).containsExactly("alice");
        // Claiming must not drop createdAt from the record read back afterward.
        assertThat(found).allSatisfy(t -> assertThat(t.createdAt()).isNotNull());
    }

    @Test
    void completeRemovesTheTaskFromTheWorklist() {
        EngineTaskRef ref = gateway().createHumanTask(new HumanTaskRequest(
                caseId("5"), planItemId("5"), "Completable", "alice", List.of(), null, Map.of()));

        gateway().completeTask(ref.engineTaskId(), Map.of("outcome", "approve"));

        assertThat(gateway().findTasks(new EngineTaskQuery(null, null, caseId("5"), 10))).isEmpty();
    }

    @Test
    void completingAnUnknownTaskFailsWithEngineException() {
        assertThatThrownBy(() -> gateway().completeTask("no-such-task", Map.of()))
                .isInstanceOf(EngineException.class);
    }

    @Test
    void startsAProcessCorrelatedToTheCase() {
        DeployedProcess process = deployTestProcess(1);

        EngineProcessRef ref = gateway().startProcess(new StartProcessRequest(
                caseId("6"), planItemId("6"), process.id(), process.key(), process.tenantId(),
                Map.of("reason", "test"), null));

        assertThat(ref.processInstanceId()).isNotBlank();
        assertThat(ref.processDefinitionKey()).isEqualTo(process.key());
        assertThat(ref.businessKey()).isEqualTo(caseId("6"));
    }

    @Test
    void startsTheSelectedV1AfterV2ExistsUnderTheSameKey() {
        DeployedProcess v1 = deployTestProcess(1);
        DeployedProcess v2 = deployTestProcess(2);
        assertThat(v1.key()).isEqualTo(v2.key());
        assertThat(v1.id()).isNotEqualTo(v2.id());

        gateway().startProcess(new StartProcessRequest(
                caseId("exact-v1"), planItemId("exact-v1"), v1.id(), v1.key(), v1.tenantId(),
                Map.of(), null));

        assertThat(gateway().findTasks(new EngineTaskQuery(
                null, null, caseId("exact-v1"), 10)))
                .extracting(EngineTaskRef::name)
                .containsExactly("Wait v1");
    }

    @Test
    void startsTheSelectedV2WhenBothVersionsExist() {
        deployTestProcess(1);
        DeployedProcess v2 = deployTestProcess(2);

        gateway().startProcess(new StartProcessRequest(
                caseId("exact-v2"), planItemId("exact-v2"), v2.id(), v2.key(), v2.tenantId(),
                Map.of(), null));

        assertThat(gateway().findTasks(new EngineTaskQuery(
                null, null, caseId("exact-v2"), 10)))
                .extracting(EngineTaskRef::name)
                .containsExactly("Wait v2");
    }

    @Test
    void explicitStartByKeySelectsTheLatestVersionForLinkedProcesses() {
        DeployedProcess v1 = deployTestProcess(1);
        DeployedProcess v2 = deployTestProcess(2);
        assertThat(v1.key()).isEqualTo(v2.key());

        gateway().startProcessByKey(new StartProcessByKeyRequest(
                caseId("legacy-key"), planItemId("legacy-key"), v1.key(), Map.of(), null));

        assertThat(gateway().findTasks(new EngineTaskQuery(
                null, null, caseId("legacy-key"), 10)))
                .extracting(EngineTaskRef::name)
                .containsExactly("Wait v2");
    }

    @Test
    void findsTheUserTaskSpawnedByAStartedProcess() {
        DeployedProcess process = deployTestProcess(1);

        gateway().startProcess(new StartProcessRequest(
                caseId("8"), planItemId("8"), process.id(), process.key(), process.tenantId(),
                Map.of(), null));

        // test-process.bpmn's caseId lands as a *process* variable (it is passed to
        // startProcess, not createHumanTask), which is a different variable scope than a
        // standalone task's local variables. A findTasks(caseId) that only checks task-local
        // scope silently returns nothing here — exactly the gap a first draft of an
        // EngineGateway can ship with while every other test in this file stays green.
        List<EngineTaskRef> found = gateway().findTasks(
                new EngineTaskQuery(null, null, caseId("8"), 10));

        assertThat(found).hasSize(1);
        assertThat(found).allSatisfy(t -> {
                    assertThat(t.name()).isEqualTo("Wait v1");
            assertThat(t.caseId()).isEqualTo(caseId("8"));
            assertThat(t.createdAt()).isNotNull();
        });
    }

    @Test
    void cancelsARunningProcess() {
        DeployedProcess process = deployTestProcess(1);
        EngineProcessRef ref = gateway().startProcess(new StartProcessRequest(
                caseId("7"), planItemId("7"), process.id(), process.key(), process.tenantId(),
                Map.of(), null));

        gateway().cancelProcess(ref.processInstanceId(), "no longer needed");

        // Cancelling twice must fail rather than silently succeed.
        assertThatThrownBy(() -> gateway().cancelProcess(ref.processInstanceId(), "again"))
                .isInstanceOf(EngineException.class);
    }
}
