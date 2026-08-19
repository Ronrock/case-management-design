package org.casemgmt.service;

import org.casemgmt.domain.CaseIds;
import org.casemgmt.domain.CaseInstance;
import org.casemgmt.error.NotFoundException;
import org.casemgmt.event.CaseEvent;
import org.casemgmt.event.EventPublisher;
import org.casemgmt.event.EventTypes;
import org.casemgmt.repo.CaseRepository;
import org.casemgmt.repo.DocumentRepository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public class DocumentService {

    private final DocumentRepository documents;
    private final CaseRepository cases;
    private final EventPublisher publisher;

    public DocumentService(DocumentRepository documents, CaseRepository cases,
                           EventPublisher publisher) {
        this.documents = documents;
        this.cases = cases;
        this.publisher = publisher;
    }

    @Transactional
    public DocumentRepository.DocumentRow add(String caseId, String name, String category,
                                              String mimeType, Long sizeBytes, String contentUrl,
                                              Actor actor) {
        CaseInstance c = cases.require(caseId);
        String id = CaseIds.newId();
        String cleanName = required(name, "name");
        String cleanCategory = clean(category);
        String cleanMimeType = clean(mimeType);
        String cleanContentUrl = required(contentUrl, "contentUrl");
        documents.insert(id, caseId, cleanName, cleanCategory, cleanMimeType,
                sizeBytes, cleanContentUrl, actor.userId());

        publisher.publish(new CaseEvent(CaseIds.newId(), publisher.engineId(),
                EventTypes.DOCUMENT_ADDED, caseId, c.tenantId(), OffsetDateTime.now(),
                Map.of("documentId", id, "name", cleanName, "uploadedBy", actor.userId())));
        publisher.audit(caseId, c.tenantId(), actor.userId(), "document.add", "Document", id,
                null, Map.of("name", cleanName,
                        "category", cleanCategory == null ? "" : cleanCategory));

        return documents.findById(caseId, id).orElseThrow();
    }

    public List<DocumentRepository.DocumentRow> forCase(String caseId) {
        return documents.findByCase(caseId);
    }

    @Transactional
    public void remove(String caseId, String documentId, Actor actor) {
        CaseInstance c = cases.require(caseId);
        DocumentRepository.DocumentRow row = documents.findById(caseId, documentId)
                .orElseThrow(() -> new NotFoundException("Document", documentId));
        documents.delete(caseId, documentId);

        publisher.publish(new CaseEvent(CaseIds.newId(), publisher.engineId(),
                EventTypes.DOCUMENT_REMOVED, caseId, c.tenantId(), OffsetDateTime.now(),
                Map.of("documentId", documentId, "name", row.name(), "removedBy", actor.userId())));
        publisher.audit(caseId, c.tenantId(), actor.userId(), "document.remove", "Document",
                documentId, null, Map.of("name", row.name()));
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
