package org.casemgmt.starter;

import org.casemgmt.engine.EngineGateway;
import org.casemgmt.orchestration.BpmnOrchestration;
import org.casemgmt.orchestration.CaseOrchestration;
import org.casemgmt.orchestration.CaseOrchestrationRegistry;
import org.casemgmt.orchestration.EngineDeploymentIdentityResolver;
import org.casemgmt.event.EventPublisher;
import org.casemgmt.observation.DefaultEngineObservationAuthorityValidator;
import org.casemgmt.observation.DefaultEngineObservationHandler;
import org.casemgmt.observation.EngineObservationAuthorityValidator;
import org.casemgmt.observation.EngineObservationHandler;
import org.casemgmt.observation.LoggingObservationSecurityTelemetry;
import org.casemgmt.observation.ObservationSecurityTelemetry;
import org.casemgmt.observation.SlaLifecyclePort;
import org.casemgmt.sla.SlaLifecycleService;
import org.casemgmt.projection.CaseProjectionPort;
import org.casemgmt.release.JsonSchemaCaseContractValidator;
import org.casemgmt.repo.AppliedObservationRepository;
import org.casemgmt.repo.AuditRepository;
import org.casemgmt.repo.CaseDefinitionRepository;
import org.casemgmt.repo.CaseDefinitionReleaseRepository;
import org.casemgmt.repo.CaseDefinitionVersionBindingRepository;
import org.casemgmt.repo.CaseRepository;
import org.casemgmt.repo.CaseTaskRepository;
import org.casemgmt.repo.CommentRepository;
import org.casemgmt.repo.DocumentRepository;
import org.casemgmt.repo.EventRepository;
import org.casemgmt.repo.EngineCommandRepository;
import org.casemgmt.repo.LinkedProcessRepository;
import org.casemgmt.repo.MilestoneRepository;
import org.casemgmt.repo.ParticipantRepository;
import org.casemgmt.repo.PlanItemRepository;
import org.casemgmt.repo.SlaRepository;
import org.casemgmt.repo.WebhookRepository;
import org.casemgmt.service.CaseDefinitionService;
import org.casemgmt.service.CaseDefinitionReleaseService;
import org.casemgmt.service.CaseDefinitionVersionService;
import org.casemgmt.service.CaseService;
import org.casemgmt.service.CaseTaskService;
import org.casemgmt.service.EngineOperationService;
import org.casemgmt.service.CommentService;
import org.casemgmt.service.DocumentService;
import org.casemgmt.service.FormValidator;
import org.casemgmt.service.LinkedProcessService;
import org.casemgmt.service.CaseDataMappingService;
import org.casemgmt.service.ContractCaseDataMappingService;
import org.casemgmt.service.MilestoneService;
import org.casemgmt.service.AdHocActionService;
import org.casemgmt.service.CombinedCaseDefinitionDeploymentService;
import org.casemgmt.service.OrchestrationDeploymentReportService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.ObjectProvider;
import org.casemgmt.orchestration.OrchestrationDeploymentPort;
import org.casemgmt.rules.CriterionEvaluator;

@Configuration(proxyBeanMethods = false)
public class CaseManagementServiceConfiguration {

    @Bean
    @ConditionalOnMissingBean(CaseDataMappingService.class)
    public CaseDataMappingService caseDataMappingService(
            CaseRepository cases,
            CaseDefinitionVersionBindingRepository bindings,
            CaseDefinitionReleaseRepository releases) {
        return new ContractCaseDataMappingService(cases, bindings, releases,
                new JsonSchemaCaseContractValidator());
    }

    @Bean
    @ConditionalOnMissingBean(EngineObservationAuthorityValidator.class)
    public EngineObservationAuthorityValidator engineObservationAuthorityValidator(
            CaseDefinitionVersionBindingRepository bindings,
            LinkedProcessRepository processes,
            CaseManagementProperties properties) {
        return new DefaultEngineObservationAuthorityValidator(
                bindings, processes, properties.getEngineId());
    }

    @Bean
    @ConditionalOnMissingBean(SlaLifecyclePort.class)
    public SlaLifecyclePort slaLifecyclePort(SlaRepository sla, CaseRepository cases,
                                             EventPublisher events,
                                             CaseDefinitionVersionBindingRepository bindings,
                                             CaseDefinitionReleaseRepository releases) {
        return new SlaLifecycleService(sla, cases, events, bindings, releases,
                new JsonSchemaCaseContractValidator());
    }

    @Bean
    @ConditionalOnMissingBean(ObservationSecurityTelemetry.class)
    public ObservationSecurityTelemetry observationSecurityTelemetry() {
        return new LoggingObservationSecurityTelemetry();
    }

    @Bean
    @ConditionalOnMissingBean(EngineObservationHandler.class)
    public EngineObservationHandler engineObservationHandler(
            AppliedObservationRepository claims,
            CaseRepository cases,
            LinkedProcessRepository processes,
            CaseProjectionPort projections,
            CaseDataMappingService mappings,
            EventPublisher events,
            SlaLifecyclePort sla,
            EngineObservationAuthorityValidator authority,
            ObservationSecurityTelemetry securityTelemetry) {
        return new DefaultEngineObservationHandler(claims, cases, processes, projections,
                mappings, events, sla, authority, securityTelemetry);
    }

    @Bean
    @ConditionalOnMissingBean(EventPublisher.class)
    public EventPublisher eventPublisher(EventRepository events, AuditRepository audit,
                                         WebhookRepository webhooks, CaseManagementProperties props) {
        String prefix = props.getEvents().getTypePrefix();
        if (prefix == null || prefix.isBlank()) {
            throw new IllegalStateException(
                    "casemgmt.events.type-prefix must be set; it becomes the CloudEvents type namespace");
        }
        return new EventPublisher(events, audit, webhooks, prefix, props.getEngineId());
    }

    @Bean(name = "caseManagementCaseService")
    public CaseService caseService(CaseRepository cases, CaseDefinitionRepository definitions,
                                   PlanItemRepository planItems, ParticipantRepository participants,
                                   CaseOrchestrationRegistry orchestrations, EventPublisher publisher,
                                   CaseManagementProperties props) {
        return new CaseService(cases, definitions, planItems, participants,
                orchestrations, publisher, props.getEngineId());
    }

    @Bean
    public BpmnOrchestration bpmnOrchestration(
            EngineGateway engine, LinkedProcessRepository processes,
            EngineDeploymentIdentityResolver identities) {
        return new BpmnOrchestration(engine, processes, identities);
    }

    @Bean
    public CaseOrchestrationRegistry caseOrchestrationRegistry(
            java.util.List<CaseOrchestration> orchestrations) {
        return new CaseOrchestrationRegistry(orchestrations);
    }

    @Bean
    public CaseDefinitionService caseDefinitionService(CaseDefinitionRepository repo) {
        return new CaseDefinitionService(repo);
    }

    @Bean
    public CaseDefinitionReleaseService caseDefinitionReleaseService(
            CaseDefinitionReleaseRepository repo,
            ObjectProvider<OrchestrationDeploymentPort> deployments) {
        OrchestrationDeploymentPort deployment = deployments.getIfAvailable(() ->
                (releaseId, definitionKey, tenantId, content, mediaType) -> {
                    throw new org.casemgmt.engine.EngineException(
                            "No orchestration deployment adapter is configured");
                });
        return new CaseDefinitionReleaseService(repo, deployment);
    }

    @Bean
    public CaseDefinitionVersionService caseDefinitionVersionService(
            CaseDefinitionReleaseRepository releases,
            CaseDefinitionVersionBindingRepository bindings,
            CaseDefinitionService definitions) {
        return new CaseDefinitionVersionService(releases, bindings, definitions);
    }

    @Bean
    public OrchestrationDeploymentReportService orchestrationDeploymentReportService(
            CaseDefinitionReleaseRepository releases,
            CaseDefinitionVersionBindingRepository bindings) {
        return new OrchestrationDeploymentReportService(releases, bindings);
    }

    @Bean
    public CombinedCaseDefinitionDeploymentService combinedCaseDefinitionDeploymentService(
            CaseDefinitionReleaseService releases, CaseDefinitionVersionService versions) {
        return new CombinedCaseDefinitionDeploymentService(releases, versions);
    }

    @Bean
    public AdHocActionService adHocActionService(
            CaseRepository cases, CaseDefinitionVersionBindingRepository bindings,
            CaseDefinitionReleaseRepository releases, ParticipantRepository participants,
            PlanItemRepository planItems, CaseTaskRepository tasks,
            LinkedProcessService linkedProcesses, EngineGateway engine,
            CriterionEvaluator criteria, EventPublisher publisher,
            EngineOperationService operations) {
        return new AdHocActionService(cases, bindings, releases, participants,
                planItems, tasks, linkedProcesses, engine, criteria, publisher, operations,
                new JsonSchemaCaseContractValidator(), new FormValidator());
    }

    @Bean
    public EngineOperationService engineOperationService(EngineCommandRepository commands,
                                                         EventPublisher publisher) {
        return new EngineOperationService(commands, publisher);
    }

    @Bean
    public CaseTaskService caseTaskService(CaseTaskRepository tasks, CaseRepository cases,
                                           CaseDefinitionRepository definitions, EngineGateway engine,
                                           FormValidator validator, EventPublisher publisher,
                                           EngineOperationService operations) {
        return new CaseTaskService(tasks, cases, definitions, engine, validator, publisher, operations);
    }

    @Bean
    public CommentService commentService(CommentRepository comments, CaseRepository cases,
                                         EventPublisher publisher) {
        return new CommentService(comments, cases, publisher);
    }

    @Bean
    public DocumentService documentService(DocumentRepository documents, CaseRepository cases,
                                           EventPublisher publisher) {
        return new DocumentService(documents, cases, publisher);
    }

    @Bean
    public MilestoneService milestoneService(MilestoneRepository milestones, CaseRepository cases,
                                             EventPublisher publisher) {
        return new MilestoneService(milestones, cases, publisher);
    }

    @Bean
    public LinkedProcessService linkedProcessService(LinkedProcessRepository processes,
                                                     CaseRepository cases, EngineGateway engine,
                                                     EventPublisher publisher) {
        return new LinkedProcessService(processes, cases, engine, publisher);
    }
}
