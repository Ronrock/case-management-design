package org.casemgmt.starter;

import org.casemgmt.repo.AuditRepository;
import org.casemgmt.repo.AppliedObservationRepository;
import org.casemgmt.repo.CaseDefinitionRepository;
import org.casemgmt.repo.CaseDefinitionReleaseRepository;
import org.casemgmt.repo.CaseDefinitionVersionBindingRepository;
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
import org.casemgmt.projection.CaseProjectionPort;
import org.casemgmt.projection.CaseCompletionPublisher;
import org.casemgmt.projection.JdbcCaseProjectionPort;
import org.casemgmt.projection.ActiveBpmnCaseRepository;
import org.casemgmt.projection.RemotePollingCheckpointRepository;
import org.casemgmt.domain.CaseIds;
import org.casemgmt.event.CaseEvent;
import org.casemgmt.event.EventPublisher;
import org.casemgmt.event.EventTypes;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
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

    @Bean
    public CaseRepository caseRepository(DataSource dataSource) {
        return new CaseRepository(dataSource);
    }

    /**
     * Takes the DataSource directly so deployment and exploded plan-item-definition inserts share
     * one physical connection and one transaction.
     */
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

    @Bean public PlanItemRepository planItemRepository(
            @Qualifier("caseJdbcClient") JdbcClient c) { return new PlanItemRepository(c); }
    @Bean public CaseTaskRepository caseTaskRepository(
            @Qualifier("caseJdbcClient") JdbcClient c) { return new CaseTaskRepository(c); }
    @Bean public MilestoneRepository milestoneRepository(
            @Qualifier("caseJdbcClient") JdbcClient c) { return new MilestoneRepository(c); }
    @Bean public CommentRepository commentRepository(
            @Qualifier("caseJdbcClient") JdbcClient c) { return new CommentRepository(c); }
    @Bean public DocumentRepository documentRepository(
            @Qualifier("caseJdbcClient") JdbcClient c) { return new DocumentRepository(c); }
    @Bean public ParticipantRepository participantRepository(
            @Qualifier("caseJdbcClient") JdbcClient c) { return new ParticipantRepository(c); }
    @Bean public LinkedProcessRepository linkedProcessRepository(
            @Qualifier("caseJdbcClient") JdbcClient c) { return new LinkedProcessRepository(c); }
    @Bean public EventRepository eventRepository(
            @Qualifier("caseJdbcClient") JdbcClient c) { return new EventRepository(c); }
    @Bean public AuditRepository auditRepository(
            @Qualifier("caseJdbcClient") JdbcClient c) { return new AuditRepository(c); }
    @Bean public WebhookRepository webhookRepository(
            @Qualifier("caseJdbcClient") JdbcClient c) { return new WebhookRepository(c); }
    @Bean public IdempotencyRepository idempotencyRepository(
            @Qualifier("caseJdbcClient") JdbcClient c) { return new IdempotencyRepository(c); }
    @Bean public EngineCommandRepository engineCommandRepository(
            DataSource dataSource) { return new EngineCommandRepository(dataSource); }
    @Bean public SlaRepository slaRepository(
            @Qualifier("caseJdbcClient") JdbcClient c) { return new SlaRepository(c); }
    @Bean public AppliedObservationRepository appliedObservationRepository(
            @Qualifier("caseJdbcClient") JdbcClient c) {
        return new AppliedObservationRepository(c);
    }
    @Bean
    @ConditionalOnMissingBean(CaseCompletionPublisher.class)
    public CaseCompletionPublisher caseCompletionPublisher(
            CaseRepository cases, EventPublisher publisher, CaseManagementProperties properties) {
        if (properties.getEngine().getMode() == CaseManagementProperties.EngineMode.embedded) {
            // Embedded root completion is owned by DefaultEngineObservationHandler.
            return CaseCompletionPublisher.none();
        }
        // Remote ingestion still reaches the lower-level projection path until Workstream 5.
        return (caseId, state, completedAt) -> {
            var completed = cases.require(caseId);
            String eventType = "CANCELLED".equals(state)
                    ? EventTypes.CASE_CANCELLED : EventTypes.CASE_CLOSED;
            publisher.publish(new CaseEvent(CaseIds.newId(), publisher.engineId(), eventType,
                    caseId, completed.tenantId(), completedAt,
                    java.util.Map.of("state", state, "source", "root-process")));
            publisher.audit(caseId, completed.tenantId(), "engine", "case.complete-root-process",
                    "Case", caseId, java.util.Map.of("state", "ACTIVE"),
                    java.util.Map.of("state", state));
        };
    }
    @Bean public CaseProjectionPort caseProjectionPort(
            @Qualifier("caseJdbcClient") JdbcClient c,
            CaseCompletionPublisher completionPublisher) {
        return new JdbcCaseProjectionPort(c, completionPublisher);
    }
    @Bean public RemotePollingCheckpointRepository remotePollingCheckpointRepository(
            @Qualifier("caseJdbcClient") JdbcClient c) {
        return new RemotePollingCheckpointRepository(c);
    }
    @Bean public ActiveBpmnCaseRepository activeBpmnCaseRepository(
            @Qualifier("caseJdbcClient") JdbcClient c) {
        return new ActiveBpmnCaseRepository(c);
    }
}
