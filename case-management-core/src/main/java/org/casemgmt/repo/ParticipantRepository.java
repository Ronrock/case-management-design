package org.casemgmt.repo;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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

    /**
     * {@link #rolesOf(String, String, List)} for many cases at once, in two statements total
     * rather than two per case.
     *
     * <p>Added by Task 24 fix round 1 (review finding I8): the case listing endpoint computes
     * {@code availableActions[]} per row, and doing that through the single-case method made a
     * 50-row page issue 100 participant queries on its own. Returns an entry for every requested
     * case id — an empty set where the caller holds no role, so a caller can distinguish
     * "no roles" from "not asked about" without a null check.
     */
    public Map<String, Set<String>> rolesOf(Collection<String> caseIds, String userId, List<String> groups) {
        Map<String, Set<String>> byCase = new LinkedHashMap<>();
        for (String caseId : caseIds) {
            byCase.put(caseId, new LinkedHashSet<>());
        }
        if (byCase.isEmpty()) {
            return byCase;
        }
        List<String> ids = List.copyOf(byCase.keySet());

        jdbc.sql("""
                SELECT CASE_ID_, ROLE_ FROM CM_PARTICIPANT
                WHERE CASE_ID_ IN (:caseIds) AND USER_ID_ = :userId""")
            .param("caseIds", ids).param("userId", userId)
            .query((rs, n) -> Map.entry(rs.getString("CASE_ID_"), rs.getString("ROLE_")))
            .list().forEach(e -> byCase.get(e.getKey()).add(e.getValue()));

        if (groups != null && !groups.isEmpty()) {
            jdbc.sql("""
                    SELECT CASE_ID_, ROLE_ FROM CM_PARTICIPANT
                    WHERE CASE_ID_ IN (:caseIds) AND GROUP_ID_ IN (:groups)""")
                .param("caseIds", ids).param("groups", groups)
                .query((rs, n) -> Map.entry(rs.getString("CASE_ID_"), rs.getString("ROLE_")))
                .list().forEach(e -> byCase.get(e.getKey()).add(e.getValue()));
        }
        return byCase;
    }

    public List<String> findByCase(String caseId) {
        return jdbc.sql("SELECT ID_ FROM CM_PARTICIPANT WHERE CASE_ID_ = :caseId")
                .param("caseId", caseId).query(String.class).list();
    }
}
