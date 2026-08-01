package org.casemgmt.repo;

import org.casemgmt.domain.CaseState;

public record CaseQuery(String tenantId, CaseState state, String assignee,
                        String caseDefKey, String businessKey, int offset, int limit) {}
