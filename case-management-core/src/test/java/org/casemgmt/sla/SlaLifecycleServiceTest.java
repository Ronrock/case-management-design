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
import org.casemgmt.repo.EventRepository;
import org.casemgmt.repo.SlaRepository;
import org.casemgmt.repo.WebhookRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

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
        sla.insertCalendar("cal-1", Map.of());
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

    private EventPublisher publisher() {
        return new EventPublisher(new EventRepository(jdbc()), new AuditRepository(jdbc()),
                new WebhookRepository(jdbc()), "org.example.cm", "engine-a");
    }
}
