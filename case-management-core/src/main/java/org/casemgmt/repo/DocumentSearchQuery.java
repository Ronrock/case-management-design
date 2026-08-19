package org.casemgmt.repo;

public record DocumentSearchQuery(String tenantId, String text, String caseId, String category,
                                  String mimeType, int offset, int limit) {

    public DocumentSearchQuery {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("Document search requires a tenant");
        }
        text = text == null || text.isBlank() ? null : text.trim();
        caseId = caseId == null || caseId.isBlank() ? null : caseId.trim();
        category = category == null || category.isBlank() ? null : category.trim();
        mimeType = mimeType == null || mimeType.isBlank() ? null : mimeType.trim();
    }
}
