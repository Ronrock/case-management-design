package org.casemgmt.starter;

import org.casemgmt.repo.AuditRepository;
import org.casemgmt.repo.CaseDefinitionRepository;
import org.casemgmt.repo.CaseRepository;
import org.casemgmt.repo.CaseTaskRepository;
import org.casemgmt.repo.CommentRepository;
import org.casemgmt.repo.DocumentRepository;
import org.casemgmt.repo.EngineCommandRepository;
import org.casemgmt.repo.EventRepository;
import org.casemgmt.repo.IdempotencyRepository;
import org.casemgmt.repo.LinkedProcessRepository;
import org.casemgmt.repo.MilestoneRepository;
import org.casemgmt.repo.ParticipantRepository;
import org.casemgmt.repo.PlanItemRepository;
import org.casemgmt.repo.SlaRepository;
import org.casemgmt.repo.WebhookRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

import javax.sql.DataSource;

@Configuration(proxyBeanMethods = false)
public class CaseManagementRepositoryConfiguration {

    @Bean
    public JdbcClient caseJdbcClient(DataSource dataSource) {
        return JdbcClient.create(dataSource);
    }

    @Bean public CaseRepository caseRepository(JdbcClient c) { return new CaseRepository(c); }

    /**
     * Takes the DataSource directly so deployment and exploded plan-item-definition inserts share
     * one physical connection and one transaction.
     */
    @Bean
    public CaseDefinitionRepository caseDefinitionRepository(DataSource dataSource) {
        return new CaseDefinitionRepository(dataSource);
    }

    @Bean public PlanItemRepository planItemRepository(JdbcClient c) { return new PlanItemRepository(c); }
    @Bean public CaseTaskRepository caseTaskRepository(JdbcClient c) { return new CaseTaskRepository(c); }
    @Bean public MilestoneRepository milestoneRepository(JdbcClient c) { return new MilestoneRepository(c); }
    @Bean public CommentRepository commentRepository(JdbcClient c) { return new CommentRepository(c); }
    @Bean public DocumentRepository documentRepository(JdbcClient c) { return new DocumentRepository(c); }
    @Bean public ParticipantRepository participantRepository(JdbcClient c) { return new ParticipantRepository(c); }
    @Bean public LinkedProcessRepository linkedProcessRepository(JdbcClient c) { return new LinkedProcessRepository(c); }
    @Bean public EventRepository eventRepository(JdbcClient c) { return new EventRepository(c); }
    @Bean public AuditRepository auditRepository(JdbcClient c) { return new AuditRepository(c); }
    @Bean public WebhookRepository webhookRepository(JdbcClient c) { return new WebhookRepository(c); }
    @Bean public IdempotencyRepository idempotencyRepository(JdbcClient c) { return new IdempotencyRepository(c); }
    @Bean public EngineCommandRepository engineCommandRepository(JdbcClient c) { return new EngineCommandRepository(c); }
    @Bean public SlaRepository slaRepository(JdbcClient c) { return new SlaRepository(c); }
}
