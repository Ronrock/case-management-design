package org.casemgmt.domain;

public enum PlanItemState {
    AVAILABLE, ENABLED, ACTIVE, COMPLETED, TERMINATED;

    public boolean isEnded() {
        return this == COMPLETED || this == TERMINATED;
    }
}
