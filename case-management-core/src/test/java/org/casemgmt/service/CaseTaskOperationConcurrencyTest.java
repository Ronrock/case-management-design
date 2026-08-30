package org.casemgmt.service;

import org.casemgmt.OracleTestBase;
import org.casemgmt.domain.CaseInstance;
import org.casemgmt.domain.CasePriority;
import org.casemgmt.domain.CaseState;
import org.casemgmt.domain.CaseTask;
import org.casemgmt.domain.PlanItem;
import org.casemgmt.domain.PlanItemState;
import org.casemgmt.domain.PlanItemType;
import org.casemgmt.domain.TaskState;
import org.casemgmt.engine.EngineGateway;
import org.casemgmt.event.EventPublisher;
import org.casemgmt.repo.AuditRepository;
import org.casemgmt.repo.CaseDefinitionRepository;
import org.casemgmt.repo.CaseRepository;
import org.casemgmt.repo.CaseTaskRepository;
import org.casemgmt.repo.EngineCommandRepository;
import org.casemgmt.repo.EventRepository;
import org.casemgmt.repo.PlanItemRepository;
import org.casemgmt.repo.WebhookRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

import javax.sql.DataSource;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Oracle proof that a remote task has exactly one active command at a time. */
class CaseTaskOperationConcurrencyTest extends OracleTestBase {

    private AnnotationConfigApplicationContext context;
    private CaseTaskService service;
    private EngineCommandRepository commands;

    @BeforeEach
    void setUp() {
        context = springContext(Config.class);
        service = context.getBean(CaseTaskService.class);
        commands = context.getBean(EngineCommandRepository.class);
        seed();
    }

    @AfterEach
    void closeContext() {
        context.close();
    }

    @Test
    void concurrentDifferentKeysAcceptExactlyOneRemoteClaimCommand() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
            Future<CaseTaskService.TaskOperation> first = workers.submit(() -> claimWhenStarted(start, "claim-a"));
            Future<CaseTaskService.TaskOperation> second = workers.submit(() -> claimWhenStarted(start, "claim-b"));
            start.countDown();

            List<Future<CaseTaskService.TaskOperation>> results = List.of(first, second);
            long accepted = results.stream().filter(this::accepted).count();
            long conflicts = results.stream().filter(this::operationPending).count();

            assertThat(accepted).isEqualTo(1);
            assertThat(conflicts).isEqualTo(1);
            assertThat(commands.countCommands()).isEqualTo(1L);
        }
    }

    private CaseTaskService.TaskOperation claimWhenStarted(CountDownLatch start, String key) throws Exception {
        start.await(10, TimeUnit.SECONDS);
        return service.claimOperation("task-1", 0L, new Actor("alice", List.of("handlers")), key);
    }

    private boolean accepted(Future<CaseTaskService.TaskOperation> result) {
        try {
            return result.get(10, TimeUnit.SECONDS).operation() != null;
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean operationPending(Future<CaseTaskService.TaskOperation> result) {
        try {
            result.get(10, TimeUnit.SECONDS);
            return false;
        } catch (ExecutionException failure) {
            return failure.getCause() instanceof org.casemgmt.error.CaseConflictException conflict
                    && "operation-pending".equals(conflict.code());
        } catch (Exception ignored) {
            return false;
        }
    }

    private void seed() {
        OffsetDateTime now = OffsetDateTime.parse("2026-08-30T10:00:00Z");
        jdbc().sql("""
                INSERT INTO CM_CASE_DEF (ID_, KEY_, VERSION_NO_, TENANT_ID_, NAME_, ORCHESTRATION_MODE_)
                VALUES ('tenant-a:definition:1', 'definition', 1, 'tenant-a', 'Definition', 'BPMN')
                """).update();
        context.getBean(CaseRepository.class).insert(new CaseInstance("case-1", "engine-a", "tenant-a",
                "tenant-a:definition:1", "definition", 1, null, "Example", CaseState.ACTIVE,
                CasePriority.MEDIUM, null, null, "alice", "NONE", null, null, Map.of(), 0L, now, now, null));
        context.getBean(PlanItemRepository.class).insert(new PlanItem("item-1", "case-1", "review",
                PlanItemType.HUMAN_TASK, "Review", PlanItemState.ACTIVE, null, false, 1,
                "engine-task-1", null, null, 0L, now, now, null));
        context.getBean(CaseTaskRepository.class).insert(new CaseTask("task-1", "case-1", "item-1",
                "engine-task-1", "Review", null, TaskState.OPEN, null, null, List.of("handlers"),
                null, 50, null, null, CaseTask.EngineSync.SYNCED, 0L, now, now, null));
    }

    @Configuration(proxyBeanMethods = false)
    static class Config {
        @Bean JdbcClient jdbcClient(DataSource dataSource) { return JdbcClient.create(dataSource); }
        @Bean CaseRepository cases(DataSource dataSource) { return new CaseRepository(dataSource); }
        @Bean CaseTaskRepository tasks(JdbcClient jdbc) { return new CaseTaskRepository(jdbc); }
        @Bean PlanItemRepository planItems(JdbcClient jdbc) { return new PlanItemRepository(jdbc); }
        @Bean CaseDefinitionRepository definitions(DataSource dataSource) { return new CaseDefinitionRepository(dataSource); }
        @Bean EngineCommandRepository commands(DataSource dataSource) { return new EngineCommandRepository(dataSource); }
        @Bean EventPublisher events(JdbcClient jdbc) {
            return new EventPublisher(new EventRepository(jdbc), new AuditRepository(jdbc),
                    new WebhookRepository(jdbc), "org.example.cm", "engine-a");
        }
        @Bean EngineOperationService operations(EngineCommandRepository commands, EventPublisher events) {
            return new EngineOperationService(commands, events);
        }
        @Bean EngineGateway engine() { return new DeferredEngineGateway(); }
        @Bean FormValidator forms() { return new FormValidator(); }
        @Bean CaseTaskService service(CaseTaskRepository tasks, CaseRepository cases,
                                      CaseDefinitionRepository definitions, EngineGateway engine,
                                      FormValidator forms, EventPublisher events,
                                      EngineOperationService operations) {
            return new CaseTaskService(tasks, cases, definitions, engine, forms, events, operations);
        }
    }

    static class DeferredEngineGateway implements EngineGateway {
        @Override public boolean defersTaskMutations() { return true; }
        @Override public org.casemgmt.engine.EngineTaskRef createHumanTask(org.casemgmt.engine.HumanTaskRequest request) { throw new UnsupportedOperationException(); }
        @Override public void claimTask(String engineTaskId, String userId) { throw new UnsupportedOperationException(); }
        @Override public void completeTask(String engineTaskId, Map<String, Object> variables) { throw new UnsupportedOperationException(); }
        @Override public org.casemgmt.engine.EngineProcessRef startProcess(org.casemgmt.engine.StartProcessRequest request) { throw new UnsupportedOperationException(); }
        @Override public void cancelProcess(String processInstanceId, String reason) { throw new UnsupportedOperationException(); }
        @Override public List<org.casemgmt.engine.EngineTaskRef> findTasks(org.casemgmt.engine.EngineTaskQuery query) { return List.of(); }
    }
}
