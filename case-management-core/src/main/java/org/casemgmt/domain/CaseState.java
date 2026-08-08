package org.casemgmt.domain;

import java.util.EnumSet;
import java.util.Set;

public enum CaseState {
    CREATED, ACTIVE, SUSPENDED, CLOSED, CANCELLED;

    private static final java.util.Map<CaseState, Set<CaseState>> ALLOWED = java.util.Map.of(
            CREATED,   EnumSet.of(ACTIVE, CANCELLED),
            ACTIVE,    EnumSet.of(SUSPENDED, CLOSED, CANCELLED),
            SUSPENDED, EnumSet.of(ACTIVE, CANCELLED),
            CLOSED,    EnumSet.of(ACTIVE),          // reactivate
            CANCELLED, EnumSet.noneOf(CaseState.class));

    public boolean canTransitionTo(CaseState target) {
        return ALLOWED.get(this).contains(target);
    }

    public boolean isTerminal() {
        return this == CANCELLED;
    }
}
