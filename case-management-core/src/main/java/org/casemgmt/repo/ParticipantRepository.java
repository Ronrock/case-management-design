package org.casemgmt.repo;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class ParticipantRepository {

    private final JdbcClient jdbc;

    public ParticipantRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public void insert(String id, String caseId, String userId, String groupId, String role) {
        if (userId == null && groupId == null) {
            throw new IllegalArgumentException("participant needs a userId or a groupId");
        }
        jdbc.sql("""
                INSERT INTO CM_PARTICIPANT (ID_, CASE_ID_, USER_ID_, GROUP_ID_, ROLE_)
                VALUES (:id, :caseId, :userId, :groupId, :role)""")
            .param("id", id).param("caseId", caseId).param("userId", userId)
            .param("groupId", groupId).param("role", role).update();
    }

    /** Roles the caller holds on this case, directly or through a group. */
    public Set<String> rolesOf(String caseId, String userId, List<String> groups) {
        Set<String> roles = new LinkedHashSet<>(jdbc.sql("""
                SELECT ROLE_ FROM CM_PARTICIPANT WHERE CASE_ID_ = :caseId AND USER_ID_ = :userId""")
            .param("caseId", caseId).param("userId", userId)
            .query(String.class).list());

        if (groups != null && !groups.isEmpty()) {
            roles.addAll(jdbc.sql("""
                    SELECT ROLE_ FROM CM_PARTICIPANT WHERE CASE_ID_ = :caseId AND GROUP_ID_ IN (:groups)""")
                .param("caseId", caseId).param("groups", groups)
                .query(String.class).list());
        }
        return roles;
    }

    public List<String> findByCase(String caseId) {
        return jdbc.sql("SELECT ID_ FROM CM_PARTICIPANT WHERE CASE_ID_ = :caseId")
                .param("caseId", caseId).query(String.class).list();
    }
}
