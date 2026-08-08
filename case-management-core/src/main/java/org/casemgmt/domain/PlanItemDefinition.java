package org.casemgmt.domain;

import java.util.List;

public record PlanItemDefinition(
        String id, String caseDefId, String defKey,
        PlanItemType type, String name, String parentStageKey,
        boolean manualActivation, boolean required, boolean repetition,
        List<String> entryCriteria, List<String> exitCriteria,
        String formKey, String processDefinitionKey, List<String> candidateGroups,
        int sortOrder) {}
