package org.casemgmt.service;

import org.casemgmt.OracleTestBase;
import org.casemgmt.domain.CaseDefinition;
import org.casemgmt.domain.CasePriority;
import org.casemgmt.engine.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Transaction proof for the supported BPMN-only case-creation path. */
class CaseServiceTransactionalIntegrationTest extends OracleTestBase {

    private AnnotationConfigApplicationContext context;
    private CaseService cases;
    private FailingGateway gateway;
    private final Actor alice = new Actor("alice", List.of("handlers"));

    @BeforeEach
    void setUp() {
        CaseDefinition definition = TestServices.deployBpmnDefinition(
                dataSource(), "transaction-case", "t1");
        TestServices.activateBpmnDefinition(dataSource(), definition);
        context = springContext(Config.class);
        cases = context.getBean(CaseService.class);
        gateway = context.getBean(FailingGateway.class);
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    @Test
    void createRollsBackCaseParticipantAndPendingRootLinkWhenEngineStartFails() {
        gateway.throwOnStart();

        assertThatThrownBy(() -> cases.create("transaction-case", "t1", null, "T",
                CasePriority.MEDIUM, Map.of(), alice))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("simulated engine start failure");

        assertCreationTablesAreEmpty();
    }

    @Test
    void createRollsBackWhenEngineAcknowledgesADifferentPinnedDefinition() {
        gateway.returnWrongDefinition();

        assertThatThrownBy(() -> cases.create("transaction-case", "t1", null, "T",
                CasePriority.MEDIUM, Map.of(), alice))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("inconsistent process-definition identity");

        assertCreationTablesAreEmpty();
    }

    private void assertCreationTablesAreEmpty() {
        assertThat(count("CM_CASE")).isZero();
        assertThat(count("CM_PARTICIPANT")).isZero();
        assertThat(count("CM_LINKED_PROCESS")).isZero();
        assertThat(count("CM_EVENT")).isZero();
        assertThat(count("CM_AUDIT_LOG")).isZero();
    }

    private int count(String table) {
        return jdbc().sql("SELECT COUNT(*) FROM " + table).query(Integer.class).single();
    }

    @Configuration(proxyBeanMethods = false)
    static class Config {
        @Bean
        FailingGateway gateway() {
            return new FailingGateway();
        }

        @Bean
        CaseService caseService(DataSource dataSource, FailingGateway gateway) {
            return TestServices.caseService(dataSource, gateway);
        }
    }

    static class FailingGateway implements EngineGateway {
        private StartBehavior behavior = StartBehavior.SUCCESS;

        void throwOnStart() {
            behavior = StartBehavior.THROW;
        }

        void returnWrongDefinition() {
            behavior = StartBehavior.WRONG_IDENTITY;
        }

        @Override
        public EngineProcessRef startProcess(StartProcessRequest request) {
            if (behavior == StartBehavior.THROW) {
                throw new IllegalStateException("simulated engine start failure");
            }
            String definitionId = behavior == StartBehavior.WRONG_IDENTITY
                    ? request.processDefinitionId() + "-wrong" : request.processDefinitionId();
            return new EngineProcessRef("process-1", definitionId,
                    request.processDefinitionKey(), request.caseId());
        }

        @Override
        public EngineProcessRef startProcessByKey(StartProcessByKeyRequest request) {
            return new EngineProcessRef("process-child", request.processDefinitionKey() + ":1",
                    request.processDefinitionKey(), request.caseId());
        }

        @Override public EngineTaskRef createHumanTask(HumanTaskRequest request) {
            throw new UnsupportedOperationException();
        }
        @Override public void claimTask(String engineTaskId, String userId) {}
        @Override public void completeTask(String engineTaskId, Map<String, Object> variables) {}
        @Override public void cancelProcess(String processInstanceId, String reason) {}
        @Override public List<EngineTaskRef> findTasks(EngineTaskQuery query) { return List.of(); }

        private enum StartBehavior { SUCCESS, THROW, WRONG_IDENTITY }
    }
}
