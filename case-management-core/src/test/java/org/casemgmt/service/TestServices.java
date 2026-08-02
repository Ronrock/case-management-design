package org.casemgmt.service;

import org.casemgmt.engine.EngineGateway;
import org.casemgmt.event.EventPublisher;
import org.casemgmt.repo.*;
import org.casemgmt.rules.*;
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
}
