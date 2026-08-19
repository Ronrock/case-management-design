package org.casemgmt.repo;

import org.casemgmt.domain.CaseState;

import java.util.List;

/**
 * Projection-search filters for case discovery.
 *
 * <p>This is deliberately separate from {@link CaseQuery}. The ordinary case list endpoint is a
 * transactional listing API; orchestrated search is a projection-facing capability that needs a
 * free-text term, provider metadata and room for future ranking without changing the list
 * endpoint's contract.
 */
public record CaseSearchQuery(String tenantId, String text, List<CaseState> states,
                              String assignee, String caseDefKey, String businessKey,
                              int offset, int limit) {

    public CaseSearchQuery {
        states = states == null ? List.of() : List.copyOf(states);
        text = text == null || text.isBlank() ? null : text.trim();
    }
}
