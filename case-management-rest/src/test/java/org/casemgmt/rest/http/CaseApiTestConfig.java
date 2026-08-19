package org.casemgmt.rest.http;

import org.casemgmt.config.TransactionManagerConfig;
import org.casemgmt.engine.EngineGateway;
import org.casemgmt.event.EventPublisher;
import org.casemgmt.repo.AuditRepository;
import org.casemgmt.repo.CaseDefinitionRepository;
import org.casemgmt.repo.CaseRepository;
import org.casemgmt.repo.CaseTaskRepository;
import org.casemgmt.repo.CommentRepository;
import org.casemgmt.repo.DocumentRepository;
import org.casemgmt.repo.EventRepository;
import org.casemgmt.repo.IdempotencyRepository;
import org.casemgmt.repo.LinkedProcessRepository;
import org.casemgmt.repo.MilestoneRepository;
import org.casemgmt.repo.ParticipantRepository;
import org.casemgmt.repo.PlanItemRepository;
import org.casemgmt.repo.SlaRepository;
import org.casemgmt.repo.WebhookRepository;
import org.casemgmt.rest.CallerResolver;
import org.casemgmt.rest.policy.ActionPolicy;
import org.casemgmt.rules.JuelCriterionEvaluator;
import org.casemgmt.rules.PlanModelEvaluator;
import org.casemgmt.rules.PlanModelInstantiator;
import org.casemgmt.rules.StageCompletion;
import org.casemgmt.search.CaseProjectionSearchProvider;
import org.casemgmt.search.DocumentMetadataSearchProvider;
import org.casemgmt.search.SearchOrchestrator;
import org.casemgmt.search.SearchProvider;
import org.casemgmt.permissions.PermissionDecision;
import org.casemgmt.permissions.WorkerPermissionEvaluator;
import org.casemgmt.permissions.WorkerPermissionsClient;
import org.casemgmt.service.CaseDefinitionService;
import org.casemgmt.service.CaseService;
import org.casemgmt.service.CaseTaskService;
import org.casemgmt.service.CommentService;
import org.casemgmt.service.DocumentService;
import org.casemgmt.service.FormValidator;
import org.casemgmt.service.LinkedProcessService;
import org.casemgmt.service.MilestoneService;
import org.casemgmt.service.PlanItemService;
import org.casemgmt.service.TransitionApplier;
import org.casemgmt.service.WebhookService;
import org.casemgmt.sla.SlaService;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;

import javax.sql.DataSource;

/**
 * The application context Task 24's HTTP tests run against: every controller in
 * {@code org.casemgmt.rest.controller}, {@code ProblemDetailHandler}, and one Spring-managed
 * bean per core service so {@code @Transactional} is genuine (a plain {@code new} is not
 * proxied and the annotation is a silent no-op — see {@code TransactionManagerConfig}).
 *
 * <p>This is a <em>test</em> assembly, not the deliverable one: Task 26 owns the real
 * application, its engine gateway and its identity integration. What matters here is that it is
 * the same shape — real controllers, real advice, real Spring proxies, real Oracle — so the
 * things these tests assert about the wire (status codes, {@code ETag}s, {@code
 * application/problem+json} bodies, authorization refusals) are produced by the code that will
 * ship, not by a stand-in.
 *
 * <p>The {@link DataSource} is {@code OracleTestBase}'s shared pooled one, injected by
 * {@link CaseApiHttpTestBase}. It is registered with {@code destroyMethod = ""} on purpose:
 * Spring otherwise infers {@code close()} for any singleton exposing it, and closing this
 * context at the end of the run would close the pool every other test class in the JVM shares.
 */
@SpringBootConfiguration
@EnableAutoConfiguration(exclude = {
        // OracleTestBase owns the DataSource and has already migrated the schema with
        // Liquibase; Boot must not build a second one from absent spring.datasource.*
        // properties, nor register a second transaction manager alongside
        // TransactionManagerConfig's. (No Liquibase exclusion is needed: spring-boot-liquibase
        // is not on this module's classpath at all, so there is no auto-configuration to
        // switch off — an exclude for an absent class fails context startup outright.)
        DataSourceAutoConfiguration.class,
        DataSourceTransactionManagerAutoConfiguration.class})
@ComponentScan(basePackages = {"org.casemgmt.rest.controller", "org.casemgmt.rest.error"})
@Import(TransactionManagerConfig.class)
public class CaseApiTestConfig {

    static final String ENGINE_ID = "eng-test";
    static final String EVENT_TYPE_PREFIX = "org.example.cm";

    @Bean(destroyMethod = "")
    public DataSource dataSource() {
        return CaseApiHttpTestBase.sharedDataSource();
    }

    @Bean
    public JdbcClient jdbcClient(DataSource dataSource) {
        return JdbcClient.create(dataSource);
    }

    @Bean public CaseRepository caseRepository(JdbcClient j) { return new CaseRepository(j); }
    @Bean public PlanItemRepository planItemRepository(JdbcClient j) { return new PlanItemRepository(j); }
    @Bean public CaseTaskRepository caseTaskRepository(JdbcClient j) { return new CaseTaskRepository(j); }
    @Bean public MilestoneRepository milestoneRepository(JdbcClient j) { return new MilestoneRepository(j); }
    @Bean public ParticipantRepository participantRepository(JdbcClient j) { return new ParticipantRepository(j); }
    @Bean public CommentRepository commentRepository(JdbcClient j) { return new CommentRepository(j); }
    @Bean public DocumentRepository documentRepository(JdbcClient j) { return new DocumentRepository(j); }
    @Bean public LinkedProcessRepository linkedProcessRepository(JdbcClient j) { return new LinkedProcessRepository(j); }
    @Bean public EventRepository eventRepository(JdbcClient j) { return new EventRepository(j); }
    @Bean public AuditRepository auditRepository(JdbcClient j) { return new AuditRepository(j); }
    @Bean public WebhookRepository webhookRepository(JdbcClient j) { return new WebhookRepository(j); }
    @Bean public SlaRepository slaRepository(JdbcClient j) { return new SlaRepository(j); }
    @Bean public IdempotencyRepository idempotencyRepository(JdbcClient j) { return new IdempotencyRepository(j); }
    @Bean public CaseProjectionSearchProvider caseProjectionSearchProvider(CaseRepository cases,
                                                                           WorkerPermissionsClient permissions) { return new CaseProjectionSearchProvider(cases, permissions); }
    @Bean public DocumentMetadataSearchProvider documentMetadataSearchProvider(DocumentRepository documents,
                                                                               WorkerPermissionsClient permissions) { return new DocumentMetadataSearchProvider(documents, permissions); }
    @Bean public SearchOrchestrator searchOrchestrator(java.util.List<SearchProvider> providers) { return new SearchOrchestrator(providers); }
    @Bean
    public WorkerPermissionsClient workerPermissionsClient() {
        return request -> request.resources().stream()
                .collect(java.util.stream.Collectors.toMap(
                        resource -> resource.id(),
                        resource -> "erin".equals(request.workerId())
                                ? PermissionDecision.deny(resource.id())
                                : PermissionDecision.allow(resource.id())));
    }

    @Bean
    public WorkerPermissionEvaluator workerPermissionEvaluator(WorkerPermissionsClient client) {
        return new WorkerPermissionEvaluator(client);
    }

    @Bean
    public CaseDefinitionRepository caseDefinitionRepository(DataSource dataSource) {
        return new CaseDefinitionRepository(dataSource);
    }

    @Bean
    public EventPublisher eventPublisher(EventRepository events, AuditRepository audit,
                                         WebhookRepository webhooks) {
        return new EventPublisher(events, audit, webhooks, EVENT_TYPE_PREFIX, ENGINE_ID);
    }

    @Bean public EngineGateway engineGateway() { return new RecordingEngineGateway(); }
    @Bean public FormValidator formValidator() { return new FormValidator(); }
    @Bean public StageCompletion stageCompletion() { return new StageCompletion(); }
    @Bean public PlanModelInstantiator planModelInstantiator() { return new PlanModelInstantiator(); }

    @Bean
    public PlanModelEvaluator planModelEvaluator() {
        return new PlanModelEvaluator(new JuelCriterionEvaluator());
    }

    @Bean
    public TransitionApplier transitionApplier(PlanItemRepository planItems, CaseTaskRepository tasks,
                                               MilestoneRepository milestones, EngineGateway engine,
                                               EventPublisher publisher) {
        return new TransitionApplier(planItems, tasks, milestones, engine, publisher);
    }

    @Bean
    public CaseService caseService(CaseRepository cases, CaseDefinitionRepository definitions,
                                   PlanItemRepository planItems, MilestoneRepository milestones,
                                   ParticipantRepository participants, PlanModelEvaluator evaluator,
                                   PlanModelInstantiator instantiator, StageCompletion stageCompletion,
                                   TransitionApplier applier, EventPublisher publisher) {
        return new CaseService(cases, definitions, planItems, milestones, participants, evaluator,
                instantiator, stageCompletion, applier, publisher, ENGINE_ID);
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
                                            FormValidator formValidator, PlanItemService planItems,
                                            PlanItemRepository planItemRepo, EventPublisher publisher) {
        return new CaseTaskService(tasks, cases, definitions, engine, formValidator, planItems,
                planItemRepo, publisher);
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

    @Bean public WebhookService webhookService(WebhookRepository webhooks) { return new WebhookService(webhooks); }

    @Bean
    public CaseDefinitionService caseDefinitionService(CaseDefinitionRepository repo) {
        return new CaseDefinitionService(repo);
    }

    @Bean
    public SlaService slaService(SlaRepository sla, CaseRepository cases, EventPublisher publisher) {
        return new SlaService(sla, cases, publisher);
    }

    @Bean public ActionPolicy actionPolicy() { return new ActionPolicy(); }

    @Bean
    public CallerResolver callerResolver(ParticipantRepository participants) {
        return new CallerResolver(participants);
    }

    /**
     * Five users with deliberately different standing, so no authorization test can pass by
     * accident (Task 23's Critical was found precisely because every test there used a
     * privileged role). Every user carries a {@code tenant:<id>} group — that prefix is how
     * {@code CallerResolver} derives the tenant, and it is the only place a tenant comes from.
     * <ul>
     *   <li>{@code alice} — tenant t1, group {@code admin}. Creates the cases in these tests, so
     *       {@code CaseService.create} makes her their {@code owner} participant, and deploys the
     *       fixture definition, which {@code ActionPolicy.listForAdministration} now requires
     *       {@code admin} for: the fully privileged path.</li>
     *   <li>{@code bob} — tenant t1, group {@code reviewers}. Never a participant, but a member
     *       of the {@code candidateGroups} entry on the fixture's human task: the candidate-group
     *       half of {@code ActionPolicy.mayActOnTask}, and nothing else.</li>
     *   <li>{@code carol} — tenant t1, and nothing else. A legitimate, authenticated user who is
     *       neither a participant nor in any candidate group nor an administrator. Every request
     *       she makes reaches the controller and is refused by {@code ActionPolicy}, not by the
     *       security filter chain.</li>
     *   <li>{@code dave} — <b>tenant t2</b>, and otherwise as privileged as it is possible to be:
     *       {@code admin} AND {@code reviewers}. He exists so a tenant test cannot pass because
     *       the caller happened to lack a role — every refusal dave gets is about the tenant
     *       boundary and nothing else.</li>
     *   <li>{@code mallory} — tenant t1, with identity groups named {@code owner} and
     *       {@code handler}. Those are participant-ROLE names; as identity groups they must
     *       confer nothing (fix round 1, review finding I3).</li>
     * </ul>
     */
    @Bean
    public UserDetailsService users() {
        return new InMemoryUserDetailsManager(
                User.withUsername("alice").password("{noop}alice")
                        .authorities("users", "tenant:t1", "admin").build(),
                User.withUsername("bob").password("{noop}bob")
                        .authorities("users", "tenant:t1", "reviewers").build(),
                User.withUsername("carol").password("{noop}carol")
                        .authorities("users", "tenant:t1").build(),
                User.withUsername("dave").password("{noop}dave")
                        .authorities("users", "tenant:t2", "admin", "reviewers").build(),
                User.withUsername("mallory").password("{noop}mallory")
                        .authorities("users", "tenant:t1", "owner", "handler").build(),
                User.withUsername("erin").password("{noop}erin")
                        .authorities("users", "tenant:t1", "admin", "reviewers").build());
    }

    /**
     * Authenticate everything, and nothing else. Deliberately no method/path authorities: if a
     * request is refused in these tests it must be {@code ActionPolicy} refusing it (409
     * {@code action-not-available}), never this filter chain (403) — a test that passes because
     * a security rule blocked the route proves nothing about the controller.
     *
     * <p>CSRF is disabled because these are stateless API calls made with basic auth, which is
     * how the API is consumed; leaving it on would fail every POST/PATCH here with a 403 that,
     * again, says nothing about the controllers.
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(requests -> requests.anyRequest().authenticated())
                .httpBasic(basic -> { })
                .build();
    }
}
