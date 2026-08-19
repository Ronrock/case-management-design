package org.casemgmt.service;

import org.casemgmt.OracleTestBase;
import org.casemgmt.domain.*;
import org.casemgmt.error.InvalidCaseDefinitionException;
import org.casemgmt.repo.CaseDefinitionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CaseDefinitionServiceTest extends OracleTestBase {

    private CaseDefinitionService service;
    private String json;

    @BeforeEach
    void setUp() throws Exception {
        // No manual DELETEs here: OracleTestBase already wipes every CM_ table (including
        // CM_PLAN_ITEM_DEF and CM_CASE_DEF) before each test method via its own @BeforeEach.
        service = new CaseDefinitionService(new CaseDefinitionRepository(dataSource()));
        json = new String(getClass().getResourceAsStream("/definitions/test-definition.json")
                .readAllBytes(), StandardCharsets.UTF_8);
    }

    @Test
    void deploysVersionOneAndExplodesPlanItems() {
        CaseDefinition def = service.deploy(json, "alice", "t1");

        assertThat(def.id()).isEqualTo("t1:widget-review:1");
        assertThat(def.versionNo()).isEqualTo(1);
        assertThat(def.planItems()).hasSize(3);
        assertThat(def.planItem("review").required()).isTrue();
        assertThat(def.planItem("review").candidateGroups()).containsExactly("reviewers");
        assertThat(def.planItem("reviewed").entryCriteria())
                .containsExactly("${items.review.state == 'COMPLETED'}");
    }

    @Test
    void redeployingTheSameKeyIncrementsTheVersion() {
        service.deploy(json, "alice", "t1");
        CaseDefinition second = service.deploy(json, "alice", "t1");

        assertThat(second.versionNo()).isEqualTo(2);
        assertThat(second.id()).isEqualTo("t1:widget-review:2");
    }

    @Test
    void findLatestReturnsTheHighestVersion() {
        service.deploy(json, "alice", "t1");
        service.deploy(json, "alice", "t1");

        var latest = new CaseDefinitionRepository(dataSource()).findLatest("widget-review", "t1");

        assertThat(latest).isPresent();
        assertThat(latest.get().versionNo()).isEqualTo(2);
    }

    @Test
    void servesFormSchemasByKeyAndTenant() {
        service.deploy(json, "alice", "t1");

        var schema = new CaseDefinitionRepository(dataSource())
                .formSchema("widget-review", "reviewForm", "t1");

        assertThat(schema).isPresent();
        assertThat(schema.get()).containsKey("properties");
    }

    @Test
    void planItemDefaultsManualActivationToFalseWhenAbsent() {
        CaseDefinition def = service.deploy(json, "alice", "t1");
        assertThat(def.planItem("reviewed").manualActivation()).isFalse();
    }

    @Test
    void listLatestReturnsOnlyTheNewestVersionPerKey() {
        service.deploy(json, "alice", "t1");
        service.deploy(json, "alice", "t1");

        var latest = new CaseDefinitionRepository(dataSource()).listLatest("t1");

        assertThat(latest).hasSize(1);
        assertThat(latest.get(0).versionNo()).isEqualTo(2);
    }

    @Test
    void findByIdAndRequireLoadTheStoredDefinitionWithPlanItems() {
        CaseDefinition deployed = service.deploy(json, "alice", "t1");

        var repo = new CaseDefinitionRepository(dataSource());
        assertThat(repo.findById(deployed.id())).isPresent();
        assertThat(repo.require(deployed.id()).planItems()).hasSize(3);
        assertThatThrownBy(() -> repo.require("no-such-def:1"))
                .isInstanceOf(org.casemgmt.error.NotFoundException.class);
    }

    @Test
    void rejectsADefinitionWhosePlanItemNamesAnUnknownParentStageKey() {
        String badJson = json.replace("\"defKey\": \"setupStage\"", "\"defKey\": \"setupStageRenamed\"");

        assertThatThrownBy(() -> service.deploy(badJson, "alice", "t1"))
                .isInstanceOf(InvalidCaseDefinitionException.class)
                .hasMessageContaining("review")
                .hasMessageContaining("setupStage");
    }

    @Test
    void rejectsADefinitionWithDuplicatePlanItemDefKeys() {
        // Renaming "reviewed" to "review" collides with the existing "review" HUMAN_TASK's
        // defKey. Caught before any write reaches the repository — this is the exact
        // condition CaseDefinitionRepositoryTest proves insert() itself rolls back atomically
        // if it were ever to reach the database instead.
        String badJson = json.replace("\"defKey\": \"reviewed\"", "\"defKey\": \"review\"");

        assertThatThrownBy(() -> service.deploy(badJson, "alice", "t1"))
                .isInstanceOf(InvalidCaseDefinitionException.class)
                .hasMessageContaining("duplicate")
                .hasMessageContaining("review");

        // And nothing was written: no CM_CASE_DEF row for this key at all.
        var repo = new CaseDefinitionRepository(dataSource());
        assertThat(repo.findLatest("widget-review", "t1")).isEmpty();
    }

    @Test
    void rejectsCandidateGroupsThatReuseCaseRoleNames() {
        String badJson = json.replace("\"candidateGroups\": [\"reviewers\"]",
                "\"candidateGroups\": [\"owner\"]");

        assertThatThrownBy(() -> service.deploy(badJson, "alice", "t1"))
                .isInstanceOf(InvalidCaseDefinitionException.class)
                .hasMessageContaining("candidateGroup")
                .hasMessageContaining("owner")
                .hasMessageContaining("role");
    }

    @Test
    void rejectsCriteriaThatReferenceUnknownPlanItemDefKeys() {
        String badJson = json.replace("items.review.state", "items.reveiw.state");

        assertThatThrownBy(() -> service.deploy(badJson, "alice", "t1"))
                .isInstanceOf(InvalidCaseDefinitionException.class)
                .hasMessageContaining("entryCriteria")
                .hasMessageContaining("reveiw");
    }

    @Test
    void retriesVersionAllocationWhenAConcurrentDeployWinsTheInsertRace() {
        CaseDefinitionService racing = new CaseDefinitionService(new OneDuplicateThenInsertRepository(dataSource()));

        CaseDefinition deployed = racing.deploy(json, "alice", "t1");

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
