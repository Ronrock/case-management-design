package org.casemgmt.sla;

import org.casemgmt.OracleTestBase;
import org.casemgmt.domain.CaseTask;
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
import org.casemgmt.repo.JsonCodec;
import org.casemgmt.repo.LinkedProcessRepository;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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
        sla.insertCalendarRevision("tenant-a", "cal-1", 1, "Calendar v1", alwaysOpenCalendar());
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
    void publishedBindingWithOnlyExactCalendarRevisionCreatesSnapshottedOccurrence() {
        jdbc().sql("DELETE FROM CM_SLA_RECORD").update();
        jdbc().sql("DELETE FROM CM_SLA_TARGET").update();
        jdbc().sql("DELETE FROM CM_SLA_POLICY").update();
        jdbc().sql("DELETE FROM CM_BUSINESS_CALENDAR").update();
        assertThat(jdbc().sql("SELECT COUNT(*) FROM CM_BUSINESS_CALENDAR WHERE ID_ = 'cal-1'")
                .query(Integer.class).single()).isZero();
        String contract = """
                {"key":"sla-root","orchestrationMode":"BPMN","fields":{},"forms":{},
                 "slaBindings":{
                   "resolution":{"scope":"TASK","calendarId":"cal-1",
                     "calendarRevision":1,"targetVersion":3,"duration":"PT1H",
                     "startAnchor":"USER_TASK_CREATED","meetAnchor":"USER_TASK_COMPLETED",
                     "cancelAnchor":"USER_TASK_DELETED","pauseAnchors":["USER_TASK_CLAIMED"],
                     "resumeAnchors":["USER_TASK_UNCLAIMED"],"warnings":["PT30M"]},
                   "other-task":{"scope":"TASK","calendarId":"cal-1",
                     "calendarRevision":1,"targetVersion":1,"duration":"PT2H",
                     "startAnchor":"USER_TASK_CREATED","meetAnchor":"USER_TASK_COMPLETED"}
                 }}""";
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
        sla.insertCalendarRevision("tenant-a", "cal-1", 2, "Calendar v2",
                Map.of("timezone", "UTC", "workingHours", Map.of(
                        "MONDAY", List.of(Map.of("from", "09:00", "to", "17:00")))));
        SlaLifecycleService contractLifecycle = new SlaLifecycleService(sla, new CaseRepository(jdbc()),
                publisher(), new CaseDefinitionVersionBindingRepository(dataSource()), releases,
                new JsonSchemaCaseContractValidator());
        Instant started = Instant.parse("2026-08-30T10:00:00Z");

        contractLifecycle.observeAnchor(new SlaLifecyclePort.Anchor(CASE_ID, "user-task", "CREATED",
                "task-1", "resolution", started));
        contractLifecycle.observeAnchor(new SlaLifecyclePort.Anchor(CASE_ID, "user-task", "CREATED",
                "task-1", "resolution", started));

        assertThat(jdbc().sql("SELECT COUNT(*) FROM CM_SLA_RECORD WHERE CONTRACT_RELEASE_ID_ = 'contract-sla-root'")
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc().sql("""
                SELECT COUNT(*) FROM CM_SLA_RECORD R
                JOIN CM_SLA_TARGET T ON T.ID_ = R.TARGET_ID_
                JOIN CM_SLA_POLICY P ON P.ID_ = T.POLICY_ID_
                WHERE R.CONTRACT_RELEASE_ID_ = 'contract-sla-root'
                  AND P.CALENDAR_ID_ IS NULL""").query(Integer.class).single()).isEqualTo(1);
        var snapshot = jdbc().sql("""
                SELECT TARGET_KEY_, TARGET_VERSION_, SLA_SCOPE_, OCCURRENCE_KEY_, CALENDAR_REVISION_,
                       CALENDAR_SHA256_, CALENDAR_DEFINITION_JSON_, TRANSITION_EVIDENCE_JSON_
                FROM CM_SLA_RECORD WHERE CONTRACT_RELEASE_ID_ = 'contract-sla-root'""")
                .query((rs, n) -> List.of(rs.getString(1), rs.getInt(2), rs.getString(3),
                        rs.getString(4), rs.getInt(5), rs.getString(6), rs.getString(7),
                        rs.getString(8))).single();
        assertThat(snapshot.subList(0, 5)).containsExactly(
                "resolution", 3, "TASK", "task-1", 1);
        assertThat(snapshot.get(5)).isEqualTo(JsonCodec.canonicalSha256(alwaysOpenCalendar()));
        assertThat(snapshot.get(6)).isEqualTo(JsonCodec.canonicalJson(alwaysOpenCalendar()));
        assertThat(String.valueOf(snapshot.get(7))).contains("\"anchor\":\"USER_TASK_CREATED\"")
                .contains("\"occurredAt\":\"2026-08-30T10:00:00Z\"")
                .contains("\"transition\":\"STARTED\"");
        assertThat(jdbc().sql("SELECT COUNT(*) FROM CM_EVENT WHERE TYPE_ LIKE '%sla.started'")
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc().sql("SELECT COUNT(*) FROM CM_AUDIT_LOG WHERE ACTION_ = 'sla.start'")
                .query(Integer.class).single()).isEqualTo(1);

        contractLifecycle.observeAnchor(new SlaLifecyclePort.Anchor(CASE_ID, "user-task", "CLAIMED",
                "task-1", "resolution", Instant.parse("2026-08-30T10:10:00Z")));
        contractLifecycle.observeAnchor(new SlaLifecyclePort.Anchor(CASE_ID, "user-task", "CLAIMED",
                "task-1", "resolution", Instant.parse("2026-08-30T10:10:00Z")));
        assertThat(jdbc().sql("SELECT STATUS_ FROM CM_SLA_RECORD WHERE CONTRACT_RELEASE_ID_ = 'contract-sla-root'")
                .query(String.class).single()).isEqualTo("PAUSED");
        assertThat(jdbc().sql("SELECT COUNT(*) FROM CM_EVENT WHERE TYPE_ LIKE '%sla.paused'")
                .query(Integer.class).single()).isEqualTo(1);

        // A later edit to the calendar must not rewrite a clock that is already governed by
        // the published revision captured at start. An empty calendar would make a mutable
        // lookup fail to calculate the remaining business time at resume.
        contractLifecycle.observeAnchor(new SlaLifecyclePort.Anchor(CASE_ID, "user-task", "UNCLAIMED",
                "task-1", "resolution", Instant.parse("2026-08-30T10:20:00Z")));
        contractLifecycle.observeAnchor(new SlaLifecyclePort.Anchor(CASE_ID, "user-task", "UNCLAIMED",
                "task-1", "resolution", Instant.parse("2026-08-30T10:20:00Z")));
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
                            .contains("\"anchor\":\"USER_TASK_UNCLAIMED\"");
                });
        assertThat(jdbc().sql("SELECT COUNT(*) FROM CM_EVENT WHERE TYPE_ LIKE '%sla.resumed'")
                .query(Integer.class).single()).isEqualTo(1);

        contractLifecycle.observeAnchor(new SlaLifecyclePort.Anchor(CASE_ID, "user-task", "COMPLETED",
                "task-1", "resolution", Instant.parse("2026-08-30T10:30:00Z")));

        assertThat(jdbc().sql("SELECT STATUS_ FROM CM_SLA_RECORD WHERE CONTRACT_RELEASE_ID_ = 'contract-sla-root'")
                .query(String.class).single()).isEqualTo("MET");

        contractLifecycle.observeAnchor(new SlaLifecyclePort.Anchor(CASE_ID, "user-task", "CREATED",
                "task-2", "resolution", Instant.parse("2026-08-30T11:00:00Z")));
        contractLifecycle.observeAnchor(new SlaLifecyclePort.Anchor(CASE_ID, "user-task", "DELETED",
                "task-2", "resolution", Instant.parse("2026-08-30T11:05:00Z")));

        assertThat(jdbc().sql("""
                SELECT STATUS_ FROM CM_SLA_RECORD
                WHERE CONTRACT_RELEASE_ID_ = 'contract-sla-root' AND OCCURRENCE_KEY_ = 'task-2'""")
                .query(String.class).single()).isEqualTo("CANCELLED");
    }

    @Test
    void occurrenceTransitionsAffectOnlyTheMatchingEntityOccurrence() {
        String contract = """
                {"key":"sla-root","orchestrationMode":"BPMN","fields":{},"forms":{},
                 "slaBindings":{
                   "review-occurrence":{"scope":"OCCURRENCE","occurrenceKey":"taskInstance",
                     "calendarId":"cal-1","calendarRevision":1,"duration":"PT1H",
                     "startAnchor":"USER_TASK_CREATED","meetAnchor":"USER_TASK_COMPLETED",
                     "cancelAnchor":"USER_TASK_DELETED","pauseAnchors":["USER_TASK_CLAIMED"],
                     "resumeAnchors":["USER_TASK_UNCLAIMED"]}
                 }}""";
        SlaLifecycleService contractLifecycle = contractLifecycle(contract);

        contractLifecycle.observeAnchor(taskAnchor("CREATED", "task-a", "review-occurrence",
                "2026-08-30T10:00:00Z"));
        contractLifecycle.observeAnchor(taskAnchor("CREATED", "task-b", "review-occurrence",
                "2026-08-30T10:01:00Z"));

        assertThat(contractOccurrenceStatuses()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "taskInstance:task-a", "RUNNING",
                "taskInstance:task-b", "RUNNING"));

        contractLifecycle.observeAnchor(taskAnchor("CLAIMED", "task-a", "review-occurrence",
                "2026-08-30T10:10:00Z"));

        assertThat(contractOccurrenceStatuses()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "taskInstance:task-a", "PAUSED",
                "taskInstance:task-b", "RUNNING"));

        contractLifecycle.observeAnchor(taskAnchor("UNCLAIMED", "task-a", "review-occurrence",
                "2026-08-30T10:20:00Z"));
        contractLifecycle.observeAnchor(taskAnchor("COMPLETED", "task-a", "review-occurrence",
                "2026-08-30T10:30:00Z"));

        assertThat(contractOccurrenceStatuses()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "taskInstance:task-a", "MET",
                "taskInstance:task-b", "RUNNING"));

        contractLifecycle.observeAnchor(taskAnchor("DELETED", "task-b", "review-occurrence",
                "2026-08-30T10:40:00Z"));

        assertThat(contractOccurrenceStatuses()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "taskInstance:task-a", "MET",
                "taskInstance:task-b", "CANCELLED"));
    }

    @ParameterizedTest
    @CsvSource({"COMPLETED,MET", "TERMINATED,CANCELLED"})
    void caseAnchorsUseOnlyTheRetainedRootProcess(String terminalEvent,
                                                   String terminalStatus) {
        String contract = """
                {"key":"sla-root","orchestrationMode":"BPMN","fields":{},"forms":{},
                 "slaBindings":{
                   "case-clock":{"scope":"CASE","calendarId":"cal-1","calendarRevision":1,
                     "duration":"PT1H","startAnchor":"CASE_CREATED",
                     "meetAnchor":"CASE_CLOSED","cancelAnchor":"CASE_CANCELLED"}
                 }}""";
        SlaLifecycleService contractLifecycle = contractLifecycle(contract);
        var processes = new LinkedProcessRepository(jdbc());
        processes.insertRoot("root-link", CASE_ID, "root-process", "proc:1", "sla-root",
                CaseTask.EngineSync.SYNCED);
        processes.insert("child-link", CASE_ID, null, "child-process", "child:1", "child",
                CaseTask.EngineSync.SYNCED);

        contractLifecycle.observeAnchor(processAnchor("STARTED", "child-process",
                "2026-08-30T10:00:00Z"));

        assertThat(contractOccurrenceStatuses()).isEmpty();

        contractLifecycle.observeAnchor(processAnchor("STARTED", "root-process",
                "2026-08-30T10:01:00Z"));
        assertThat(contractOccurrenceStatuses()).containsOnly(Map.entry("CASE", "RUNNING"));

        contractLifecycle.observeAnchor(processAnchor(terminalEvent, "child-process",
                "2026-08-30T10:10:00Z"));
        assertThat(contractOccurrenceStatuses()).containsOnly(Map.entry("CASE", "RUNNING"));

        contractLifecycle.observeAnchor(processAnchor(terminalEvent, "root-process",
                "2026-08-30T10:20:00Z"));
        assertThat(contractOccurrenceStatuses()).containsOnly(Map.entry("CASE", terminalStatus));
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

    private SlaLifecycleService contractLifecycle(String contract) {
        String sha = JsonCodec.sha256(contract);
        var releases = new CaseDefinitionReleaseRepository(dataSource());
        releases.insert(CaseDefinitionRelease.storedWithEngineIdentity("orch", "sla-root", "tenant-a",
                ReleaseKind.ORCHESTRATION, "application/xml",
                "<definitions/>".getBytes(StandardCharsets.UTF_8), "a".repeat(64),
                ReleaseStatus.ACTIVE, new EngineDeploymentIdentity("dep", "proc:1", "sla-root", 1,
                        "tenant-a"), null, "tester"));
        releases.insert(CaseDefinitionRelease.stored("contract-sla-root", "sla-root", "tenant-a",
                ReleaseKind.CONTRACT, "application/json", contract.getBytes(StandardCharsets.UTF_8),
                sha, ReleaseStatus.ACTIVE, null, null, "tester"));
        releases.insert(CaseDefinitionRelease.stored("presentation", "sla-root", "tenant-a",
                ReleaseKind.PRESENTATION, "application/json",
                "{}".getBytes(StandardCharsets.UTF_8), "b".repeat(64),
                ReleaseStatus.ACTIVE, null, null, "tester"));
        var bindings = new CaseDefinitionVersionBindingRepository(dataSource());
        bindings.insert(new CaseDefinitionVersionBinding(
                "sla-root:1", "sla-root", "tenant-a", "orch", "a".repeat(64),
                "contract-sla-root", sha, "presentation", "b".repeat(64), ReleaseStatus.ACTIVE,
                OrchestrationMode.BPMN, BindingStatus.ACTIVE,
                new EngineDeploymentIdentity("dep", "proc:1", "sla-root", 1, "tenant-a"), null,
                OffsetDateTime.now(), OffsetDateTime.now(), null, "tester"));
        return new SlaLifecycleService(sla, new CaseRepository(jdbc()), publisher(), bindings,
                releases, new JsonSchemaCaseContractValidator());
    }

    private static SlaLifecyclePort.Anchor taskAnchor(String eventType, String entityId,
                                                       String targetId, String occurredAt) {
        return new SlaLifecyclePort.Anchor(CASE_ID, "user-task", eventType, entityId, targetId,
                Instant.parse(occurredAt));
    }

    private static SlaLifecyclePort.Anchor processAnchor(String eventType, String entityId,
                                                          String occurredAt) {
        return new SlaLifecyclePort.Anchor(CASE_ID, "process", eventType, entityId, null,
                Instant.parse(occurredAt));
    }

    private Map<String, String> contractOccurrenceStatuses() {
        return jdbc().sql("""
                SELECT OCCURRENCE_KEY_, STATUS_ FROM CM_SLA_RECORD
                WHERE CONTRACT_RELEASE_ID_ = 'contract-sla-root'""")
                .query((rs, n) -> Map.entry(rs.getString(1), rs.getString(2))).list().stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
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
