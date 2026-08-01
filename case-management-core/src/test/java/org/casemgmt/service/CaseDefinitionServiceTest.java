package org.casemgmt.service;

import org.casemgmt.OracleTestBase;
import org.casemgmt.domain.*;
import org.casemgmt.repo.CaseDefinitionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CaseDefinitionServiceTest extends OracleTestBase {

    private CaseDefinitionService service;
    private String json;

    @BeforeEach
    void setUp() throws Exception {
        // No manual DELETEs here: OracleTestBase already wipes every CM_ table (including
        // CM_PLAN_ITEM_DEF and CM_CASE_DEF) before each test method via its own @BeforeEach.
        service = new CaseDefinitionService(new CaseDefinitionRepository(jdbc()));
        json = new String(getClass().getResourceAsStream("/definitions/test-definition.json")
                .readAllBytes(), StandardCharsets.UTF_8);
    }

    @Test
    void deploysVersionOneAndExplodesPlanItems() {
        CaseDefinition def = service.deploy(json, "alice");

        assertThat(def.id()).isEqualTo("widget-review:1");
        assertThat(def.versionNo()).isEqualTo(1);
        assertThat(def.planItems()).hasSize(3);
        assertThat(def.planItem("review").required()).isTrue();
        assertThat(def.planItem("review").candidateGroups()).containsExactly("reviewers");
        assertThat(def.planItem("reviewed").entryCriteria())
                .containsExactly("${items.review.state == 'COMPLETED'}");
    }

    @Test
    void redeployingTheSameKeyIncrementsTheVersion() {
        service.deploy(json, "alice");
        CaseDefinition second = service.deploy(json, "alice");

        assertThat(second.versionNo()).isEqualTo(2);
        assertThat(second.id()).isEqualTo("widget-review:2");
    }

    @Test
    void findLatestReturnsTheHighestVersion() {
        service.deploy(json, "alice");
        service.deploy(json, "alice");

        var latest = new CaseDefinitionRepository(jdbc()).findLatest("widget-review", "t1");

        assertThat(latest).isPresent();
        assertThat(latest.get().versionNo()).isEqualTo(2);
    }

    @Test
    void servesFormSchemasByKey() {
        service.deploy(json, "alice");

        var schema = new CaseDefinitionRepository(jdbc()).formSchema("widget-review", "reviewForm");

        assertThat(schema).isPresent();
        assertThat(schema.get()).containsKey("properties");
    }

    @Test
    void planItemDefaultsManualActivationToFalseWhenAbsent() {
        CaseDefinition def = service.deploy(json, "alice");
        assertThat(def.planItem("reviewed").manualActivation()).isFalse();
    }

    @Test
    void listLatestReturnsOnlyTheNewestVersionPerKey() {
        service.deploy(json, "alice");
        service.deploy(json, "alice");

        var latest = new CaseDefinitionRepository(jdbc()).listLatest("t1");

        assertThat(latest).hasSize(1);
        assertThat(latest.get(0).versionNo()).isEqualTo(2);
    }

    @Test
    void findByIdAndRequireLoadTheStoredDefinitionWithPlanItems() {
        CaseDefinition deployed = service.deploy(json, "alice");

        var repo = new CaseDefinitionRepository(jdbc());
        assertThat(repo.findById(deployed.id())).isPresent();
        assertThat(repo.require(deployed.id()).planItems()).hasSize(3);
        assertThatThrownBy(() -> repo.require("no-such-def:1"))
                .isInstanceOf(org.casemgmt.error.NotFoundException.class);
    }

    @Test
    void rejectsADefinitionWhosePlanItemNamesAnUnknownParentStageKey() {
        String badJson = json.replace("\"defKey\": \"intake\"", "\"defKey\": \"intake-renamed\"");

        assertThatThrownBy(() -> service.deploy(badJson, "alice"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("review")
                .hasMessageContaining("intake");
    }
}
