package org.casemgmt.search;

import org.casemgmt.OracleTestBase;
import org.casemgmt.domain.CaseInstance;
import org.casemgmt.domain.CasePriority;
import org.casemgmt.domain.CaseState;
import org.casemgmt.permissions.PermissionDecision;
import org.casemgmt.permissions.WorkerPermissionRequest;
import org.casemgmt.permissions.WorkerPermissionsClient;
import org.casemgmt.repo.CaseRepository;
import org.casemgmt.repo.DocumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentMetadataSearchProviderTest extends OracleTestBase {

    private CaseRepository cases;
    private DocumentRepository documents;

    @BeforeEach
    void setUp() {
        cases = new CaseRepository(jdbc());
        documents = new DocumentRepository(jdbc());
        jdbc().sql("""
                INSERT INTO CM_CASE_DEF (ID_, KEY_, VERSION_NO_, NAME_, TENANT_ID_)
                VALUES ('widget-review:1', 'widget-review', 1, 'Widget review', 't1')""").update();
        cases.insert(newCase("eng-a:1", "t1", "Allowed case"));
        cases.insert(newCase("eng-a:2", "t1", "Denied case"));
        cases.insert(newCase("eng-b:1", "t2", "Other tenant case"));
    }

    @Test
    void filtersDocumentResultsThroughWorkerPermissionsBeforeReturningAnything() {
        documents.insert("doc-allowed", "eng-a:1", "Passport evidence", "evidence",
                "application/pdf", 1200L, "https://dms.example/doc-allowed", "alice");
        documents.insert("doc-denied", "eng-a:2", "Passport escalation memo", "evidence",
                "application/pdf", 900L, "https://dms.example/doc-denied", "alice");

        DocumentMetadataSearchProvider provider = new DocumentMetadataSearchProvider(documents,
                allowOnly(Set.of("doc-allowed")));
        SearchProviderResult response = provider.search(query("passport"));

        assertThat(response.items()).extracting(SearchResultItem::id)
                .containsExactly("doc-allowed");
        SearchResultItem item = response.items().get(0);
        assertThat(item.resultType()).isEqualTo(SearchResultType.DOCUMENT);
        assertThat(item.caseId()).isEqualTo("eng-a:1");
        assertThat(item.matchedFields()).contains("name");
        assertThat(item.resource()).containsEntry("contentUrl", "https://dms.example/doc-allowed");
    }

    @Test
    void searchIsTenantScopedBeforeWorkerPermissionEvaluation() {
        documents.insert("doc-local", "eng-a:1", "Passport evidence", "evidence",
                "application/pdf", 1200L, "https://dms.example/doc-local", "alice");
        documents.insert("doc-foreign", "eng-b:1", "Passport evidence", "evidence",
                "application/pdf", 1200L, "https://dms.example/doc-foreign", "dave");

        WorkerPermissionsClient recording = request -> {
            assertThat(request.resources()).extracting(resource -> resource.id())
                    .containsExactly("doc-local");
            return request.resources().stream().collect(Collectors.toMap(
                    resource -> resource.id(), resource -> PermissionDecision.allow(resource.id())));
        };
        DocumentMetadataSearchProvider provider = new DocumentMetadataSearchProvider(documents,
                recording);

        SearchProviderResult response = provider.search(query("passport"));

        assertThat(response.items()).extracting(SearchResultItem::id)
                .containsExactly("doc-local");
    }

    @Test
    void treatsOracleLikeWildcardsAsLiteralDocumentText() {
        documents.insert("doc-literal", "eng-a:1", "Evidence 100%_ complete", "evidence",
                "application/pdf", 1200L, "https://dms.example/doc-literal", "alice");
        documents.insert("doc-expanded", "eng-a:1", "Evidence 100XA complete", "evidence",
                "application/pdf", 1200L, "https://dms.example/doc-expanded", "alice");

        DocumentMetadataSearchProvider provider = new DocumentMetadataSearchProvider(documents,
                allowOnly(Set.of("doc-literal", "doc-expanded")));

        SearchProviderResult response = provider.search(query("100%_"));

        assertThat(response.items()).extracting(SearchResultItem::id)
                .containsExactly("doc-literal");
    }

    @Test
    void suppressesDocumentMatchesWhenOnlyUndisclosableFieldsMatched() {
        documents.insert("doc-masked", "eng-a:1", "Sensitive filename.pdf", "evidence",
                "application/pdf", 1200L, "https://dms.example/doc-masked", "alice");
        DocumentMetadataSearchProvider provider = new DocumentMetadataSearchProvider(documents,
                request -> request.resources().stream().collect(Collectors.toMap(
                        resource -> resource.id(),
                        resource -> new PermissionDecision(resource.id(), true,
                                List.of("category")))));

        SearchProviderResult response = provider.search(query("Sensitive"));

        assertThat(response.items()).isEmpty();
    }

    @Test
    void failsClosedWhenWorkerPermissionsIsUnavailable() {
        documents.insert("doc-allowed", "eng-a:1", "Passport evidence", "evidence",
                "application/pdf", 1200L, "https://dms.example/doc-allowed", "alice");
        DocumentMetadataSearchProvider provider = new DocumentMetadataSearchProvider(documents,
                request -> {
                    throw new IllegalStateException("permissions down");
                });

        SearchProviderResult response = provider.search(query("passport"));

        assertThat(response.items()).isEmpty();
        assertThat(response.warnings()).extracting(SearchWarning::code)
                .containsExactly("authorization-unavailable");
        assertThat(response.providerStatus().status()).isEqualTo("degraded");
    }

    @Test
    void failsClosedWhenSearchQueryHasNoWorkerIdentity() {
        documents.insert("doc-allowed", "eng-a:1", "Passport evidence", "evidence",
                "application/pdf", 1200L, "https://dms.example/doc-allowed", "alice");
        DocumentMetadataSearchProvider provider = new DocumentMetadataSearchProvider(documents,
                WorkerPermissionsClient.allowAll());

        SearchProviderResult response = provider.search(new SearchQuery("t1", "passport",
                List.of(SearchScope.DOCUMENTS), Map.of(), List.of(), 0, 25, true));

        assertThat(response.items()).isEmpty();
        assertThat(response.warnings()).extracting(SearchWarning::code)
                .containsExactly("authorization-unavailable");
    }

    private static WorkerPermissionsClient allowOnly(Set<String> allowed) {
        return request -> request.resources().stream().collect(Collectors.toMap(
                resource -> resource.id(),
                resource -> allowed.contains(resource.id())
                        ? PermissionDecision.allow(resource.id())
                        : PermissionDecision.deny(resource.id())));
    }

    private static SearchQuery query(String text) {
        return new SearchQuery("t1", "alice", List.of("users", "tenant:t1"), text,
                List.of(SearchScope.DOCUMENTS), Map.of(), List.of(), 0, 25, true);
    }

    private static CaseInstance newCase(String id, String tenantId, String title) {
        OffsetDateTime now = OffsetDateTime.now();
        return new CaseInstance(id, "eng-a", tenantId, "widget-review:1", "widget-review", 1,
                "BK-" + id, title, CaseState.ACTIVE, CasePriority.HIGH, null, null,
                "alice", "NONE", null, null, Map.of(), 0L, now, now, null);
    }
}
