package org.casemgmt.starter;

import org.casemgmt.engine.EngineGateway;
import org.casemgmt.event.EventPublisher;
import org.casemgmt.repo.AuditRepository;
import org.casemgmt.repo.CaseDefinitionRepository;
import org.casemgmt.repo.CaseRepository;
import org.casemgmt.repo.CaseTaskRepository;
import org.casemgmt.repo.CommentRepository;
import org.casemgmt.repo.EventRepository;
import org.casemgmt.repo.LinkedProcessRepository;
import org.casemgmt.repo.MilestoneRepository;
import org.casemgmt.repo.ParticipantRepository;
import org.casemgmt.repo.PlanItemRepository;
import org.casemgmt.repo.WebhookRepository;
import org.casemgmt.rules.PlanModelEvaluator;
import org.casemgmt.rules.PlanModelInstantiator;
import org.casemgmt.rules.StageCompletion;
import org.casemgmt.service.CaseDefinitionService;
import org.casemgmt.service.CaseService;
import org.casemgmt.service.CaseTaskService;
import org.casemgmt.service.CommentService;
import org.casemgmt.service.FormValidator;
import org.casemgmt.service.LinkedProcessService;
import org.casemgmt.service.MilestoneService;
import org.casemgmt.service.PlanItemService;
import org.casemgmt.service.TransitionApplier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class CaseManagementServiceConfiguration {

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

    @Bean
    public TransitionApplier transitionApplier(PlanItemRepository planItems, CaseTaskRepository tasks,
                                               LinkedProcessRepository linkedProcesses,
                                               MilestoneRepository milestones,
                                               EngineGateway engine, EventPublisher publisher) {
        return new TransitionApplier(planItems, tasks, linkedProcesses, milestones, engine, publisher);
    }

    @Bean(name = "caseManagementCaseService")
    public CaseService caseService(CaseRepository cases, CaseDefinitionRepository definitions,
                                   PlanItemRepository planItems, MilestoneRepository milestones,
                                   ParticipantRepository participants, PlanModelEvaluator evaluator,
                                   PlanModelInstantiator instantiator, StageCompletion completion,
                                   TransitionApplier applier, EventPublisher publisher,
                                   CaseManagementProperties props) {
        return new CaseService(cases, definitions, planItems, milestones, participants, evaluator,
                instantiator, completion, applier, publisher, props.getEngineId());
    }

    @Bean
    public CaseDefinitionService caseDefinitionService(CaseDefinitionRepository repo) {
        return new CaseDefinitionService(repo);
    }

    @Bean
    public PlanItemService planItemService(PlanItemRepository planItems, CaseService cases,
                                           TransitionApplier applier, EventPublisher publisher,
                                           StageCompletion stageCompletion) {
        return new PlanItemService(planItems, cases, applier, publisher, stageCompletion);
    }

    @Bean
    public CaseTaskService caseTaskService(CaseTaskRepository tasks, CaseRepository cases,
                                           CaseDefinitionRepository definitions, EngineGateway engine,
                                           FormValidator validator, PlanItemService planItems,
                                           PlanItemRepository planItemRepo, EventPublisher publisher) {
        return new CaseTaskService(tasks, cases, definitions, engine, validator, planItems,
                planItemRepo, publisher);
    }

    @Bean
    public CommentService commentService(CommentRepository comments, CaseRepository cases,
                                         EventPublisher publisher) {
        return new CommentService(comments, cases, publisher);
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
