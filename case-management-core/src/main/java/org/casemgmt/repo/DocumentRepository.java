package org.casemgmt.repo;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public class DocumentRepository {

    public record DocumentRow(String id, String caseId, String name, String category,
                              String mimeType, Long sizeBytes, String contentUrl,
                              String uploadedBy, OffsetDateTime uploadedAt) {}

    private final JdbcClient jdbc;

    public DocumentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(String id, String caseId, String name, String category, String mimeType,
                       Long sizeBytes, String contentUrl, String uploadedBy) {
        jdbc.sql("""
                INSERT INTO CM_DOCUMENT (ID_, CASE_ID_, NAME_, CATEGORY_, MIME_TYPE_,
                    SIZE_BYTES_, CONTENT_URL_, UPLOADED_BY_)
                VALUES (:id, :caseId, :name, :category, :mimeType,
                    :sizeBytes, :contentUrl, :uploadedBy)""")
            .param("id", id)
            .param("caseId", caseId)
            .param("name", name)
            .param("category", category)
            .param("mimeType", mimeType)
            .param("sizeBytes", sizeBytes)
            .param("contentUrl", contentUrl)
            .param("uploadedBy", uploadedBy)
            .update();
    }

    public Optional<DocumentRow> findById(String caseId, String documentId) {
        return jdbc.sql("""
                SELECT ID_, CASE_ID_, NAME_, CATEGORY_, MIME_TYPE_, SIZE_BYTES_,
                       CONTENT_URL_, UPLOADED_BY_, UPLOADED_AT_
                FROM CM_DOCUMENT
                WHERE CASE_ID_ = :caseId AND ID_ = :documentId""")
            .param("caseId", caseId)
            .param("documentId", documentId)
            .query(DocumentRepository::map)
            .optional();
    }

    public List<DocumentRow> findByCase(String caseId) {
        return jdbc.sql("""
                SELECT ID_, CASE_ID_, NAME_, CATEGORY_, MIME_TYPE_, SIZE_BYTES_,
                       CONTENT_URL_, UPLOADED_BY_, UPLOADED_AT_
                FROM CM_DOCUMENT
                WHERE CASE_ID_ = :caseId
                ORDER BY UPLOADED_AT_ DESC, ID_ ASC""")
            .param("caseId", caseId)
            .query(DocumentRepository::map)
            .list();
    }

    public boolean delete(String caseId, String documentId) {
        return jdbc.sql("DELETE FROM CM_DOCUMENT WHERE CASE_ID_ = :caseId AND ID_ = :documentId")
            .param("caseId", caseId)
            .param("documentId", documentId)
            .update() > 0;
    }

    public List<DocumentRow> search(DocumentSearchQuery query) {
        StringBuilder sql = new StringBuilder("""
                SELECT d.ID_, d.CASE_ID_, d.NAME_, d.CATEGORY_, d.MIME_TYPE_, d.SIZE_BYTES_,
                       d.CONTENT_URL_, d.UPLOADED_BY_, d.UPLOADED_AT_
                FROM CM_DOCUMENT d
                JOIN CM_CASE c ON c.ID_ = d.CASE_ID_
                WHERE c.TENANT_ID_ = :tenantId""");
        List<Object[]> params = new ArrayList<>();
        params.add(new Object[]{"tenantId", query.tenantId()});
        if (query.caseId() != null) {
            sql.append(" AND d.CASE_ID_ = :caseId");
            params.add(new Object[]{"caseId", query.caseId()});
        }
        if (query.category() != null) {
            sql.append(" AND d.CATEGORY_ = :category");
            params.add(new Object[]{"category", query.category()});
        }
        if (query.mimeType() != null) {
            sql.append(" AND d.MIME_TYPE_ = :mimeType");
            params.add(new Object[]{"mimeType", query.mimeType()});
        }
        if (query.text() != null) {
            sql.append("""
                     AND (
                        LOWER(d.ID_) = :searchExact
                        OR LOWER(d.NAME_) LIKE :searchLike ESCAPE '~'
                        OR LOWER(d.CATEGORY_) LIKE :searchLike ESCAPE '~'
                        OR LOWER(d.MIME_TYPE_) LIKE :searchLike ESCAPE '~'
                    )""");
            String exact = query.text().toLowerCase(Locale.ROOT);
            params.add(new Object[]{"searchExact", exact});
            params.add(new Object[]{"searchLike", containsLike(exact)});
        }

        if (query.text() == null) {
            sql.append(" ORDER BY d.UPLOADED_AT_ DESC, d.ID_ ASC");
        } else {
            sql.append("""
                     ORDER BY
                      CASE
                        WHEN LOWER(d.ID_) = :rankingExact THEN 0
                        WHEN LOWER(d.NAME_) LIKE :rankingPrefix ESCAPE '~' THEN 1
                        WHEN LOWER(d.CATEGORY_) LIKE :rankingPrefix ESCAPE '~' THEN 2
                        ELSE 3
                      END,
                      d.UPLOADED_AT_ DESC, d.ID_ ASC""");
        }
        sql.append(" OFFSET :offset ROWS FETCH NEXT :limit ROWS ONLY");

        var spec = jdbc.sql(sql.toString());
        if (query.text() != null) {
            String exact = query.text().toLowerCase(Locale.ROOT);
            spec = spec.param("rankingExact", exact)
                    .param("rankingPrefix", startsWithLike(exact));
        }
        for (Object[] param : params) {
            spec = spec.param((String) param[0], param[1]);
        }
        return spec.param("offset", query.offset())
                .param("limit", query.limit() <= 0 ? 25 : query.limit())
                .query(DocumentRepository::map)
                .list();
    }

    private static String containsLike(String value) {
        return "%" + value
                .replace("~", "~~")
                .replace("%", "~%")
                .replace("_", "~_") + "%";
    }

    private static String startsWithLike(String value) {
        return value
                .replace("~", "~~")
                .replace("%", "~%")
                .replace("_", "~_") + "%";
    }

    private static DocumentRow map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        long rawSize = rs.getLong("SIZE_BYTES_");
        Long size = rs.wasNull() ? null : rawSize;
        return new DocumentRow(rs.getString("ID_"), rs.getString("CASE_ID_"),
                rs.getString("NAME_"), rs.getString("CATEGORY_"), rs.getString("MIME_TYPE_"),
                size, rs.getString("CONTENT_URL_"),
                rs.getString("UPLOADED_BY_"),
                rs.getObject("UPLOADED_AT_", OffsetDateTime.class));
    }
}
