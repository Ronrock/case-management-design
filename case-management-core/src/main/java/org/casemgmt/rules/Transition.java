package org.casemgmt.rules;

import org.casemgmt.domain.PlanItemState;

public record Transition(String planItemId, PlanItemState from, PlanItemState to, String reason) {}
