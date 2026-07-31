package org.casemgmt.engine;

import java.util.List;

public record EngineTaskQuery(String assignee, List<String> candidateGroups,
                              String caseId, int maxResults) {}
