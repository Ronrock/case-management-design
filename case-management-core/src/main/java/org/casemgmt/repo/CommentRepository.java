package org.casemgmt.repo;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.OffsetDateTime;
import java.util.List;

public class CommentRepository {

    public record CommentRow(String id, String caseId, String author, String text,
                             String visibility, OffsetDateTime createdAt) {}

    private final JdbcClient jdbc;

    public CommentRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public void insert(String id, String caseId, String author, String text, String visibility) {
        if (!"internal".equals(visibility) && !"external".equals(visibility)) {
            throw new IllegalArgumentException("visibility must be 'internal' or 'external', got " + visibility);
        }
        jdbc.sql("""
                INSERT INTO CM_COMMENT (ID_, CASE_ID_, AUTHOR_, TEXT_, VISIBILITY_)
                VALUES (:id, :caseId, :author, :text, :visibility)""")
            .param("id", id).param("caseId", caseId).param("author", author)
            .param("text", text).param("visibility", visibility).update();
    }

    public List<CommentRow> findByCase(String caseId, String visibilityFilter) {
        return jdbc.sql("""
                SELECT ID_, CASE_ID_, AUTHOR_, TEXT_, VISIBILITY_, CREATED_AT_
                FROM CM_COMMENT
                WHERE CASE_ID_ = :caseId AND (:vis IS NULL OR VISIBILITY_ = :vis)
                ORDER BY CREATED_AT_""")
            .param("caseId", caseId).param("vis", visibilityFilter)
            .query((rs, n) -> new CommentRow(rs.getString("ID_"), rs.getString("CASE_ID_"),
                    rs.getString("AUTHOR_"), rs.getString("TEXT_"), rs.getString("VISIBILITY_"),
                    rs.getObject("CREATED_AT_", OffsetDateTime.class)))
            .list();
    }
}
