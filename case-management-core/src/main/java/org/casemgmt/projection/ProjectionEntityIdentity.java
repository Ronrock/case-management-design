package org.casemgmt.projection;

/** Case/process authority coordinates for one engine-projected entity. */
public record ProjectionEntityIdentity(
        String caseId,
        String processInstanceId,
        Kind kind,
        String entityId,
        String relatedActivityInstanceId) {

    public enum Kind { USER_TASK, ACTIVITY, MILESTONE }
}
