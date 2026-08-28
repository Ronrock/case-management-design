package org.casemgmt.observation;

import org.casemgmt.OracleTestBase;
import org.casemgmt.domain.CaseInstance;
import org.casemgmt.domain.CasePriority;
import org.casemgmt.domain.CaseState;
import org.casemgmt.domain.CaseTask;
import org.casemgmt.event.EventPublisher;
import org.casemgmt.projection.CaseProjectionPort;
import org.casemgmt.projection.ProjectionStatus;
import org.casemgmt.projection.TaskObservation;
import org.casemgmt.repo.AppliedObservationRepository;
import org.casemgmt.repo.CaseRepository;
import org.casemgmt.repo.LinkedProcessRepository;
import org.casemgmt.service.CaseDataMappingService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Oracle proof that distinct fingerprints re-read the watermark after the same case-row lock. */
class EngineObservationHandlerConcurrencyTest extends OracleTestBase {

    @Test
    void lowerRevisionWaitsForHigherRevisionThenFinalizesIgnoredStale() throws Exception {
        JdbcClient jdbc = jdbc();
        jdbc.sql("INSERT INTO CM_CASE_DEF (ID_, KEY_, VERSION_NO_, NAME_, ORCHESTRATION_MODE_) "
                + "VALUES ('claim:1','claim',1,'Claim','BPMN')").update();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        new CaseRepository(jdbc).insert(new CaseInstance("case-1", "engine-a", "tenant-a",
                "claim:1", "claim", 1, "business-1", "Claim", CaseState.ACTIVE,
                CasePriority.MEDIUM, null, null, "starter", "NONE", null, null, Map.of(),
                0, now, now, null, null, ProjectionStatus.CURRENT, null, now));

        AppliedObservationRepository claims = new AppliedObservationRepository(jdbc);
        CaseRepository cases = new CaseRepository(dataSource());
        LinkedProcessRepository processes = mock(LinkedProcessRepository.class);
        when(processes.findByCase("case-1")).thenReturn(List.of(
                new LinkedProcessRepository.LinkedProcessRow("link", "case-1", null,
                        "correlation", "process-1", "claim-process", "ACTIVE",
                        CaseTask.EngineSync.SYNCED, true)));
        CaseProjectionPort projections = mock(CaseProjectionPort.class);
        CountDownLatch higherReachedProjection = new CountDownLatch(1);
        CountDownLatch releaseHigher = new CountDownLatch(1);
        AtomicInteger projectionsApplied = new AtomicInteger();
        doAnswer(invocation -> {
            if (projectionsApplied.incrementAndGet() == 1) {
                higherReachedProjection.countDown();
                assertThat(releaseHigher.await(10, TimeUnit.SECONDS)).isTrue();
            }
            return null;
        }).when(projections).observe(any(TaskObservation.class));
        DefaultEngineObservationHandler handler = new DefaultEngineObservationHandler(
                claims, cases, processes, projections, mock(CaseDataMappingService.class),
                mock(EventPublisher.class), mock(SlaLifecyclePort.class),
                mock(EngineObservationAuthorityValidator.class),
                mock(ObservationSecurityTelemetry.class));
        TransactionTemplate transaction = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource()));
        UserTaskObservation higher = task("higher", 2L, Instant.parse("2026-08-28T08:30:02Z"));
        UserTaskObservation lower = task("lower", 1L, Instant.parse("2026-08-28T08:30:01Z"));

        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> transaction.execute(status -> handler.apply(higher)));
            assertThat(higherReachedProjection.await(10, TimeUnit.SECONDS)).isTrue();
            var second = pool.submit(() -> transaction.execute(status -> handler.apply(lower)));
            releaseHigher.countDown();

            assertThat(first.get(10, TimeUnit.SECONDS).status()).isEqualTo(ApplyStatus.APPLIED);
            assertThat(second.get(10, TimeUnit.SECONDS).status())
                    .isEqualTo(ApplyStatus.IGNORED_STALE);
        } finally {
            releaseHigher.countDown();
        }
        assertThat(projectionsApplied).hasValue(1);
    }

    private static UserTaskObservation task(String id, Long revision, Instant occurredAt) {
        return new UserTaskObservation(id, 1, "adapter:embedded", "tenant-a", "case-1",
                "process-1", "task-1", revision, UserTaskObservation.EventType.CREATED,
                occurredAt, occurredAt.plusSeconds(1), Map.of(
                "engineId", "engine-a",
                "processDefinitionId", "claim-process:7",
                "processDefinitionKey", "claim-process",
                "taskDefinitionKey", "review"));
    }
}
