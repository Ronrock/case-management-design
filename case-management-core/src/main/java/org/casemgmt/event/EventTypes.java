package org.casemgmt.event;

/** Type suffixes. EventPublisher prepends casemgmt.events.type-prefix. */
public final class EventTypes {

    public static final String CASE_CREATED = "case.created";
    public static final String CASE_UPDATED = "case.updated";
    public static final String CASE_CLOSED = "case.closed";
    public static final String CASE_CANCELLED = "case.cancelled";
    public static final String PLAN_ITEM_TRANSITIONED = "case.planitem.transitioned";
    public static final String TASK_CREATED = "case.task.created";
    public static final String TASK_CLAIMED = "case.task.claimed";
    public static final String TASK_COMPLETED = "case.task.completed";
    public static final String MILESTONE_ACHIEVED = "case.milestone.achieved";
    public static final String COMMENT_ADDED = "case.comment.added";
    public static final String DOCUMENT_ADDED = "case.document.added";
    public static final String DOCUMENT_REMOVED = "case.document.removed";
    public static final String PROCESS_STARTED = "case.process.started";
    public static final String SLA_STARTED = "case.sla.started";
    public static final String SLA_PAUSED = "case.sla.paused";
    public static final String SLA_RESUMED = "case.sla.resumed";
    public static final String SLA_WARNING = "case.sla.warning";
    public static final String SLA_BREACHED = "case.sla.breached";

    private EventTypes() {}
}
