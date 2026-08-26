package org.casemgmt.engine;

import java.util.Map;

public record MessageCorrelationRequest(
        String caseId, String messageName, Map<String, Object> variables) { }
