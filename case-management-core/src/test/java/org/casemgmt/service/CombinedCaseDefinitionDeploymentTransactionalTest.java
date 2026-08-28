package org.casemgmt.service;

import org.casemgmt.OracleTestBase;
import org.casemgmt.orchestration.OrchestrationDeploymentPort;
import org.casemgmt.release.ReleaseKind;
import org.casemgmt.release.ReleaseStatus;
import org.casemgmt.repo.CaseDefinitionReleaseRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

/** Transaction-boundary proof for durable definitive deployment failures. */
class CombinedCaseDefinitionDeploymentTransactionalTest extends OracleTestBase {

    private AnnotationConfigApplicationContext context;

    @BeforeEach
    void setUp() {
        context = springContext(Config.class);
    }

    @AfterEach
    void closeContext() {
        context.close();
    }

    @Test
    void definitiveFailedOrchestrationReleaseSurvivesLaterCombinedBindingFailure()
            throws Exception {
        CombinedCaseDefinitionDeploymentService service =
                context.getBean(CombinedCaseDefinitionDeploymentService.class);
        CaseDefinitionReleaseRepository releases =
                context.getBean(CaseDefinitionReleaseRepository.class);

        assertThatThrownBy(() -> service.deploy("tenant-a", validArchive(), "alice"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("simulated combined binding failure");

        var orchestration = releases.list(
                "invoice", "tenant-a", ReleaseKind.ORCHESTRATION);
        assertThat(orchestration).singleElement().satisfies(release -> {
            assertThat(release.status()).isEqualTo(ReleaseStatus.FAILED);
            assertThat(release.failureDetail())
                    .hasSize(2_000)
                    .endsWith("...");
        });
    }

    private static byte[] validArchive() throws Exception {
        return zip(Map.of(
                "processes/invoice.bpmn", """
                        <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL">
                          <process id="invoice" isExecutable="true"/>
                        </definitions>""",
                "contract.json", """
                        {"key":"invoice","orchestrationMode":"BPMN","fields":{},"forms":{}}
                        """,
                "presentation.json", """
                        {"version":"1.0","sections":[]}
                        """));
    }

    private static byte[] zip(Map<String, String> entries) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (var entry : new LinkedHashMap<>(entries).entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    @Configuration(proxyBeanMethods = false)
    static class Config {
        @Bean
        CaseDefinitionReleaseRepository releases(DataSource dataSource) {
            return new CaseDefinitionReleaseRepository(dataSource);
        }

        @Bean
        OrchestrationDeploymentPort deployments() {
            return (releaseId, definitionKey, tenantId, content, mediaType) ->
                    new OrchestrationDeploymentPort.DeploymentResult(
                            ReleaseStatus.FAILED, null, "x".repeat(2_100));
        }

        @Bean
        CaseDefinitionReleaseService releaseService(
                CaseDefinitionReleaseRepository releases,
                OrchestrationDeploymentPort deployments) {
            return new CaseDefinitionReleaseService(releases, deployments);
        }

        @Bean
        CaseDefinitionVersionService versionService() {
            CaseDefinitionVersionService versions = mock(CaseDefinitionVersionService.class);
            doThrow(new IllegalStateException("simulated combined binding failure"))
                    .when(versions).bindPendingDeployment(
                            anyString(), anyString(), anyString(), anyString(), anyString(),
                            anyString());
            return versions;
        }

        @Bean
        CombinedCaseDefinitionDeploymentService combined(
                CaseDefinitionReleaseService releases,
                CaseDefinitionVersionService versions) {
            return new CombinedCaseDefinitionDeploymentService(releases, versions);
        }
    }
}
