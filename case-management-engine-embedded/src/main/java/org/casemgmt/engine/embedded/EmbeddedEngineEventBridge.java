package org.casemgmt.engine.embedded;

import org.casemgmt.projection.CaseProjectionPort;
import org.casemgmt.projection.ProcessCompletionObservation;
import org.casemgmt.projection.TaskObservation;
import org.casemgmt.projection.ActivityObservation;
import org.operaton.bpm.engine.impl.history.event.HistoricProcessInstanceEventEntity;
import org.operaton.bpm.engine.impl.history.event.HistoryEvent;
import org.operaton.bpm.spring.boot.starter.event.ExecutionEvent;
import org.operaton.bpm.spring.boot.starter.event.TaskEvent;
import org.springframework.context.event.EventListener;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;

/** Translates Operaton's built-in Spring event bridge into the engine-neutral projection port. */
public class EmbeddedEngineEventBridge {

    private final CaseProjectionPort projections;
    private final ProcessCaseCorrelation correlation;
    private final ProcessActivityClassifier classifier;

    public EmbeddedEngineEventBridge(CaseProjectionPort projections,
                                     ProcessCaseCorrelation correlation) {
        this(projections, correlation, (definition, activity) -> java.util.Optional.empty());
    }

    public EmbeddedEngineEventBridge(CaseProjectionPort projections,
                                     ProcessCaseCorrelation correlation,
                                     ProcessActivityClassifier classifier) {
        this.projections = projections;
        this.correlation = correlation;
        this.classifier = classifier;
    }

    @EventListener
    public void onTask(TaskEvent event) {
        if (event.getProcessInstanceId() == null) {
            return;
        }
        String caseId = correlation.caseId(event.getProcessInstanceId());
        if (caseId == null) {
            return;
        }
        OffsetDateTime observedAt = OffsetDateTime.now(ZoneOffset.UTC);
        Date engineDate = event.getLastUpdated() != null
                ? event.getLastUpdated() : event.getCreateTime();
        ProcessActivityClassifier.TaskMetadata metadata = classifier.taskMetadata(
                event.getProcessDefinitionId(), event.getTaskDefinitionKey());
        projections.observe(new TaskObservation(caseId, event.getId(),
                // One execution commonly visits several sequential user tasks. The task id is
                // the stable per-occurrence key; using executionId collapses those occurrences
                // into one CM_PLAN_ITEM and breaks repetition/multi-instance projection.
                event.getId(),
                event.getTaskDefinitionKey(), event.getName(), event.getEventName(),
                event.getAssignee(), metadata.candidateGroups(), metadata.formKey(), event.getPriority(),
                at(event.getDueDate()), engineDate == null ? observedAt : at(engineDate), observedAt));
    }

    /** Execution events are consumed so tagged-stage/milestone mapping can evolve at this adapter. */
    @EventListener
    public void onExecution(ExecutionEvent event) {
        if (event.getProcessInstanceId() == null || event.getActivityInstanceId() == null) return;
        String caseId = event.getProcessBusinessKey() != null
                ? event.getProcessBusinessKey() : correlation.caseId(event.getProcessInstanceId());
        if (caseId == null) return;
        classifier.classify(event.getProcessDefinitionId(), event.getCurrentActivityId())
                .ifPresent(classification -> {
                    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
                    projections.observe(new ActivityObservation(caseId,
                            event.getActivityInstanceId(), event.getCurrentActivityId(),
                            event.getCurrentActivityName(), classification.kind(),
                            classification.milestoneId(), event.getEventName(), now, now));
                });
    }

    @EventListener
    public void onHistory(HistoryEvent event) {
        if (!(event instanceof HistoricProcessInstanceEventEntity process)
                || process.getEndTime() == null || process.getProcessInstanceId() == null) {
            return;
        }
        String caseId = process.getBusinessKey() != null
                ? process.getBusinessKey() : correlation.caseId(process.getProcessInstanceId());
        if (caseId == null) {
            return;
        }
        OffsetDateTime observedAt = OffsetDateTime.now(ZoneOffset.UTC);
        projections.observe(new ProcessCompletionObservation(caseId,
                process.getProcessInstanceId(), process.getProcessDefinitionKey(),
                process.getDeleteReason() == null ? "completed" : "cancelled",
                at(process.getEndTime()), observedAt));
    }

    private static OffsetDateTime at(Date date) {
        return date == null ? null : date.toInstant().atOffset(ZoneOffset.UTC);
    }
}
