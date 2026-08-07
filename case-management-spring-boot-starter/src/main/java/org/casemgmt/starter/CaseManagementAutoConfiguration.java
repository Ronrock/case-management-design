package org.casemgmt.starter;

import org.casemgmt.config.TransactionManagerConfig;
import org.casemgmt.engine.EngineGateway;
import org.casemgmt.event.EventPublisher;
import org.casemgmt.event.WebhookDispatcher;
import org.casemgmt.repo.*;
import org.casemgmt.rest.CallerResolver;
import org.casemgmt.rest.controller.*;
import org.casemgmt.rest.error.ProblemDetailHandler;
import org.casemgmt.rest.policy.ActionPolicy;
import org.casemgmt.rules.*;
import org.casemgmt.service.*;
import org.casemgmt.sla.SlaService;
import org.casemgmt.sla.SlaSweeper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import javax.sql.DataSource;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@AutoConfiguration
@ConditionalOnProperty(prefix = "casemgmt", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(CaseManagementProperties.class)
// TransactionManagerConfig is deliberately imported here too (not in the brief's original
// list): it is core's @Configuration + @EnableTransactionManagement class that registers the
// PlatformTransactionManager and turns on the AOP proxying every @Transactional service in
// this bean set relies on (CaseService, PlanItemService, CaseTaskService, CommentService,
// MilestoneService, LinkedProcessService, SlaService, WebhookService...). Its own Javadoc says
// explicitly that it is NOT a Spring Boot auto-configuration and that "the eventual application
// assembly imports or component-scans this class" — this starter IS that assembly. Without it,
// every @Transactional annotation wired below is silently inert (see K3/TransactionManagerConfig
// Javadoc's self-invocation warning for the general shape of that failure mode): no proxy, no
// begin/commit/rollback, and WebhookService.subscribe in particular would insert its row without
// any transactional guarantee at all.
@Import({TransactionManagerConfig.class, CaseController.class, PlanItemController.class,
        TaskController.class, CaseDefinitionController.class, CollaborationController.class,
        EventController.class, SlaController.class, ProblemDetailHandler.class})
public class CaseManagementAutoConfiguration {

    /**
     * PoC-only: webhook secrets are hashed in the database, but the dispatcher needs the
     * plaintext to sign. This in-memory map holds them for the process lifetime. A
     * production build needs a secret store or reversible encryption instead — see FINDINGS.md.
     */
    private final Map<String, String> webhookSecrets = new ConcurrentHashMap<>();

    @Bean
    public JdbcClient caseJdbcClient(DataSource dataSource) {
        return JdbcClient.create(dataSource);
    }

    @Bean public CaseRepository caseRepository(JdbcClient c) { return new CaseRepository(c); }

    /**
     * Takes the {@link DataSource} directly, not the shared {@link JdbcClient} bean (deviation
     * from the brief, which wired this with {@code JdbcClient} and would not compile):
     * {@code CaseDefinitionRepository}'s own Javadoc explains it needs the raw {@link DataSource}
     * to run a case definition's INSERT and all of its exploded plan-item-definition INSERTs as
     * one atomic unit on a single physical connection.
     */
    @Bean public CaseDefinitionRepository caseDefinitionRepository(DataSource dataSource) { return new CaseDefinitionRepository(dataSource); }
    @Bean public PlanItemRepository planItemRepository(JdbcClient c) { return new PlanItemRepository(c); }
    @Bean public CaseTaskRepository caseTaskRepository(JdbcClient c) { return new CaseTaskRepository(c); }
    @Bean public MilestoneRepository milestoneRepository(JdbcClient c) { return new MilestoneRepository(c); }
    @Bean public CommentRepository commentRepository(JdbcClient c) { return new CommentRepository(c); }
    @Bean public ParticipantRepository participantRepository(JdbcClient c) { return new ParticipantRepository(c); }
    @Bean public LinkedProcessRepository linkedProcessRepository(JdbcClient c) { return new LinkedProcessRepository(c); }
    @Bean public EventRepository eventRepository(JdbcClient c) { return new EventRepository(c); }
    @Bean public AuditRepository auditRepository(JdbcClient c) { return new AuditRepository(c); }
    @Bean public WebhookRepository webhookRepository(JdbcClient c) { return new WebhookRepository(c); }
    @Bean public IdempotencyRepository idempotencyRepository(JdbcClient c) { return new IdempotencyRepository(c); }
    @Bean public EngineCommandRepository engineCommandRepository(JdbcClient c) { return new EngineCommandRepository(c); }
    @Bean public SlaRepository slaRepository(JdbcClient c) { return new SlaRepository(c); }

    @Bean
    public EventPublisher eventPublisher(EventRepository events, AuditRepository audit,
                                         WebhookRepository webhooks, CaseManagementProperties props) {
        String prefix = props.getEvents().getTypePrefix();
        if (prefix == null || prefix.isBlank()) {
            throw new IllegalStateException(
                    "casemgmt.events.type-prefix must be set — it becomes the CloudEvents type "
                            + "namespace and there is no safe default");
        }
        return new EventPublisher(events, audit, webhooks, prefix, props.getEngineId());
    }

    @Bean public CriterionEvaluator criterionEvaluator() { return new JuelCriterionEvaluator(); }
    @Bean public PlanModelEvaluator planModelEvaluator(CriterionEvaluator c) { return new PlanModelEvaluator(c); }
    @Bean public PlanModelInstantiator planModelInstantiator() { return new PlanModelInstantiator(); }
    @Bean public StageCompletion stageCompletion() { return new StageCompletion(); }
    @Bean public FormValidator formValidator() { return new FormValidator(); }
    @Bean public ActionPolicy actionPolicy() { return new ActionPolicy(); }

    @Bean
    public CallerResolver callerResolver(ParticipantRepository participants) {
        return new CallerResolver(participants);
    }

    @Bean
    public TransitionApplier transitionApplier(PlanItemRepository planItems, CaseTaskRepository tasks,
                                               MilestoneRepository milestones, EngineGateway engine,
                                               EventPublisher publisher) {
        return new TransitionApplier(planItems, tasks, milestones, engine, publisher);
    }

    /**
     * Deviation, found in this task (Task 26), not in the brief: named explicitly, rather than
     * left to default to the method name {@code caseService}. Operaton's own Spring integration
     * ({@code operaton-bpm-spring-boot-starter}'s {@code SpringProcessEngineServicesConfiguration})
     * registers a bean literally named {@code caseService} for {@code
     * org.operaton.bpm.engine.CaseService} — its CMMN engine service, an entirely different type
     * that happens to share this bean's default Spring name. Every prior test of this starter
     * (Task 25's {@code AutoConfigurationTest}) built its context from this module's own
     * auto-configurations plus a bare {@code ApplicationContextRunner} fake, never alongside
     * Operaton's real Spring wiring — so the collision was invisible until
     * {@code case-management-poc-app} (Task 26) booted both together in one real context and hit
     * {@code BeanDefinitionOverrideException}. Nothing in this codebase looks this bean up by name
     * (verified by grep for {@code "caseService"} and any {@code @Qualifier} across all modules —
     * every consumer autowires by the distinct type {@link CaseService}), so renaming the Spring
     * bean id is behavior-preserving everywhere except the one place it was actually broken.
     */
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

    /**
     * The override below re-declares {@code @Transactional}, though it turns out to be belt and
     * braces rather than a fix: investigated as a possible K3-shaped defect (Java annotations are
     * not normally inherited across an override, so overriding {@code WebhookService.subscribe}
     * — as the brief's own sketch does, to capture the plaintext secret — without repeating
     * {@code @Transactional} looked like it would silently drop the transaction boundary). Verified
     * empirically with {@code AutoConfigurationTest.webhookServiceSubscribeOverrideStaysTransactional}
     * that it does NOT: {@code AnnotationTransactionAttributeSource} resolves {@code @Transactional}
     * by walking up the class hierarchy for a same-signature method, so the superclass's annotation
     * was already found even with the annotation stripped from the override (confirmed by actually
     * stripping it — see the Task 25 report's mechanism-stripping evidence). Kept here anyway,
     * explicitly, so a reader does not have to know that resolution detail to trust this method
     * runs in a transaction.
     */
    @Bean
    public WebhookService webhookService(WebhookRepository webhooks) {
        return new WebhookService(webhooks) {
            @Override
            @org.springframework.transaction.annotation.Transactional
            public CreatedSubscription subscribe(String tenantId, String url,
                                                 java.util.List<String> eventTypes, Actor actor) {
                CreatedSubscription created = super.subscribe(tenantId, url, eventTypes, actor);
                webhookSecrets.put(created.id(), created.secret());
                return created;
            }
        };
    }

    @Bean
    public WebhookDispatcher webhookDispatcher(WebhookRepository webhooks, EventRepository events) {
        return new WebhookDispatcher(webhooks, events, webhookSecrets::get);
    }

    /**
     * Deviation from the brief: {@code SlaService}'s constructor takes a third argument,
     * {@link EventPublisher}, which the brief's bean method omitted — it would not compile as
     * given (see {@code SlaService}'s Javadoc: every mutating method follows the
     * row + event + audit convention, so it needs the publisher to emit either).
     */
    @Bean
    public SlaService slaService(SlaRepository sla, CaseRepository cases, EventPublisher publisher) {
        return new SlaService(sla, cases, publisher);
    }

    @Bean
    public SlaSweeper slaSweeper(SlaRepository sla, CaseRepository cases, EventPublisher publisher) {
        return new SlaSweeper(sla, cases, publisher);
    }
}
