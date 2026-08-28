package org.casemgmt.repo;

import org.casemgmt.OracleTestBase;
import org.casemgmt.domain.CaseTask;
import org.casemgmt.engine.EngineGateway;
import org.casemgmt.event.EventPublisher;
import org.casemgmt.service.LinkedProcessService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

import javax.sql.DataSource;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class LinkedProcessRootConfirmationTest extends OracleTestBase {

    private LinkedProcessRepository processes;
    private LinkedProcessService confirmations;
    private AnnotationConfigApplicationContext context;

    @BeforeEach
    void createCase() {
        jdbc().sql("""
                INSERT INTO CM_CASE_DEF (ID_, KEY_, VERSION_NO_, NAME_, ORCHESTRATION_MODE_)
                VALUES ('orders:1', 'orders', 1, 'Orders', 'BPMN')""").update();
        jdbc().sql("""
                INSERT INTO CM_CASE
                  (ID_, ENGINE_ID_, CASE_DEF_ID_, CASE_DEF_KEY_, CASE_DEF_VER_, STATE_)
                VALUES ('case-1', 'engine-a', 'orders:1', 'orders', 1, 'ACTIVE')""").update();
        processes = new LinkedProcessRepository(jdbc());
        context = springContext(ConfirmationConfig.class);
        confirmations = context.getBean(LinkedProcessService.class);
    }

    @AfterEach
    void closeContext() {
        context.close();
    }

    @Test
    void pendingRootKeepsCorrelationSeparateFromUnconfirmedEngineIdentity() {
        processes.insertRoot("root-correlation", "case-1", null, "orders",
                CaseTask.EngineSync.PENDING);

        LinkedProcessRepository.LinkedProcessRow root = processes.findByCase("case-1").getFirst();
        assertThat(root.correlationId()).isEqualTo("root-correlation");
        assertThat(root.processInstanceId()).isNull();
        assertThat(root.caseRoot()).isTrue();
        assertThat(jdbc().sql("SELECT ROOT_CORRELATION_ID_ FROM CM_CASE WHERE ID_ = 'case-1'")
                .query(String.class).single()).isEqualTo("root-correlation");
        assertThat(jdbc().sql("SELECT ROOT_PROC_INST_ID_ FROM CM_CASE WHERE ID_ = 'case-1'")
                .query(String.class).optional()).isEmpty();
    }

    @Test
    void confirmedRootWritesTheSameRealEngineIdentityToLinkAndCase() {
        processes.insertRoot("root-correlation", "case-1", null, "orders",
                CaseTask.EngineSync.PENDING);

        confirmations.confirmStarted("case-1", "root-correlation", "engine-process-42",
                OffsetDateTime.parse("2026-08-28T07:00:00Z"));

        LinkedProcessRepository.LinkedProcessRow root = processes.findByCase("case-1").getFirst();
        assertThat(root.processInstanceId()).isEqualTo("engine-process-42");
        assertThat(root.engineSync()).isEqualTo(CaseTask.EngineSync.SYNCED);
        assertThat(jdbc().sql("SELECT ROOT_PROC_INST_ID_ FROM CM_CASE WHERE ID_ = 'case-1'")
                .query(String.class).single()).isEqualTo("engine-process-42");
    }

    @Test
    void repeatedConfirmationOfTheSameIdentityIsIdempotent() {
        processes.insertRoot("root-correlation", "case-1", null, "orders",
                CaseTask.EngineSync.PENDING);
        OffsetDateTime confirmedAt = OffsetDateTime.parse("2026-08-28T07:00:00Z");

        confirmations.confirmStarted("case-1", "root-correlation", "engine-process-42", confirmedAt);
        confirmations.confirmStarted("case-1", "root-correlation", "engine-process-42", confirmedAt);

        assertThat(processes.findByCase("case-1")).singleElement()
                .extracting(LinkedProcessRepository.LinkedProcessRow::processInstanceId)
                .isEqualTo("engine-process-42");
    }

    @Test
    void nonRootConfirmationDoesNotSetTheCaseRootIdentity() {
        processes.insert("linked-correlation", "case-1", null, null, "letter-process",
                CaseTask.EngineSync.PENDING);

        confirmations.confirmStarted("case-1", "linked-correlation", "engine-process-77",
                OffsetDateTime.parse("2026-08-28T07:00:00Z"));

        assertThat(processes.findByCase("case-1").getFirst().processInstanceId())
                .isEqualTo("engine-process-77");
        assertThat(jdbc().sql("SELECT ROOT_PROC_INST_ID_ FROM CM_CASE WHERE ID_ = 'case-1'")
                .query(String.class).optional()).isEmpty();
    }

    @Test
    void confirmationPersistsExactChildDefinitionIdentity() {
        processes.insert("linked-correlation", "case-1", null, null, "letter-process",
                CaseTask.EngineSync.PENDING);

        confirmations.confirmStarted("case-1", "linked-correlation", "engine-process-77",
                "letter-process:9", "letter-process",
                OffsetDateTime.parse("2026-08-28T07:00:00Z"));

        LinkedProcessRepository.LinkedProcessRow child = processes.findByCase("case-1").getFirst();
        assertThat(child.processDefinitionId()).isEqualTo("letter-process:9");
        assertThat(child.processDefinitionKey()).isEqualTo("letter-process");
    }

    @Test
    void competingRootConfirmationRollsBackTheLinkUpdate() {
        processes.insertRoot("accepted-root", "case-1", null, "orders",
                CaseTask.EngineSync.PENDING);
        jdbc().sql("""
                INSERT INTO CM_LINKED_PROCESS
                  (ID_, CASE_ID_, CORRELATION_ID_, PROC_INST_ID_, PROC_DEF_KEY_,
                   ENGINE_SYNC_, IS_CASE_ROOT_)
                VALUES ('competing-root', 'case-1', 'competing-root', NULL, 'orders',
                        'PENDING', 1)""").update();

        assertThatThrownBy(() -> confirmations.confirmStarted(
                "case-1", "competing-root", "engine-process-other",
                OffsetDateTime.parse("2026-08-28T07:00:00Z")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not have pending root correlation");

        LinkedProcessRepository.LinkedProcessRow competing = processes
                .findByCorrelation("case-1", "competing-root").orElseThrow();
        assertThat(competing.processInstanceId()).isNull();
        assertThat(competing.engineSync()).isEqualTo(CaseTask.EngineSync.PENDING);
    }

    @Test
    void aDifferentDuplicateEngineIdentityIsRejectedWithoutOverwritingTheFirst() {
        processes.insertRoot("root-correlation", "case-1", null, "orders",
                CaseTask.EngineSync.PENDING);
        OffsetDateTime confirmedAt = OffsetDateTime.parse("2026-08-28T07:00:00Z");
        confirmations.confirmStarted("case-1", "root-correlation", "engine-process-42", confirmedAt);

        assertThatThrownBy(() -> confirmations.confirmStarted(
                "case-1", "root-correlation", "engine-process-99", confirmedAt))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("different state or engine identity");

        assertThat(processes.findByCase("case-1").getFirst().processInstanceId())
                .isEqualTo("engine-process-42");
    }

    @Configuration
    static class ConfirmationConfig {
        @Bean
        LinkedProcessService linkedProcessService(DataSource dataSource) {
            JdbcClient jdbc = JdbcClient.create(dataSource);
            return new LinkedProcessService(new LinkedProcessRepository(jdbc),
                    new CaseRepository(jdbc), mock(EngineGateway.class), mock(EventPublisher.class));
        }
    }
}
