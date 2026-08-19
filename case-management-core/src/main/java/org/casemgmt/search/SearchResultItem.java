package org.casemgmt.search;

import java.util.List;
import java.util.Map;
import java.time.OffsetDateTime;

public record SearchResultItem(String id, SearchResultType resultType, String caseId, String title,
                               String summary, String sourceProvider, double score,
                               List<String> matchedFields, List<String> highlights,
                               Map<String, Object> resource, OffsetDateTime updatedAt,
                               String freshness) {

    public SearchResultItem {
        matchedFields = matchedFields == null ? List.of() : List.copyOf(matchedFields);
        highlights = highlights == null ? List.of() : List.copyOf(highlights);
        resource = resource == null ? Map.of() : Map.copyOf(resource);
        freshness = freshness == null ? "fresh" : freshness;
    }
}
