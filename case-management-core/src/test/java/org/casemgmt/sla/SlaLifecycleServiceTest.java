package org.casemgmt.sla;

import org.casemgmt.OracleTestBase;
import org.casemgmt.domain.CaseInstance;
import org.casemgmt.domain.CasePriority;
import org.casemgmt.domain.CaseState;
import org.casemgmt.error.OptimisticLockException;
import org.casemgmt.event.EventPublisher;
import org.casemgmt.observation.SlaLifecyclePort;
import org.casemgmt.projection.ProjectionStatus;
import org.casemgmt.repo.AuditRepository;
import org.casemgmt.repo.CaseRepository;
import org.casemgmt.repo.CaseDefinitionReleaseRepository;
import org.casemgmt.repo.CaseDefinitionVersionBindingRepository;
import org.casemgmt.repo.EventRepository;
import org.casemgmt.repo.SlaRepository;
import org.casemgmt.repo.WebhookRepository;
import org.casemgmt.release.BindingStatus;
import org.casemgmt.release.CaseDefinitionRelease;
import org.casemgmt.release.CaseDefinitionVersionBinding;
import org.casemgmt.release.JsonSchemaCaseContractValidator;
import org.casemgmt.release.ReleaseKind;
import org.casemgmt.release.ReleaseStatus;
import org.casemgmt.orchestration.OrchestrationMode;
import org.casemgmt.orchestration.EngineDeploymentIdentity;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Oracle proof for the production root-completion SLA effect (review comment 7). */
class SlaLifecycleServiceTest extends OracleTestBase {

    private static final String CASE_ID = "case-sla-root";
    private SlaRepository sla;
    private SlaLifecycleService lifecycle;

    @BeforeEach
    void setUp() {
        OffsetDateTime now = OffsetDateTime.now().minusMinutes(5);
        jdbc().sql("""
                INSERT INTO CM_CASE_DEF (ID_, KEY_, VERSION_NO_, TENANT_ID_, NAME_, ORCHESTRATION_MODE_)
                VALUES ('sla-root:1', 'sla-root', 1, 'tenant-a', 'SLA root', 'BPMN')""").update();
        new CaseRepository(jdbc()).insert(new CaseInstance(CASE_ID, "engine-a", "tenant-a",
                "sla-root:1", "sla-root", 1, "business-1", "SLA root", CaseState.ACTIVE,
                CasePriority.MEDIUM, null, null, "starter", "NONE", null, null, Map.of(), 0,
                now, now, null, null, ProjectionStatus.CURRENT, null, now));
        sla = new SlaRepository(jdbc());
        sla.insertCalendar("cal-1", alwaysOpenCalendar());
        sla.insertPolicy("policy-1", "Policy", null, "cal-1");
        sla.insertTarget("target-running", "policy-1", "running", "Running", "PT1H", null,
                List.of(), List.of("EMIT_EVENT"));
        sla.insertTarget("target-paused", "policy-1", "paused", "Paused", "PT1H", null,
                List.of(), List.of("EMIT_EVENT"));
        sla.insertRecord(new SlaRecord("sla-running", CASE_ID, "target-running", "RUNNING", now,
                now.plusHours(1), now.plusMinutes(30), null, null, 0, 0));
        sla.insertRecord(new SlaRecord("sla-paused", CASE_ID, "target-paused", "PAUSED", now,
                now.plusHours(1), now.plusMinutes(30), now.minusMinutes(1), "WAITING", 0, 0));
        lifecycle = new SlaLifecycleService(sla, new CaseRepository(jdbc()), publisher());
    }

    @Test
    void rootCompletionTerminalizesEveryOpenClockAsMetAndPreventsLaterSweepBreach() {
        OffsetDateTime terminalAt = OffsetDateTime.now();

        lifecycle.terminalizeRoot(CASE_ID, SlaLifecyclePort.TerminalState.COMPLETED,
                terminalAt.toInstant());

        assertThat(sla.findByCase(CASE_ID)).allSatisfy(record -> {
            assertThat(record.status()).isEqualTo("MET");
            assertThat(record.terminalAt()).isEqualTo(terminalAt);
        });
        assertThat(sla.claimDueRecords(OffsetDateTime.now().plusDays(1))).isEmpty();
    }

    @Test
    void rootCancellationCreatesOneTerminalAuditAndEventPerOccurrenceEvenWhenReplayed() {
        lifecycle.terminalizeRoot(CASE_ID, SlaLifecyclePort.TerminalState.CANCELLED,
                OffsetDateTime.now().toInstant());
        lifecycle.terminalizeRoot(CASE_ID, SlaLifecyclePort.TerminalState.CANCELLED,
                OffsetDateTime.now().plusSeconds(1).toInstant());

        assertThat(sla.findByCase(CASE_ID)).allSatisfy(record -> {
            assertThat(record.status()).isEqualTo("CANCELLED");
            assertThat(record.terminalAt()).isNotNull();
        });
        assertThat(jdbc().sql("SELECT COUNT(*) FROM CM_EVENT WHERE SUBJECT_ = :caseId")
                .param("caseId", CASE_ID).query(Integer.class).single()).isEqualTo(2);
        assertThat(jdbc().sql("SELECT COUNT(*) FROM CM_AUDIT_LOG WHERE CASE_ID_ = :caseId")
                .param("caseId", CASE_ID).query(Integer.class).single()).isEqualTo(2);
    }

    @Test
    void rootTerminalizationWinsOverAnAlreadyClaimedSweeperRowSoItCannotLaterBreach() {
        var claimed = sla.claimDueRecords(OffsetDateTime.now().plusDays(1));
        // Only RUNNING clocks are sweepable; the PAUSED occurrence remains open but is correctly
        // excluded from the sweeper and is still terminalised by the root transition below.
        assertThat(claimed).hasSize(1);
        var first = claimed.getFirst();

        lifecycle.terminalizeRoot(CASE_ID, SlaLifecyclePort.TerminalState.COMPLETED,
                OffsetDateTime.now().toInstant());

        assertThatThrownBy(() -> sla.updateClaimed(first.record(), first.record().version(),
                first.claimToken())).isInstanceOf(OptimisticLockException.class);
        assertThat(sla.findByCase(CASE_ID)).allSatisfy(record ->
                assertThat(record.status()).isEqualTo("MET"));
    }

    @Test
    void publishedBindingCreatesOneSnapshottedOccurrenceAndUsesItsDeclaredRootOutcomes() {
        String contract = """
                {"key":"sla-root","orchestrationMode":"BPMN","fields":{},"forms":{},
                 "slaBindings":{"resolution":{"scope":"CASE","calendarId":"cal-1",
                 "calendarRevision":7,"targetVersion":3,"duration":"PT1H",
                 "startAnchor":"CASE_CREATED","meetAnchor":"CASE_CLOSED",
                 "cancelAnchor":"CASE_CANCELLED","pauseAnchors":["USER_TASK_CREATED"],
                 "resumeAnchors":["USER_TASK_COMPLETED"],"warnings":["PT30M"]}}}""";
        String sha = org.casemgmt.repo.JsonCodec.sha256(contract);
        var releases = new CaseDefinitionReleaseRepository(dataSource());
        releases.insert(CaseDefinitionRelease.storedWithEngineIdentity("orch", "sla-root", "tenant-a",
                ReleaseKind.ORCHESTRATION, "application/xml", "<definitions/>".getBytes(StandardCharsets.UTF_8), "a".repeat(64),
                ReleaseStatus.ACTIVE, new EngineDeploymentIdentity("dep", "proc:1", "sla-root", 1,
                        "tenant-a"), null, "tester"));
        releases.insert(CaseDefinitionRelease.stored("contract-sla-root", "sla-root", "tenant-a",
                ReleaseKind.CONTRACT, "application/json", contract.getBytes(StandardCharsets.UTF_8), sha,
                ReleaseStatus.ACTIVE, null, null, "tester"));
        releases.insert(CaseDefinitionRelease.stored("presentation", "sla-root", "tenant-a",
                ReleaseKind.PRESENTATION, "application/json", "{}".getBytes(StandardCharsets.UTF_8),
                "b".repeat(64), ReleaseStatus.ACTIVE, null, null, "tester"));
        new CaseDefinitionVersionBindingRepository(dataSource()).insert(new CaseDefinitionVersionBinding(
                "sla-root:1", "sla-root", "tenant-a", "orch", "a".repeat(64),
                "contract-sla-root", sha, "presentation", "b".repeat(64), ReleaseStatus.ACTIVE,
                OrchestrationMode.BPMN, BindingStatus.ACTIVE,
                new EngineDeploymentIdentity("dep", "proc:1", "sla-root", 1, "tenant-a"), null,
                OffsetDateTime.now(), OffsetDateTime.now(), null, "tester"));
        SlaLifecycleService contractLifecycle = new SlaLifecycleService(sla, new CaseRepository(jdbc()),
                publisher(), new CaseDefinitionVersionBindingRepository(dataSource()), releases,
                new JsonSchemaCaseContractValidator());
        Instant started = Instant.parse("2026-08-30T10:00:00Z");

        contractLifecycle.observeAnchor(new SlaLifecyclePort.Anchor(CASE_ID, "process", "STARTED",
                "root", started));
        contractLifecycle.observeAnchor(new SlaLifecyclePort.Anchor(CASE_ID, "process", "STARTED",
                "root", started));

        assertThat(jdbc().sql("SELECT COUNT(*) FROM CM_SLA_RECORD WHERE CONTRACT_RELEASE_ID_ = 'contract-sla-root'")
                .query(Integer.class).single()).isEqualTo(1);
        var snapshot = jdbc().sql("""
                SELECT TARGET_KEY_, TARGET_VERSION_, SLA_SCOPE_, OCCURRENCE_KEY_, CALENDAR_REVISION_,
                       TRANSITION_EVIDENCE_JSON_ FROM CM_SLA_RECORD WHERE CONTRACT_RELEASE_ID_ = 'contract-sla-root'""")
                .query((rs, n) -> List.of(rs.getString(1), rs.getInt(2), rs.getString(3),
                        rs.getString(4), rs.getInt(5), rs.getString(6))).single();
        assertThat(snapshot.subList(0, 5)).containsExactly("resolution", 3, "CASE", "CASE", 7);
        assertThat(String.valueOf(snapshot.get(5))).contains("\"anchor\":\"CASE_CREATED\"")
                .contains("\"occurredAt\":\"2026-08-30T10:00:00Z\"")
                .contains("\"transition\":\"STARTED\"");
        assertThat(jdbc().sql("SELECT COUNT(*) FROM CM_EVENT WHERE TYPE_ LIKE '%sla.started'")
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc().sql("SELECT COUNT(*) FROM CM_AUDIT_LOG WHERE ACTION_ = 'sla.start'")
                .query(Integer.class).single()).isEqualTo(1);

        contractLifecycle.observeAnchor(new SlaLifecyclePort.Anchor(CASE_ID, "user-task", "CREATED",
                "task-1", Instant.parse("2026-08-30T10:10:00Z")));
        contractLifecycle.observeAnchor(new SlaLifecyclePort.Anchor(CASE_ID, "user-task", "CREATED",
                "task-1", Instant.parse("2026-08-30T10:10:00Z")));
        assertThat(jdbc().sql("SELECT STATUS_ FROM CM_SLA_RECORD WHERE CONTRACT_RELEASE_ID_ = 'contract-sla-root'")
                .query(String.class).single()).isEqualTo("PAUSED");
        assertThat(jdbc().sql("SELECT COUNT(*) FROM CM_EVENT WHERE TYPE_ LIKE '%sla.paused'")
                .query(Integer.class).single()).isEqualTo(1);

        // A later edit to the calendar must not rewrite a clock that is already governed by
        // the published revision captured at start. An empty calendar would make a mutable
        // lookup fail to calculate the remaining business time at resume.
        jdbc().sql("UPDATE CM_BUSINESS_CALENDAR SET DEFINITION_JSON_ = '{}' WHERE ID_ = 'cal-1'")
                .update();

        contractLifecycle.observeAnchor(new SlaLifecyclePort.Anchor(CASE_ID, "user-task", "COMPLETED",
                "task-1", Instant.parse("2026-08-30T10:20:00Z")));
        contractLifecycle.observeAnchor(new SlaLifecyclePort.Anchor(CASE_ID, "user-task", "COMPLETED",
                "task-1", Instant.parse("2026-08-30T10:20:00Z")));
        assertThat(jdbc().sql("""
                SELECT STATUS_, DUE_AT_, WARN_AT_, PAUSED_TOTAL_SECS_, TRANSITION_EVIDENCE_JSON_
                FROM CM_SLA_RECORD WHERE CONTRACT_RELEASE_ID_ = 'contract-sla-root'""")
                .query((rs, n) -> List.of(rs.getString(1), rs.getObject(2, OffsetDateTime.class),
                        rs.getObject(3, OffsetDateTime.class), rs.getLong(4), rs.getString(5))).single())
                .satisfies(row -> {
                    assertThat(row.get(0)).isEqualTo("RUNNING");
                    assertThat(row.get(1)).isEqualTo(OffsetDateTime.parse("2026-08-30T11:10:00Z"));
                    assertThat(row.get(2)).isEqualTo(OffsetDateTime.parse("2026-08-30T10:40:00Z"));
                    assertThat(row.get(3)).isEqualTo(600L);
                    assertThat(String.valueOf(row.get(4))).contains("\"transition\":\"RESUMED\"")
                            .contains("\"anchor\":\"USER_TASK_COMPLETED\"");
                });
        assertThat(jdbc().sql("SELECT COUNT(*) FROM CM_EVENT WHERE TYPE_ LIKE '%sla.resumed'")
                .query(Integer.class).single()).isEqualTo(1);

        contractLifecycle.terminalizeRoot(CASE_ID, SlaLifecyclePort.TerminalState.CANCELLED,
                Instant.parse("2026-08-30T10:20:00Z"));

        assertThat(jdbc().sql("SELECT STATUS_ FROM CM_SLA_RECORD WHERE CONTRACT_RELEASE_ID_ = 'contract-sla-root'")
                .query(String.class).single()).isEqualTo("CANCELLED");
    }

    @Test
    void oracleRootCompletionAndSweeperRaceUsesCaseThenSlaOrderAndCannotLeaveABreach() throws Exception {
        jdbc().sql("UPDATE CM_SLA_RECORD SET DUE_AT_ = SYSTIMESTAMP - INTERVAL '1' MINUTE "
                        + "WHERE ID_ = 'sla-running'").update();
        TransactionTemplate transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource()));
        CaseRepository lockingCases = new CaseRepository(jdbc());
        CountDownLatch rootHasCaseLock = new CountDownLatch(1);
        CountDownLatch sweeperReadCandidate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Void> root = pool.submit(() -> transactions.execute(status -> {
                lockingCases.lockForSlaLifecycle(CASE_ID);
                rootHasCaseLock.countDown();
                await(sweeperReadCandidate, "sweeper to read its due candidate");
                lifecycle.terminalizeRoot(CASE_ID, SlaLifecyclePort.TerminalState.COMPLETED,
                        Instant.parse("2026-08-30T11:00:00Z"));
                return null;
            }));
            assertThat(rootHasCaseLock.await(5, TimeUnit.SECONDS)).isTrue();
            Future<Integer> sweep = pool.submit(() -> {
                return transactions.execute(status -> {
                    SlaRecord candidate = sla.dueRecords(OffsetDateTime.now()).stream()
                            .filter(record -> record.id().equals("sla-running")).findFirst().orElseThrow();
                    sweeperReadCandidate.countDown();
                    // These are exactly SlaSweeper's production steps: obtain the shared case
                    // lock before leasing the SLA row, then let the claim predicate decide.
                    lockingCases.lockForSlaLifecycle(candidate.caseId());
                    return sla.claimDueRecord(candidate.id(), OffsetDateTime.now()).isPresent() ? 1 : 0;
                });
            });
            root.get(10, TimeUnit.SECONDS);
            assertThat(sweep.get(10, TimeUnit.SECONDS)).isZero();
            assertThat(sla.require("sla-running").status()).isEqualTo("MET");
            assertThat(sla.claimDueRecords(OffsetDateTime.now().plusDays(1))).isEmpty();
        } finally {
            pool.shutdownNow();
        }
    }

    private EventPublisher publisher() {
        return new EventPublisher(new EventRepository(jdbc()), new AuditRepository(jdbc()),
                new WebhookRepository(jdbc()), "org.example.cm", "engine-a");
    }

    private static void await(CountDownLatch latch, String description) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for " + description);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted waiting for " + description, interrupted);
        }
    }

    private static Map<String, Object> alwaysOpenCalendar() {
        List<Map<String, String>> wholeDay = List.of(Map.of("from", "00:00", "to", "23:59"));
        return Map.of("timezone", "UTC", "workingHours", Map.of(
                "MONDAY", wholeDay, "TUESDAY", wholeDay, "WEDNESDAY", wholeDay,
                "THURSDAY", wholeDay, "FRIDAY", wholeDay, "SATURDAY", wholeDay,
                "SUNDAY", wholeDay));
    }
}
