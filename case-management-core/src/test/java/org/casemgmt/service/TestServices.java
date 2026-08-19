package org.casemgmt.service;

import org.casemgmt.engine.EngineGateway;
import org.casemgmt.event.EventPublisher;
import org.casemgmt.repo.*;
import org.casemgmt.rules.*;
import org.casemgmt.sla.SlaService;
import org.casemgmt.sla.SlaSweeper;
import org.springframework.jdbc.core.simple.JdbcClient;

import javax.sql.DataSource;

/**
 * Test-only wiring so each test does not repeat eight constructor calls.
 *
 * <p>Takes a {@link DataSource}, not a pre-built {@link JdbcClient}: {@link CaseDefinitionRepository}
 * needs the {@link DataSource} directly (it runs its own atomic multi-statement insert on a
 * single borrowed connection — see its Javadoc), so a plain {@code JdbcClient} is not enough to
 * construct every repository this wiring needs. Every other repository is built from a
 * {@link JdbcClient} wrapping the same {@link DataSource}, so this opens no second connection
 * pool.
 */
public final class TestServices {

    private TestServices() {}

    public static CaseService caseService(DataSource dataSource, EngineGateway gateway) {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        var publisher = new EventPublisher(new EventRepository(jdbc), new AuditRepository(jdbc),
                new WebhookRepository(jdbc), "org.example.cm", "eng-test");
        var evaluator = new PlanModelEvaluator(new JuelCriterionEvaluator());
        var applier = new TransitionApplier(new PlanItemRepository(jdbc), new CaseTaskRepository(jdbc),
                new MilestoneRepository(jdbc), gateway, publisher);
        return new CaseService(new CaseRepository(jdbc), new CaseDefinitionRepository(dataSource),
                new PlanItemRepository(jdbc), new MilestoneRepository(jdbc),
                new ParticipantRepository(jdbc), evaluator, new PlanModelInstantiator(),
                new StageCompletion(), applier, publisher, "eng-test");
    }

    public static PlanItemService planItemService(DataSource dataSource, EngineGateway gateway) {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        var publisher = new EventPublisher(new EventRepository(jdbc), new AuditRepository(jdbc),
                new WebhookRepository(jdbc), "org.example.cm", "eng-test");
        var applier = new TransitionApplier(new PlanItemRepository(jdbc), new CaseTaskRepository(jdbc),
                new MilestoneRepository(jdbc), gateway, publisher);
        return new PlanItemService(new PlanItemRepository(jdbc), caseService(dataSource, gateway),
                applier, publisher, new StageCompletion());
    }

    public static CaseTaskService taskService(DataSource dataSource, EngineGateway gateway) {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        var publisher = new EventPublisher(new EventRepository(jdbc), new AuditRepository(jdbc),
                new WebhookRepository(jdbc), "org.example.cm", "eng-test");
        return new CaseTaskService(new CaseTaskRepository(jdbc), new CaseRepository(jdbc),
                new CaseDefinitionRepository(dataSource), gateway, new FormValidator(),
                planItemService(dataSource, gateway), new PlanItemRepository(jdbc), publisher);
    }

    public static CommentService commentService(DataSource dataSource) {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        var publisher = new EventPublisher(new EventRepository(jdbc), new AuditRepository(jdbc),
                new WebhookRepository(jdbc), "org.example.cm", "eng-test");
        return new CommentService(new CommentRepository(jdbc), new CaseRepository(jdbc), publisher);
    }

    public static DocumentService documentService(DataSource dataSource) {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        var publisher = new EventPublisher(new EventRepository(jdbc), new AuditRepository(jdbc),
                new WebhookRepository(jdbc), "org.example.cm", "eng-test");
        return new DocumentService(new DocumentRepository(jdbc), new CaseRepository(jdbc), publisher);
    }

    public static MilestoneService milestoneService(DataSource dataSource) {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        var publisher = new EventPublisher(new EventRepository(jdbc), new AuditRepository(jdbc),
                new WebhookRepository(jdbc), "org.example.cm", "eng-test");
        return new MilestoneService(new MilestoneRepository(jdbc), new CaseRepository(jdbc), publisher);
    }

    public static LinkedProcessService processService(DataSource dataSource, EngineGateway gateway) {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        var publisher = new EventPublisher(new EventRepository(jdbc), new AuditRepository(jdbc),
                new WebhookRepository(jdbc), "org.example.cm", "eng-test");
        return new LinkedProcessService(new LinkedProcessRepository(jdbc), new CaseRepository(jdbc),
                gateway, publisher);
    }

    /**
     * Takes a {@link JdbcClient} directly, unlike the factories above: unlike
     * {@link CaseDefinitionRepository}, nothing {@link SlaService} depends on needs a raw
     * {@link DataSource}, so there is no reason to require callers to hold one just to build
     * a {@link JdbcClient} from it here.
     */
    public static SlaService slaService(JdbcClient jdbc) {
        var publisher = new EventPublisher(new EventRepository(jdbc), new AuditRepository(jdbc),
                new WebhookRepository(jdbc), "org.example.cm", "eng-test");
        return new SlaService(new SlaRepository(jdbc), new CaseRepository(jdbc), publisher);
    }

    public static SlaSweeper slaSweeper(JdbcClient jdbc) {
        var publisher = new EventPublisher(new EventRepository(jdbc), new AuditRepository(jdbc),
                new WebhookRepository(jdbc), "org.example.cm", "eng-test");
        return new SlaSweeper(new SlaRepository(jdbc), new CaseRepository(jdbc), publisher);
    }
}
