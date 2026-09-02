package org.casemgmt.rest.controller;

import org.casemgmt.domain.CaseDefinition;
import org.casemgmt.error.NotFoundException;
import org.casemgmt.orchestration.EngineDeploymentIdentity;
import org.casemgmt.orchestration.OrchestrationMode;
import org.casemgmt.release.BindingStatus;
import org.casemgmt.release.CaseDefinitionRelease;
import org.casemgmt.release.CaseDefinitionVersionBinding;
import org.casemgmt.release.ReleaseKind;
import org.casemgmt.release.ReleaseStatus;
import org.casemgmt.repo.CaseDefinitionReleaseRepository;
import org.casemgmt.repo.CaseDefinitionRepository;
import org.casemgmt.repo.CaseDefinitionVersionBindingRepository;
import org.casemgmt.repo.ParticipantRepository;
import org.casemgmt.rest.CallerResolver;
import org.casemgmt.rest.policy.ActionPolicy;
import org.casemgmt.service.Actor;
import org.casemgmt.service.CaseDefinitionReleaseService;
import org.casemgmt.service.CaseDefinitionService;
import org.casemgmt.service.CaseDefinitionVersionService;
import org.casemgmt.service.CombinedCaseDefinitionDeploymentService;
import org.casemgmt.sla.SlaCalendarCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

import java.time.OffsetDateTime;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CaseDefinitionLifecycleResponseTest {

    private static final OffsetDateTime BOUND_AT = OffsetDateTime.parse("2026-08-28T08:00:00Z");
    private static final OffsetDateTime ACTIVATED_AT = OffsetDateTime.parse("2026-08-28T08:01:00Z");

    @Test
    void standaloneContractPublicationUsesTheAuthenticatedTenantForExactCalendarValidation() {
        CaseDefinitionReleaseRepository repository = mock(CaseDefinitionReleaseRepository.class);
        SlaCalendarCatalog calendars = mock(SlaCalendarCatalog.class);
        ActionPolicy policy = new ActionPolicy();
        CallerResolver callers = mock(CallerResolver.class);
        Authentication authentication = mock(Authentication.class);
        Actor actor = admin();
        when(callers.actor(authentication)).thenReturn(actor);
        when(callers.groups(actor)).thenReturn(Set.of("admin", "tenant:t1"));
        when(callers.requireTenant(actor, null)).thenReturn("t1");
        when(repository.findByDigest(org.mockito.ArgumentMatchers.eq("t1"),
                org.mockito.ArgumentMatchers.eq("orders"),
                org.mockito.ArgumentMatchers.eq(ReleaseKind.CONTRACT),
                org.mockito.ArgumentMatchers.any())).thenReturn(Optional.empty());
        when(calendars.require("t1", "support", 7))
                .thenThrow(new NotFoundException("SlaCalendarRevision", "t1/support/7"));
        byte[] content = ("{\"key\":\"orders\",\"orchestrationMode\":\"BPMN\"," +
                "\"fields\":{},\"forms\":{},\"slaBindings\":{\"response\":{" +
                "\"scope\":\"CASE\",\"calendarId\":\"support\",\"calendarRevision\":7," +
                "\"duration\":\"PT1H\",\"startAnchor\":\"CASE_CREATED\"," +
                "\"meetAnchor\":\"CASE_CLOSED\"}}}").getBytes(StandardCharsets.UTF_8);
        CaseDefinitionReleaseService releases = new CaseDefinitionReleaseService(
                repository, mock(org.casemgmt.orchestration.OrchestrationDeploymentPort.class),
                new org.casemgmt.release.JsonSchemaCaseContractValidator(), calendars);
        CaseDefinitionReleaseController controller = new CaseDefinitionReleaseController(
                releases, repository, mock(CaseDefinitionVersionService.class), policy, callers);

        var response = controller.contract(
                "orders", content, "application/json", authentication);

        assertThat(response.getStatusCode()).isEqualTo(org.springframework.http.HttpStatus.CREATED);
        assertThat(response.getBody())
                .containsEntry("status", "FAILED")
                .satisfies(body -> assertThat((String) body.get("failureDetail"))
                        .contains("support")
                        .contains("revision 7")
                        .contains("tenant 't1'"));
        verify(calendars).require("t1", "support", 7);
    }

    @Test
    void bindResponseIncludesLifecycleAndFullOperationalIdentityForTheAdministrator() {
        CaseDefinitionVersionService versions = mock(CaseDefinitionVersionService.class);
        ActionPolicy policy = new ActionPolicy();
        CallerResolver callers = mock(CallerResolver.class);
        Authentication authentication = mock(Authentication.class);
        Actor actor = admin();
        when(callers.actor(authentication)).thenReturn(actor);
        when(callers.groups(actor)).thenReturn(Set.of("admin", "tenant:t1"));
        when(callers.requireTenant(actor, null)).thenReturn("t1");
        when(versions.bind("orders", "t1", "orch-1", "contract-1", "presentation-1", "alice"))
                .thenReturn(binding());
        CaseDefinitionReleaseController controller = new CaseDefinitionReleaseController(
                mock(CaseDefinitionReleaseService.class),
                mock(CaseDefinitionReleaseRepository.class), versions, policy, callers);

        Map<String, Object> body = controller.bindVersion("orders", Map.of(
                "orchestrationReleaseId", "orch-1",
                "contractReleaseId", "contract-1",
                "presentationReleaseId", "presentation-1"), authentication).getBody();

        assertFullAdministrativeLifecycle(body);
    }

    @Test
    void combinedDeployResponseIncludesLifecycleAndFullOperationalIdentityForTheAdministrator() {
        CombinedCaseDefinitionDeploymentService service =
                mock(CombinedCaseDefinitionDeploymentService.class);
        ActionPolicy policy = new ActionPolicy();
        CallerResolver callers = mock(CallerResolver.class);
        Authentication authentication = mock(Authentication.class);
        Actor actor = admin();
        byte[] archive = {1, 2, 3};
        when(callers.actor(authentication)).thenReturn(actor);
        when(callers.groups(actor)).thenReturn(Set.of("admin", "tenant:t1"));
        when(callers.requireTenant(actor, null)).thenReturn("t1");
        when(service.deploy("t1", archive, "alice")).thenReturn(binding());
        CombinedCaseDefinitionController controller = new CombinedCaseDefinitionController(
                service, policy, callers);

        Map<String, Object> body = controller.deploy(archive, authentication).getBody();

        assertFullAdministrativeLifecycle(body);
    }

    @Test
    void generalDiscoveryExposesDescriptiveIdentityButNotRawOperationalIds() {
        CaseDefinitionRepository definitions = mock(CaseDefinitionRepository.class);
        CaseDefinitionVersionBindingRepository bindings =
                mock(CaseDefinitionVersionBindingRepository.class);
        ActionPolicy policy = mock(ActionPolicy.class);
        CallerResolver callers = new CallerResolver(mock(ParticipantRepository.class));
        Authentication authentication = mock(Authentication.class);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getName()).thenReturn("carol");
        doReturn(List.of((org.springframework.security.core.GrantedAuthority)
                () -> "ROLE_tenant:t1")).when(authentication).getAuthorities();
        when(definitions.listLatest("t1")).thenReturn(List.of(definition()));
        when(definitions.findLatest("orders", "t1")).thenReturn(Optional.of(definition()));
        when(definitions.findVersion("orders", 1, "t1")).thenReturn(Optional.of(definition()));
        when(bindings.find("definition-1")).thenReturn(Optional.of(binding()));
        when(policy.listForAdministration(Set.of("tenant:t1"))).thenReturn(List.of());
        CaseDefinitionController controller = new CaseDefinitionController(
                definitions, bindings, policy, callers);

        List<Map<String, Object>> responses = List.of(
                controller.list(null, authentication).getFirst(),
                controller.get("orders", null, authentication),
                controller.getVersion("orders", 1, null, authentication));

        assertThat(responses).allSatisfy(body -> {
            assertThat(body)
                    .containsEntry("bindingStatus", "ACTIVE")
                    .containsEntry("boundAt", BOUND_AT)
                    .containsEntry("activatedAt", ACTIVATED_AT)
                    .containsEntry("retiredAt", null)
                    .containsEntry("engineProcessDefinitionKey", "orders")
                    .containsEntry("engineProcessDefinitionVersion", 7)
                    .containsEntry("engineTenantId", "t1")
                    .doesNotContainKeys("engineDeploymentId", "engineProcessDefinitionId");
        });
    }

    @Test
    void workerCanReadAFormFromOneExactDefinitionVersion() {
        CaseDefinitionRepository definitions = mock(CaseDefinitionRepository.class);
        CaseDefinitionVersionBindingRepository bindings =
                mock(CaseDefinitionVersionBindingRepository.class);
        ActionPolicy policy = mock(ActionPolicy.class);
        CallerResolver callers = new CallerResolver(mock(ParticipantRepository.class));
        Authentication authentication = mock(Authentication.class);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getName()).thenReturn("carol");
        doReturn(List.of((org.springframework.security.core.GrantedAuthority)
                () -> "ROLE_tenant:t1")).when(authentication).getAuthorities();
        when(definitions.findVersion("orders", 1, "t1")).thenReturn(Optional.of(definition()));
        Map<String, Object> schema = Map.of("schema", Map.of(
                "type", "object", "required", List.of("outcome")));
        when(definitions.formSchemaOfDefinition("definition-1", "reviewForm"))
                .thenReturn(Optional.of(schema));
        CaseDefinitionController controller = new CaseDefinitionController(
                definitions, bindings, policy, callers);

        assertThat(controller.versionedForm(
                "orders", 1, "reviewForm", null, authentication)).isEqualTo(schema);
        verify(definitions).formSchemaOfDefinition("definition-1", "reviewForm");
    }

    @Test
    void failedPublicationPointsToWorkingMetadataAndReturnsTheBoundedDiagnostic() {
        CaseDefinitionReleaseService releases = mock(CaseDefinitionReleaseService.class);
        CaseDefinitionReleaseRepository repository = mock(CaseDefinitionReleaseRepository.class);
        ActionPolicy policy = new ActionPolicy();
        CallerResolver callers = mock(CallerResolver.class);
        Authentication authentication = mock(Authentication.class);
        Actor actor = admin();
        String oversized = "x".repeat(2_100);
        byte[] content = {1};
        CaseDefinitionRelease failed = failedRelease(
                "orchestration:failed", ReleaseKind.ORCHESTRATION, oversized);
        when(callers.actor(authentication)).thenReturn(actor);
        when(callers.groups(actor)).thenReturn(Set.of("admin", "tenant:t1"));
        when(callers.requireTenant(actor, null)).thenReturn("t1");
        when(releases.publish("orders", "t1", ReleaseKind.ORCHESTRATION,
                "application/bpmn+xml", content, "alice")).thenReturn(failed);
        when(repository.require("orchestration:failed", "t1")).thenReturn(failed);
        CaseDefinitionReleaseController controller = new CaseDefinitionReleaseController(
                releases, repository, mock(CaseDefinitionVersionService.class),
                policy, callers);

        var published = controller.orchestration(
                "orders", content, "application/bpmn+xml", authentication);
        Map<String, Object> retrieved = controller.release(
                "orders", "orchestration:failed", authentication);

        assertThat(published.getHeaders().getLocation()).isEqualTo(URI.create(
                "/case-api/v2/case-definitions/orders/releases/orchestration:failed"));
        assertThat(published.getBody())
                .containsEntry("status", "FAILED")
                .containsEntry("failureDetail", "x".repeat(1_997) + "...");
        assertThat(retrieved)
                .containsEntry("id", "orchestration:failed")
                .containsEntry("kind", "ORCHESTRATION")
                .containsEntry("status", "FAILED")
                .containsEntry("failureDetail", "x".repeat(1_997) + "...");
    }

    @Test
    void oneMetadataEndpointRetrievesEveryReleaseKindWithoutChangingContentRoutes() {
        CaseDefinitionReleaseRepository repository = mock(CaseDefinitionReleaseRepository.class);
        ActionPolicy policy = new ActionPolicy();
        CallerResolver callers = mock(CallerResolver.class);
        Authentication authentication = mock(Authentication.class);
        Actor actor = admin();
        when(callers.actor(authentication)).thenReturn(actor);
        when(callers.groups(actor)).thenReturn(Set.of("admin", "tenant:t1"));
        when(callers.requireTenant(actor, null)).thenReturn("t1");
        for (ReleaseKind kind : ReleaseKind.values()) {
            when(repository.require(kind.name().toLowerCase() + ":1", "t1"))
                    .thenReturn(failedRelease(
                            kind.name().toLowerCase() + ":1", kind, "deployment rejected"));
        }
        CaseDefinitionReleaseController controller = new CaseDefinitionReleaseController(
                mock(CaseDefinitionReleaseService.class), repository,
                mock(CaseDefinitionVersionService.class), policy, callers);

        for (ReleaseKind kind : ReleaseKind.values()) {
            assertThat(controller.release(
                    "orders", kind.name().toLowerCase() + ":1", authentication))
                    .containsEntry("kind", kind.name())
                    .containsEntry("status", "FAILED")
                    .containsEntry("failureDetail", "deployment rejected");
        }
    }

    private static void assertFullAdministrativeLifecycle(Map<String, Object> body) {
        assertThat(body)
                .containsEntry("caseDefinitionId", "definition-1")
                .containsEntry("deploymentStatus", "ACTIVE")
                .containsEntry("bindingStatus", "ACTIVE")
                .containsEntry("boundAt", BOUND_AT)
                .containsEntry("activatedAt", ACTIVATED_AT)
                .containsEntry("retiredAt", null)
                .containsEntry("engineDeploymentId", "deployment-7")
                .containsEntry("engineProcessDefinitionId", "orders:7:exact")
                .containsEntry("engineProcessDefinitionKey", "orders")
                .containsEntry("engineProcessDefinitionVersion", 7)
                .containsEntry("engineTenantId", "t1");
    }

    private static Actor admin() {
        return new Actor("alice", List.of("admin", "tenant:t1"));
    }

    private static CaseDefinitionVersionBinding binding() {
        return new CaseDefinitionVersionBinding(
                "definition-1", "orders", "t1",
                "orch-1", "orch-sha", "contract-1", "contract-sha",
                "presentation-1", "presentation-sha", ReleaseStatus.ACTIVE,
                OrchestrationMode.BPMN, BindingStatus.ACTIVE,
                new EngineDeploymentIdentity(
                        "deployment-7", "orders:7:exact", "orders", 7, "t1"),
                null, BOUND_AT, ACTIVATED_AT, null, "alice");
    }

    private static CaseDefinition definition() {
        return new CaseDefinition(
                "definition-1", "orders", 1, "Orders", "t1", null, null,
                List.of(), List.of(), Map.of(), List.of(), OrchestrationMode.BPMN,
                OffsetDateTime.parse("2026-08-28T07:59:00Z"), "alice");
    }

    private static CaseDefinitionRelease failedRelease(
            String id, ReleaseKind kind, String failureDetail) {
        return CaseDefinitionRelease.storedWithEngineIdentity(
                id, "orders", "t1", kind,
                kind == ReleaseKind.ORCHESTRATION
                        ? "application/bpmn+xml" : "application/json",
                new byte[]{1}, "f".repeat(64), ReleaseStatus.FAILED,
                null, failureDetail, "alice");
    }
}
