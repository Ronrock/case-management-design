package org.casemgmt.rest.http;

import org.casemgmt.config.TransactionManagerConfig;
import org.casemgmt.engine.EngineGateway;
import org.casemgmt.event.AesGcmWebhookSecretCodec;
import org.casemgmt.event.EventPublisher;
import org.casemgmt.event.WebhookSecretCodec;
import org.casemgmt.event.WebhookSecretStore;
import org.casemgmt.repo.AuditRepository;
import org.casemgmt.repo.CaseDefinitionRepository;
import org.casemgmt.repo.CaseDefinitionReleaseRepository;
import org.casemgmt.repo.CaseDefinitionVersionBindingRepository;
import org.casemgmt.repo.CaseRepository;
import org.casemgmt.repo.CaseTaskRepository;
import org.casemgmt.repo.CommentRepository;
import org.casemgmt.repo.DocumentRepository;
import org.casemgmt.repo.DatabaseWebhookSecretStore;
import org.casemgmt.repo.EventRepository;
import org.casemgmt.repo.EngineCommandRepository;
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
import org.casemgmt.search.CaseProjectionSearchProvider;
import org.casemgmt.search.DocumentMetadataSearchProvider;
import org.casemgmt.search.SearchOrchestrator;
import org.casemgmt.search.SearchProvider;
import org.casemgmt.permissions.PermissionDecision;
import org.casemgmt.permissions.WorkerPermissionEvaluator;
import org.casemgmt.permissions.WorkerPermissionsClient;
import org.casemgmt.projection.ActiveBpmnCaseRepository;
import org.casemgmt.projection.RemotePollingCheckpointRepository;
import org.casemgmt.orchestration.BpmnOrchestration;
import org.casemgmt.orchestration.CaseOrchestration;
import org.casemgmt.orchestration.CaseOrchestrationRegistry;
import org.casemgmt.orchestration.EngineDeploymentIdentity;
import org.casemgmt.orchestration.OrchestrationDeploymentPort;
import org.casemgmt.release.ReleaseStatus;
import org.casemgmt.service.CaseDefinitionService;
import org.casemgmt.service.CaseDefinitionReleaseService;
import org.casemgmt.service.CaseDefinitionVersionService;
import org.casemgmt.service.AdHocActionService;
import org.casemgmt.service.CaseService;
import org.casemgmt.service.CaseTaskService;
import org.casemgmt.service.EngineOperationService;
import org.casemgmt.service.CommentService;
import org.casemgmt.service.DocumentService;
import org.casemgmt.service.FormValidator;
import org.casemgmt.service.LinkedProcessService;
import org.casemgmt.service.MilestoneService;
import org.casemgmt.service.WebhookService;
import org.casemgmt.service.CombinedCaseDefinitionDeploymentService;
import org.casemgmt.sla.SlaService;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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

    @Bean
    public CaseRepository caseRepository(DataSource dataSource) {
        return new CaseRepository(dataSource);
    }
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
    @Bean public EngineCommandRepository engineCommandRepository(JdbcClient j) {
        return new EngineCommandRepository(j);
    }
    @Bean public ActiveBpmnCaseRepository activeBpmnCaseRepository(JdbcClient j) {
        return new ActiveBpmnCaseRepository(j);
    }
    @Bean public RemotePollingCheckpointRepository remotePollingCheckpointRepository(JdbcClient j) {
        return new RemotePollingCheckpointRepository(j);
    }
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
    public CaseDefinitionReleaseRepository caseDefinitionReleaseRepository(DataSource dataSource) {
        return new CaseDefinitionReleaseRepository(dataSource);
    }

    @Bean
    public CaseDefinitionVersionBindingRepository caseDefinitionVersionBindingRepository(
            DataSource dataSource) {
        return new CaseDefinitionVersionBindingRepository(dataSource);
    }

    @Bean
    public EventPublisher eventPublisher(EventRepository events, AuditRepository audit,
                                         WebhookRepository webhooks) {
        return new EventPublisher(events, audit, webhooks, EVENT_TYPE_PREFIX, ENGINE_ID);
    }

    @Bean
    @ConditionalOnProperty(prefix = "casemgmt.test", name = "remote", havingValue = "false", matchIfMissing = true)
    public EngineGateway embeddedEngineGateway() { return new RecordingEngineGateway(); }

    @Bean
    @ConditionalOnProperty(prefix = "casemgmt.test", name = "remote", havingValue = "true")
    public EngineGateway remoteEngineGateway() { return new DeferredRecordingEngineGateway(); }
    @Bean public EngineOperationService engineOperationService(EngineCommandRepository commands,
                                                               EventPublisher publisher) {
        return new EngineOperationService(commands, publisher);
    }
    @Bean public FormValidator formValidator() { return new FormValidator(); }
    @Bean
    public BpmnOrchestration bpmnOrchestration(
            EngineGateway engine, LinkedProcessRepository processes,
            CaseDefinitionVersionBindingRepository bindings) {
        return new BpmnOrchestration(engine, processes, bindings);
    }

    @Bean
    public CaseOrchestrationRegistry caseOrchestrationRegistry(
            java.util.List<CaseOrchestration> orchestrations) {
        return new CaseOrchestrationRegistry(orchestrations);
    }

    @Bean
    public CaseService caseService(CaseRepository cases, CaseDefinitionRepository definitions,
                                   PlanItemRepository planItems, ParticipantRepository participants,
                                   CaseOrchestrationRegistry orchestrations,
                                   EventPublisher publisher) {
        return new CaseService(cases, definitions, planItems, participants,
                orchestrations, publisher, ENGINE_ID);
    }

    @Bean
    public CaseTaskService caseTaskService(CaseTaskRepository tasks, CaseRepository cases,
                                            CaseDefinitionRepository definitions, EngineGateway engine,
                                            FormValidator formValidator, EventPublisher publisher,
                                            EngineOperationService operations) {
        return new CaseTaskService(tasks, cases, definitions, engine, formValidator, publisher, operations);
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
    public MilestoneService milestoneService(MilestoneRepository milestones) {
        return new MilestoneService(milestones);
    }

    @Bean
    public LinkedProcessService linkedProcessService(LinkedProcessRepository processes,
                                                      CaseRepository cases, EngineGateway engine,
                                                      EventPublisher publisher) {
        return new LinkedProcessService(processes, cases, engine, publisher);
    }

    @Bean
    public WebhookSecretCodec webhookSecretCodec() {
        return new AesGcmWebhookSecretCodec("test",
                "0123456789abcdef0123456789abcdef".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Bean
    public WebhookSecretStore webhookSecretStore(WebhookRepository webhooks,
                                                 WebhookSecretCodec codec) {
        return new DatabaseWebhookSecretStore(webhooks, codec);
    }

    @Bean
    public WebhookService webhookService(WebhookRepository webhooks, WebhookSecretStore secrets,
                                         AuditRepository audit) {
        return new WebhookService(webhooks, secrets, audit);
    }

    @Bean
    public CaseDefinitionService caseDefinitionService(CaseDefinitionRepository repo) {
        return new CaseDefinitionService(repo);
    }

    @Bean
    public OrchestrationDeploymentPort orchestrationDeployments() {
        return (releaseId, definitionKey, tenantId, content, mediaType) ->
                new OrchestrationDeploymentPort.DeploymentResult(ReleaseStatus.ACTIVE,
                        new EngineDeploymentIdentity("test-deployment-" + releaseId,
                                definitionKey + ":1:exact", definitionKey, 1, tenantId), null);
    }

    @Bean
    public CaseDefinitionReleaseService caseDefinitionReleaseService(
            CaseDefinitionReleaseRepository repo, OrchestrationDeploymentPort orchestrationDeployments) {
        return new CaseDefinitionReleaseService(repo, orchestrationDeployments);
    }

    @Bean
    public CaseDefinitionVersionService caseDefinitionVersionService(
            CaseDefinitionReleaseRepository releases,
            CaseDefinitionVersionBindingRepository bindings,
            CaseDefinitionService definitions,
            SlaRepository calendars) {
        return new CaseDefinitionVersionService(releases, bindings, definitions, calendars);
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
            LinkedProcessService linkedProcesses, EngineGateway engine,
            EventPublisher publisher, EngineOperationService operations) {
        return new AdHocActionService(cases, bindings, releases, participants,
                linkedProcesses, engine, new JuelCriterionEvaluator(), publisher, operations,
                new org.casemgmt.release.JsonSchemaCaseContractValidator(), new org.casemgmt.service.FormValidator());
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
