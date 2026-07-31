package org.casemgmt.engine;

import java.util.Map;

public record StartProcessRequest(String caseId, String planItemId,
                                  String processDefinitionKey, Map<String, Object> variables) {}
