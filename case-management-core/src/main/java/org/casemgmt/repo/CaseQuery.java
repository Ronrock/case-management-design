package org.casemgmt.repo;

import org.casemgmt.domain.CaseState;

import java.util.List;

/**
 * Filters for {@link CaseRepository#query}.
 *
 * <p>{@code states} is a list, not a single {@link CaseState} (Task 24 fix round 1, review
 * finding I6): {@code openapi-specs.md} declares {@code state} as a repeatable query parameter,
 * and a worklist genuinely wants {@code OPEN} plus {@code CLAIMED} in one call rather than one
 * request per state. An empty or null list means "any state".
 *
 * <p>{@code offset}/{@code limit} stay row-based here — the {@code page}/{@code pageSize}
 * translation belongs to the REST layer, which is where the spec's pagination vocabulary lives.
 */
public record CaseQuery(String tenantId, List<CaseState> states, String assignee,
                        String caseDefKey, String businessKey, int offset, int limit) {

    public CaseQuery {
        states = states == null ? List.of() : List.copyOf(states);
    }
}
