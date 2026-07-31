package org.casemgmt.engine;

import java.time.OffsetDateTime;

public record EngineTaskRef(String engineTaskId, String name, String assignee,
                            String caseId, OffsetDateTime createdAt) {}
