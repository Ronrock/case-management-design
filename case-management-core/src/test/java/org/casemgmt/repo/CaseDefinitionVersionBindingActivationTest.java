package org.casemgmt.repo;

import org.casemgmt.OracleTestBase;
import org.casemgmt.domain.CaseDefinition;
import org.casemgmt.orchestration.EngineDeploymentIdentity;
import org.casemgmt.orchestration.OrchestrationMode;
import org.casemgmt.release.BindingStatus;
import org.casemgmt.release.CaseDefinitionRelease;
import org.casemgmt.release.CaseDefinitionVersionBinding;
import org.casemgmt.release.ReleaseKind;
import org.casemgmt.release.ReleaseStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Repository proof for out-of-order and concurrent remote deployment reports. */
class CaseDefinitionVersionBindingActivationTest extends OracleTestBase {

    private AnnotationConfigApplicationContext context;
    private CaseDefinitionVersionBindingRepository bindings;
    private ActivationWorker worker;

    @BeforeEach
    void setUp() {
        context = springContext(Config.class);
        bindings = context.getBean(CaseDefinitionVersionBindingRepository.class);
        worker = context.getBean(ActivationWorker.class);
    }

    @AfterEach
    void closeContext() {
        context.close();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = "tenant-a")
    void delayedVersionTwoReportCannotReplaceAlreadyActiveVersionThree(String tenantId) {
        Fixture v2 = insertCandidate("invoice", tenantId, 2);
        Fixture v3 = insertCandidate("invoice", tenantId, 3);

        worker.activate(v3.activeBinding());
        worker.activate(v2.activeBinding());

        assertThat(bindings.find(v3.definitionId()).orElseThrow().status())
                .isEqualTo(BindingStatus.ACTIVE);
        CaseDefinitionVersionBinding superseded = bindings.find(v2.definitionId()).orElseThrow();
        assertThat(superseded.status()).isEqualTo(BindingStatus.FAILED);
        assertThat(superseded.failureDetail())
                .contains("superseded")
                .contains("version 3");
    }

    @Test
    void concurrentReportsSerializeOnTheDefinitionAuthorityAndLeaveTheNewestVersionActive()
            throws Exception {
        Fixture v2 = insertCandidate("invoice", "tenant-a", 2);
        Fixture v3 = insertCandidate("invoice", "tenant-a", 3);
        CountDownLatch start = new CountDownLatch(1);

        try (var pool = Executors.newFixedThreadPool(2)) {
            var v2Result = pool.submit(() -> {
                start.await();
                worker.activate(v2.activeBinding());
                return null;
            });
            var v3Result = pool.submit(() -> {
                start.await();
                worker.activate(v3.activeBinding());
                return null;
            });
            start.countDown();

            v2Result.get(10, TimeUnit.SECONDS);
            v3Result.get(10, TimeUnit.SECONDS);
        }

        assertThat(bindings.find(v3.definitionId()).orElseThrow().status())
                .isEqualTo(BindingStatus.ACTIVE);
        assertThat(bindings.find(v2.definitionId()).orElseThrow().status())
                .isEqualTo(BindingStatus.FAILED);
        assertThat(jdbc().sql("""
                SELECT COUNT(*) FROM CM_CASE_DEF_BINDING
                WHERE CASE_DEF_KEY_ = 'invoice' AND TENANT_ID_ = 'tenant-a'
                  AND STATUS_ = 'ACTIVE'""").query(Integer.class).single()).isEqualTo(1);
    }

    private Fixture insertCandidate(String key, String tenantId, int version) {
        String authority = tenantId == null ? "global" : tenantId;
        String definitionId = authority + ":" + key + ":" + version;
        new CaseDefinitionRepository(dataSource()).insert(new CaseDefinition(
                definitionId, key, version, "Invoice v" + version, tenantId,
                null, null, List.of(), List.of(), Map.of(), List.of(),
                OrchestrationMode.BPMN, OffsetDateTime.now(), "alice"));

        String orchestrationId = definitionId + ":orchestration";
        String contractId = definitionId + ":contract";
        String presentationId = definitionId + ":presentation";
        String orchestrationSha = Integer.toString(version).repeat(64);
        String contractSha = "c".repeat(64);
        String presentationSha = "p".repeat(64);
        EngineDeploymentIdentity identity = new EngineDeploymentIdentity(
                "deployment-" + version, key + ":" + version + ":exact",
                key, version, tenantId);
        CaseDefinitionReleaseRepository releases =
                new CaseDefinitionReleaseRepository(dataSource());
        releases.insert(release(orchestrationId, key, tenantId, ReleaseKind.ORCHESTRATION,
                orchestrationSha, identity));
        releases.insert(release(contractId, key, tenantId, ReleaseKind.CONTRACT,
                contractSha, null));
        releases.insert(release(presentationId, key, tenantId, ReleaseKind.PRESENTATION,
                presentationSha, null));

        CaseDefinitionVersionBinding draft = new CaseDefinitionVersionBinding(
                definitionId, key, tenantId, orchestrationId, orchestrationSha,
                contractId, contractSha, presentationId, presentationSha,
                ReleaseStatus.DEPLOYING, OrchestrationMode.BPMN, BindingStatus.DRAFT,
                null, null, OffsetDateTime.now(), null, null, "alice");
        bindings.insert(draft);
        return new Fixture(draft, identity);
    }

    private static CaseDefinitionRelease release(
            String id, String key, String tenantId, ReleaseKind kind, String sha,
            EngineDeploymentIdentity identity) {
        return CaseDefinitionRelease.storedWithEngineIdentity(
                id, key, tenantId, kind,
                kind == ReleaseKind.ORCHESTRATION ? "application/zip" : "application/json",
                new byte[]{1}, sha, ReleaseStatus.ACTIVE, identity, null, "alice");
    }

    private record Fixture(CaseDefinitionVersionBinding draft,
                           EngineDeploymentIdentity identity) {
        String definitionId() {
            return draft.caseDefinitionId();
        }

        CaseDefinitionVersionBinding activeBinding() {
            return new CaseDefinitionVersionBinding(
                    draft.caseDefinitionId(), draft.caseDefinitionKey(), draft.tenantId(),
                    draft.orchestrationReleaseId(), draft.orchestrationSha256(),
                    draft.contractReleaseId(), draft.contractSha256(),
                    draft.presentationReleaseId(), draft.presentationSha256(),
                    ReleaseStatus.ACTIVE, draft.orchestrationMode(), BindingStatus.ACTIVE,
                    identity, null, draft.boundAt(), OffsetDateTime.now(), null,
                    draft.boundBy());
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class Config {
        @Bean
        CaseDefinitionVersionBindingRepository bindings(DataSource dataSource) {
            return new CaseDefinitionVersionBindingRepository(dataSource);
        }

        @Bean
        ActivationWorker activationWorker(CaseDefinitionVersionBindingRepository bindings) {
            return new ActivationWorker(bindings);
        }
    }

    static class ActivationWorker {
        private final CaseDefinitionVersionBindingRepository bindings;

        ActivationWorker(CaseDefinitionVersionBindingRepository bindings) {
            this.bindings = bindings;
        }

        @Transactional
        public void activate(CaseDefinitionVersionBinding binding) {
            bindings.activate(binding);
        }
    }
}
