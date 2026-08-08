package org.casemgmt.domain;

import java.util.UUID;

public final class CaseIds {

    private CaseIds() {}

    /** Globally unique case id: {engineId}:{uuid} — spec §Appendix F. */
    public static String newCaseId(String engineId) {
        if (engineId == null || engineId.isBlank()) {
            throw new IllegalArgumentException("engineId must not be blank");
        }
        if (engineId.indexOf(':') >= 0) {
            throw new IllegalArgumentException("engineId must not contain ':' — got " + engineId);
        }
        return engineId + ":" + UUID.randomUUID();
    }

    public static String engineIdOf(String caseId) {
        int sep = caseId.indexOf(':');
        if (sep < 0) throw new IllegalArgumentException("Not a global case id: " + caseId);
        return caseId.substring(0, sep);
    }

    /** Local (non-case) entity id: plan items, tasks, comments, events. */
    public static String newId() {
        return UUID.randomUUID().toString();
    }
}
