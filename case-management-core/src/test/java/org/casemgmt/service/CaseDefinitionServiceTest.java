package org.casemgmt.service;

import org.casemgmt.OracleTestBase;
import org.casemgmt.domain.*;
import org.casemgmt.orchestration.OrchestrationMode;
import org.casemgmt.repo.CaseDefinitionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import javax.sql.DataSource;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CaseDefinitionServiceTest extends OracleTestBase {

    private CaseDefinitionService service;
    private String contract;

    @BeforeEach
    void setUp() {
        service = new CaseDefinitionService(new CaseDefinitionRepository(dataSource()));
        contract = """
                {
                  "key":"widget-review",
                  "name":"Widget Review",
                  "roles":["owner"],
                  "attachmentCategories":["evidence"],
                  "forms":{
                    "reviewForm":{
                      "schema":{"type":"object","properties":{"outcome":{"type":"string"}}},
                      "uiSchema":{"ui:order":["outcome"]}
                    }
                  }
                }
                """;
    }

    @Test
    void deploysBpmnVersionOneWithoutCreatingASecondPlanModel() {
        CaseDefinition def = service.deployBpmn("widget-review", contract, "alice", "t1");

        assertThat(def.id()).isEqualTo("t1:widget-review:1");
        assertThat(def.versionNo()).isEqualTo(1);
        assertThat(def.orchestrationMode()).isEqualTo(OrchestrationMode.BPMN);
        assertThat(def.planItems()).isEmpty();
        assertThat(def.roles()).containsExactly("owner");
    }

    @Test
    void redeployingTheSameKeyIncrementsTheVersion() {
        service.deployBpmn("widget-review", contract, "alice", "t1");
        CaseDefinition second = service.deployBpmn("widget-review", contract, "alice", "t1");

        assertThat(second.versionNo()).isEqualTo(2);
        assertThat(second.id()).isEqualTo("t1:widget-review:2");
    }

    @Test
    void findLatestReturnsTheHighestVersion() {
        service.deployBpmn("widget-review", contract, "alice", "t1");
        service.deployBpmn("widget-review", contract, "alice", "t1");

        var latest = new CaseDefinitionRepository(dataSource()).findLatest("widget-review", "t1");

        assertThat(latest).isPresent();
        assertThat(latest.get().versionNo()).isEqualTo(2);
    }

    @Test
    void servesFormSchemasByKeyAndTenant() {
        service.deployBpmn("widget-review", contract, "alice", "t1");

        var schema = new CaseDefinitionRepository(dataSource())
                .formSchema("widget-review", "reviewForm", "t1");

        assertThat(schema).isPresent();
        assertThat(schema.get()).containsKey("properties");
    }

    @Test
    void formLookupUsesTheCanonicalSchemaAndNotPresentationMetadata() {
        CaseDefinition def = service.deployBpmn("widget-review", contract, "alice", "t1");

        assertThat(def.forms().get("reviewForm"))
                .isEqualTo(java.util.Map.of("type", "object", "properties",
                        java.util.Map.of("outcome", java.util.Map.of("type", "string"))));
    }

    @Test
    void listLatestReturnsOnlyTheNewestVersionPerKey() {
        service.deployBpmn("widget-review", contract, "alice", "t1");
        service.deployBpmn("widget-review", contract, "alice", "t1");

        var latest = new CaseDefinitionRepository(dataSource()).listLatest("t1");

        assertThat(latest).hasSize(1);
        assertThat(latest.get(0).versionNo()).isEqualTo(2);
    }

    @Test
    void findByIdAndRequireLoadTheStoredBpmnDefinition() {
        CaseDefinition deployed = service.deployBpmn("widget-review", contract, "alice", "t1");

        var repo = new CaseDefinitionRepository(dataSource());
        assertThat(repo.findById(deployed.id())).isPresent();
        assertThat(repo.require(deployed.id()).planItems()).isEmpty();
        assertThat(repo.require(deployed.id()).orchestrationMode()).isEqualTo(OrchestrationMode.BPMN);
        assertThatThrownBy(() -> repo.require("no-such-def:1"))
                .isInstanceOf(org.casemgmt.error.NotFoundException.class);
    }

    @Test
    void rejectsAContractWhoseDeclaredKeyDiffersFromTheReleaseKey() {
        assertThatThrownBy(() -> service.deployBpmn("other-key", contract, "alice", "t1"))
                .isInstanceOf(org.casemgmt.error.InvalidCaseDefinitionException.class)
                .hasMessageContaining("does not match definition key");
    }

    @Test
    void retriesVersionAllocationWhenAConcurrentDeployWinsTheInsertRace() {
        CaseDefinitionService racing = new CaseDefinitionService(new OneDuplicateThenInsertRepository(dataSource()));

        CaseDefinition deployed = racing.deployBpmn("widget-review", contract, "alice", "t1");

        assertThat(deployed.versionNo()).isEqualTo(2);
        assertThat(deployed.id()).isEqualTo("t1:widget-review:2");
        CaseDefinition latest = new CaseDefinitionRepository(dataSource())
                .findLatest("widget-review", "t1").orElseThrow();
        assertThat(latest.versionNo()).isEqualTo(2);
    }

    private static final class OneDuplicateThenInsertRepository extends CaseDefinitionRepository {
        private final AtomicInteger nextVersionCalls = new AtomicInteger();
        private final AtomicInteger insertCalls = new AtomicInteger();

        private OneDuplicateThenInsertRepository(DataSource dataSource) {
            super(dataSource);
        }

        @Override
        public int nextVersion(String key, String tenantId) {
            return nextVersionCalls.incrementAndGet();
        }

        @Override
        public void insert(CaseDefinition d) {
            if (insertCalls.incrementAndGet() == 1) {
                throw new DuplicateKeyException("simulated concurrent deploy");
            }
            super.insert(d);
        }
    }
}
