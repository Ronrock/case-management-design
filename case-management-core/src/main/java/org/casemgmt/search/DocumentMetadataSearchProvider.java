package org.casemgmt.search;

import org.casemgmt.permissions.PermissionDecision;
import org.casemgmt.permissions.PermissionActions;
import org.casemgmt.permissions.ResourceTypes;
import org.casemgmt.permissions.WorkerPermissionRequest;
import org.casemgmt.permissions.WorkerPermissionResource;
import org.casemgmt.permissions.WorkerPermissionsClient;
import org.casemgmt.repo.DocumentRepository;
import org.casemgmt.repo.DocumentSearchQuery;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class DocumentMetadataSearchProvider implements SearchProvider {

    public static final String PROVIDER_ID = "document-metadata";

    private final DocumentRepository documents;
    private final WorkerPermissionsClient permissions;

    public DocumentMetadataSearchProvider(DocumentRepository documents,
                                          WorkerPermissionsClient permissions) {
        this.documents = documents;
        this.permissions = permissions;
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public List<SearchScope> supportedScopes() {
        return List.of(SearchScope.DOCUMENTS);
    }

    @Override
    public int estimateCost(SearchQuery query) {
        return exactIdentifier(query.q()) ? 2 : 25;
    }

    @Override
    public SearchProviderStatus status() {
        return new SearchProviderStatus(PROVIDER_ID, "available", supportedScopes(),
                false, true, 30, 0, false, List.of());
    }

    @Override
    public SearchProviderResult search(SearchQuery query) {
        if (query.workerId() == null) {
            SearchWarning warning = authUnavailable("Search request has no worker identity");
            return new SearchProviderResult(List.of(), List.of(), List.of(warning),
                    degradedStatus(warning));
        }

        DocumentSearchQuery documentQuery = new DocumentSearchQuery(query.tenantId(), query.q(),
                stringFilter(query, "caseId"), stringFilter(query, "category"),
                stringFilter(query, "mimeType"), query.offset(),
                query.pageSize());
        List<DocumentRepository.DocumentRow> candidates = documents.search(documentQuery);
        if (candidates.isEmpty()) {
            return new SearchProviderResult(List.of(), List.of(), List.of(), status());
        }

        Map<String, PermissionDecision> decisions;
        try {
            decisions = permissions.evaluate(new WorkerPermissionRequest(query.tenantId(),
                    query.workerId(), query.groups(), PermissionActions.DOCUMENT_READ,
                    ResourceTypes.DOCUMENT,
                    candidates.stream()
                            .map(row -> new WorkerPermissionResource(row.id(),
                                    permissionContext(row)))
                            .toList()));
        } catch (RuntimeException e) {
            SearchWarning warning = authUnavailable("Document authorization is unavailable");
            return new SearchProviderResult(List.of(), List.of(), List.of(warning),
                    degradedStatus(warning));
        }

        List<SearchResultItem> items = candidates.stream()
                .filter(row -> decisions.getOrDefault(row.id(),
                        PermissionDecision.deny(row.id())).allowed())
                .filter(row -> canDiscloseMatch(row, query.q(), decisions.get(row.id())))
                .map(row -> toResult(row, query.q(), decisions.get(row.id())))
                .toList();
        return new SearchProviderResult(items, List.of(), List.of(), status());
    }

    private static SearchResultItem toResult(DocumentRepository.DocumentRow row, String q,
                                             PermissionDecision decision) {
        List<String> matchedFields = matchedFields(row, q);
        return new SearchResultItem(row.id(), SearchResultType.DOCUMENT, row.caseId(),
                fieldAllowed(decision, "name") ? row.name() : "Document " + row.id(),
                summary(row, decision), PROVIDER_ID, score(row, q, matchedFields),
                matchedFields.stream().filter(field -> fieldAllowed(decision, field)).toList(),
                highlights(row, q, decision), resource(row, decision), row.uploadedAt(), "fresh");
    }

    private static Map<String, Object> permissionContext(DocumentRepository.DocumentRow row) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("caseId", row.caseId());
        context.put("name", row.name());
        putIfPresent(context, "category", row.category());
        putIfPresent(context, "mimeType", row.mimeType());
        putIfPresent(context, "uploadedBy", row.uploadedBy());
        return context;
    }

    private static Map<String, Object> resource(DocumentRepository.DocumentRow row,
                                                PermissionDecision decision) {
        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("id", row.id());
        resource.put("caseId", row.caseId());
        putIfAllowed(resource, decision, "name", row.name());
        putIfAllowed(resource, decision, "category", row.category());
        putIfAllowed(resource, decision, "mimeType", row.mimeType());
        putIfAllowed(resource, decision, "sizeBytes", row.sizeBytes());
        putIfAllowed(resource, decision, "contentUrl", row.contentUrl());
        putIfAllowed(resource, decision, "uploadedBy", row.uploadedBy());
        resource.put("uploadedAt", row.uploadedAt());
        return resource;
    }

    private static String summary(DocumentRepository.DocumentRow row, PermissionDecision decision) {
        List<String> parts = new ArrayList<>();
        if (fieldAllowed(decision, "category") && row.category() != null) {
            parts.add(row.category());
        }
        if (fieldAllowed(decision, "mimeType") && row.mimeType() != null) {
            parts.add(row.mimeType());
        }
        if (fieldAllowed(decision, "sizeBytes") && row.sizeBytes() != null) {
            parts.add(row.sizeBytes() + " bytes");
        }
        return String.join(" / ", parts);
    }

    private static List<String> matchedFields(DocumentRepository.DocumentRow row, String q) {
        if (q == null || q.isBlank()) {
            return List.of();
        }
        String term = q.toLowerCase(Locale.ROOT);
        List<String> fields = new ArrayList<>();
        if (row.id().equalsIgnoreCase(q)) {
            fields.add("documentId");
        }
        if (row.name() != null && row.name().toLowerCase(Locale.ROOT).contains(term)) {
            fields.add("name");
        }
        if (row.category() != null && row.category().toLowerCase(Locale.ROOT).contains(term)) {
            fields.add("category");
        }
        if (row.mimeType() != null && row.mimeType().toLowerCase(Locale.ROOT).contains(term)) {
            fields.add("mimeType");
        }
        return fields;
    }

    private static boolean canDiscloseMatch(DocumentRepository.DocumentRow row, String q,
                                            PermissionDecision decision) {
        return q == null || q.isBlank()
                || matchedFields(row, q).stream().anyMatch(field -> fieldAllowed(decision, field));
    }

    private static List<String> highlights(DocumentRepository.DocumentRow row, String q,
                                           PermissionDecision decision) {
        if (q == null || q.isBlank()) {
            return List.of();
        }
        String term = q.toLowerCase(Locale.ROOT);
        if (fieldAllowed(decision, "name")
                && row.name() != null && row.name().toLowerCase(Locale.ROOT).contains(term)) {
            return List.of(row.name());
        }
        if (fieldAllowed(decision, "category")
                && row.category() != null && row.category().toLowerCase(Locale.ROOT).contains(term)) {
            return List.of(row.category());
        }
        return List.of();
    }

    private static double score(DocumentRepository.DocumentRow row, String q,
                                List<String> matchedFields) {
        if (q == null || q.isBlank()) {
            return 10;
        }
        if (row.id().equalsIgnoreCase(q)) {
            return 100;
        }
        String term = q.toLowerCase(Locale.ROOT);
        if (row.name() != null && row.name().toLowerCase(Locale.ROOT).startsWith(term)) {
            return 70;
        }
        if (matchedFields.contains("name")) {
            return 60;
        }
        if (matchedFields.contains("category")) {
            return 40;
        }
        return 20;
    }

    private static String stringFilter(SearchQuery query, String name) {
        Object value = query.filters().get(name);
        return value == null || value.toString().isBlank() ? null : value.toString().trim();
    }

    private static boolean exactIdentifier(String q) {
        return q != null && (q.contains(":") || q.length() >= 8);
    }

    private static SearchWarning authUnavailable(String message) {
        return new SearchWarning("authorization-unavailable", message, PROVIDER_ID);
    }

    private static SearchProviderStatus degradedStatus(SearchWarning warning) {
        return new SearchProviderStatus(PROVIDER_ID, "degraded", List.of(SearchScope.DOCUMENTS),
                false, true, 30, 0, false, List.of(warning));
    }

    private static void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private static boolean fieldAllowed(PermissionDecision decision, String field) {
        if ("documentId".equals(field) || "caseId".equals(field)) {
            return true;
        }
        return decision.allowsField(field);
    }

    private static void putIfAllowed(Map<String, Object> target, PermissionDecision decision,
                                     String key, Object value) {
        if (value != null && fieldAllowed(decision, key)) {
            target.put(key, value);
        }
    }
}
