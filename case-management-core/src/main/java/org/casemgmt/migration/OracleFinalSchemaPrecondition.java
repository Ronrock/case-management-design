package org.casemgmt.migration;

import liquibase.database.Database;
import liquibase.database.jvm.JdbcConnection;
import liquibase.exception.CustomPreconditionErrorException;
import liquibase.exception.CustomPreconditionFailedException;
import liquibase.precondition.CustomPrecondition;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Fail-closed Oracle metadata contract used only by final runAlways migration gates. */
public final class OracleFinalSchemaPrecondition implements CustomPrecondition {

    private String contract;

    public void setContract(String contract) {
        this.contract = contract;
    }

    @Override
    public void check(Database database)
            throws CustomPreconditionFailedException, CustomPreconditionErrorException {
        if (!(database.getConnection() instanceof JdbcConnection jdbc)) {
            throw new CustomPreconditionErrorException("Exact schema validation requires JDBC");
        }
        SchemaContract expected = switch (Objects.requireNonNull(contract, "contract")) {
            case "production-command" -> productionCommandContract();
            case "engine-observation" -> engineObservationContract();
            default -> throw new CustomPreconditionErrorException(
                    "Unknown exact schema contract: " + contract);
        };
        try {
            List<String> differences = expected.differences(jdbc.getUnderlyingConnection());
            if (!differences.isEmpty()) {
                throw new CustomPreconditionFailedException(String.join("; ", differences));
            }
        } catch (SQLException ex) {
            throw new CustomPreconditionErrorException(
                    "Unable to read Oracle schema metadata for " + contract, ex);
        }
    }

    static SchemaContract productionCommandContract() {
        var columns = new ArrayList<ColumnContract>();
        add(columns, "CM_ENGINE_COMMAND", false,
                v("ID_",64,false,null), v("CASE_ID_",140,false,null),
                v("TYPE_",30,false,null), clob("PAYLOAD_JSON_",true,null),
                v("STATUS_",32,false,"PENDING"), num("ATTEMPTS_",3,false,"0"),
                ts("NEXT_ATTEMPT_AT_",true,null), v("LAST_ERROR_",2000,true,null),
                ts("CREATED_AT_",false,"SYSTIMESTAMP"), v("CLAIM_TOKEN_",64,true,null),
                ts("CLAIMED_AT_",true,null), v("OPERATION_ID_",64,false,null),
                v("TENANT_ID_",64,false,null), v("IDEMPOTENCY_KEY_",128,false,null),
                v("PAYLOAD_DIGEST_",64,false,null), v("TARGET_IDENTITY_",255,true,null),
                clob("CORRELATION_JSON_",true,null), clob("CANONICAL_PATCH_JSON_",true,null),
                num("EXPECTED_CASE_VERSION_",19,true,null), v("LEASE_TOKEN_",64,true,null),
                v("LEASE_OWNER_",128,true,null), ts("LEASE_EXPIRES_AT_",true,null),
                ts("DISPATCHED_AT_",true,null), ts("UPDATED_AT_",false,null),
                ts("CONFIRMED_AT_",true,null), ts("FAILED_AT_",true,null),
                ts("DECIDED_AT_",false,null), v("SAFE_ERROR_CODE_",64,true,null),
                v("SAFE_SUMMARY_",256,true,null), v("CONFIRM_SOURCE_",32,true,null),
                v("REMOTE_IDENTITY_",255,true,null), v("REMOTE_STATE_",64,true,null),
                v("EVIDENCE_REFERENCE_",160,true,null), num("TOTAL_DISPATCH_ATTEMPTS_",19,false,null),
                num("AUTO_ATTEMPTS_",3,false,null), num("BUDGET_EPOCH_",19,false,null),
                num("AUTO_BUDGET_RESET_",1,false,null), num("ROW_VERSION_",19,false,null),
                num("ACTION_COUNT_",19,false,null), num("ACTION_HIGH_WATER_",19,false,null),
                num("ACTION_RESET_COUNT_",19,false,null), num("ACTION_CANCEL_COUNT_",19,false,null),
                num("CURRENT_ACTION_SEQ_",19,true,null), v("DECISION_REVIEW_FINDING_",32,true,null),
                v("DECISION_REVIEW_SOURCE_",32,true,null), v("DECISION_REVIEW_REF_",160,true,null),
                v("LEGACY_ROW_ID_",64,true,null), v("LEGACY_STATUS_",20,true,null),
                v("LEGACY_MIGRATION_REF_",160,true,null), ts("LEGACY_MIGRATED_AT_",true,null),
                num("LEGACY_FAILURE_COUNT_",3,true,null), clob("RAW_LEGACY_PAYLOAD_",true,null),
                v("RAW_LEGACY_ERROR_",2000,true,null), v("RAW_LEGACY_CLAIM_TOKEN_",64,true,null),
                ts("RAW_LEGACY_CLAIMED_AT_",true,null), num("RAW_LEGACY_ATTEMPTS_",3,true,null),
                ts("RAW_LEGACY_CREATED_AT_",true,null), ts("RAW_LEGACY_UPDATED_AT_",true,null),
                ts("MIGRATION_BASELINE_DECIDED_AT_",true,null),
                num("MIGRATION_BASELINE_ACTIVE_",1,true,null), v("ORIGINAL_STATUS_",20,true,null));
        add(columns, "CM_ENGINE_COMMAND_ACTION", false,
                v("COMMAND_ID_",64,false,null), num("SEQUENCE_",19,false,null),
                v("ACTION_ID_",160,false,null), v("TENANT_ID_",64,false,null),
                v("OPERATION_ID_",64,false,null), v("COMMAND_TYPE_",30,false,null),
                v("EXPECTED_TARGET_",255,false,null), v("ACTION_TYPE_",32,false,null),
                v("AUDIT_REFERENCE_",160,false,null), ts("PERFORMED_AT_",false,null),
                num("OVERRIDE_AUTO_CAP_",1,false,null), v("REVIEW_FINDING_",32,true,null),
                v("REVIEW_SOURCE_",32,true,null), v("REVIEW_REFERENCE_",160,true,null),
                ts("CREATED_AT_",false,"SYSTIMESTAMP"));
        add(columns, "CM_ENGINE_COMMAND_TRANSITION", false,
                v("COMMAND_ID_",64,false,null), num("VERSION_",19,false,null),
                v("TENANT_ID_",64,false,null), v("OPERATION_ID_",64,false,null),
                v("COMMAND_TYPE_",30,false,null), v("EXPECTED_TARGET_",255,false,null),
                v("FROM_STATUS_",32,false,null), v("TO_STATUS_",32,false,null),
                num("OUTCOME_FORMAT_",3,false,null), v("OUTCOME_KIND_",40,false,null),
                clob("OUTCOME_JSON_",false,null), num("ACTION_SEQUENCE_",19,true,null),
                ts("DECIDED_AT_",false,null), v("PREVIOUS_DECISION_DIGEST_",64,true,null),
                v("NEXT_DECISION_DIGEST_",64,false,null), ts("CREATED_AT_",false,"SYSTIMESTAMP"));
        return new SchemaContract(columns, productionConstraints(), productionIndexes());
    }

    static SchemaContract engineObservationContract() {
        var columns = new ArrayList<ColumnContract>();
        add(columns, "CM_APPLIED_ENGINE_OBSERVATION", false,
                v("OBSERVATION_ID_",128,false,null), v("TENANT_ID_",64,true,null),
                v("FINGERPRINT_",64,false,null), v("CLAIM_TOKEN_",43,false,null),
                v("STATUS_",16,false,null), v("SOURCE_",128,false,null),
                v("CASE_ID_",128,false,null), v("PROCESS_INSTANCE_ID_",128,false,null),
                v("ENTITY_ID_",128,false,null), num("ENTITY_REVISION_",19,true,null),
                v("EVENT_TYPE_",64,false,null), ts("ENGINE_OCCURRED_AT_",false,null),
                ts("CLAIMED_AT_",false,null), ts("APPLIED_AT_",true,null),
                ts("FAILED_AT_",true,null), v("FAILURE_DETAIL_",2000,true,null),
                v("OBSERVATION_KIND_",32,false,"LEGACY"), ts("IGNORED_AT_",true,null),
                v("ENGINE_ID_",128,true,null));
        add(columns, "CM_PLAN_ITEM", true, v("PROC_INST_ID_",128,true,null));
        add(columns, "CM_TASK", true, v("PROC_INST_ID_",128,true,null));
        add(columns, "CM_LINKED_PROCESS", true, v("PROC_DEF_ID_",128,true,null));
        return new SchemaContract(columns, observationConstraints(), observationIndexes());
    }

    private static List<ConstraintContract> productionConstraints() {
        return List.of(
                new ConstraintContract("CK_CM_ENGCMD_STATUS","CM_ENGINE_COMMAND","C",null,null,
                        "STATUS_IN('PENDING','DISPATCHING','RETRYABLE','AWAITING_CONFIRMATION','CONFIRMED','FAILED','CONFLICT','MANUAL_REVIEW','CANCELLED')"),
                new ConstraintContract("CK_CM_ENGCMD_COUNTERS","CM_ENGINE_COMMAND","C",null,null,
                        "TOTAL_DISPATCH_ATTEMPTS_>=0ANDAUTO_ATTEMPTS_>=0ANDAUTO_ATTEMPTS_<=6ANDBUDGET_EPOCH_>=0ANDAUTO_BUDGET_RESET_IN(0,1)ANDROW_VERSION_>=0ANDACTION_COUNT_>=0ANDACTION_HIGH_WATER_=ACTION_COUNT_AND(CURRENT_ACTION_SEQ_ISNULLORCURRENT_ACTION_SEQ_=ACTION_HIGH_WATER_)ANDACTION_RESET_COUNT_>=0ANDACTION_CANCEL_COUNT_>=0ANDACTION_RESET_COUNT_+ACTION_CANCEL_COUNT_<=ACTION_COUNT_ANDBUDGET_EPOCH_=ACTION_RESET_COUNT_ANDTOTAL_DISPATCH_ATTEMPTS_>=BUDGET_EPOCH_*6+AUTO_ATTEMPTS_AND(MIGRATION_BASELINE_ACTIVE_ISNULLORMIGRATION_BASELINE_ACTIVE_IN(0,1))"),
                new ConstraintContract("CK_CM_ENGCMD_LEASE","CM_ENGINE_COMMAND","C",null,null,
                        "(STATUS_='DISPATCHING'ANDLEASE_TOKEN_ISNOTNULLANDLEASE_OWNER_ISNOTNULLANDLEASE_EXPIRES_AT_ISNOTNULL)OR(STATUS_<>'DISPATCHING'ANDLEASE_TOKEN_ISNULLANDLEASE_OWNER_ISNULLANDLEASE_EXPIRES_AT_ISNULL)"),
                new ConstraintContract("CK_CM_ENGCMD_TEMPORAL","CM_ENGINE_COMMAND","C",null,null,
                        "((STATUS_='RETRYABLE'ANDNEXT_ATTEMPT_AT_ISNOTNULLANDNEXT_ATTEMPT_AT_>=DECIDED_AT_)OR(STATUS_<>'RETRYABLE'ANDNEXT_ATTEMPT_AT_ISNULL))AND((STATUS_='DISPATCHING'ANDLEASE_EXPIRES_AT_>DECIDED_AT_)ORSTATUS_<>'DISPATCHING')AND((STATUS_='CONFIRMED'ANDCONFIRMED_AT_=DECIDED_AT_)OR(STATUS_<>'CONFIRMED'ANDCONFIRMED_AT_ISNULL))AND((STATUS_='FAILED'ANDFAILED_AT_=DECIDED_AT_)OR(STATUS_<>'FAILED'ANDFAILED_AT_ISNULL))AND(DISPATCHED_AT_ISNULLOR(DISPATCHED_AT_>=CREATED_AT_ANDDISPATCHED_AT_<=DECIDED_AT_))AND(TOTAL_DISPATCH_ATTEMPTS_>0ORDISPATCHED_AT_ISNULL)"),
                new ConstraintContract("CK_CM_ECA_INVARIANTS","CM_ENGINE_COMMAND_ACTION","C",null,null,
                        "SEQUENCE_>0ANDOVERRIDE_AUTO_CAP_IN(0,1)AND((REVIEW_FINDING_ISNULLANDREVIEW_SOURCE_ISNULLANDREVIEW_REFERENCE_ISNULL)OR(REVIEW_FINDING_ISNOTNULLANDREVIEW_SOURCE_ISNOTNULLANDREVIEW_REFERENCE_ISNOTNULL))"),
                new ConstraintContract("FK_CM_ECA_COMMAND","CM_ENGINE_COMMAND_ACTION","R",
                        List.of("COMMAND_ID_"),"CM_ENGINE_COMMAND:ID_",null),
                new ConstraintContract("UQ_CM_ECA_SEQUENCE_C","CM_ENGINE_COMMAND_ACTION","U",
                        List.of("COMMAND_ID_","SEQUENCE_"),null,null),
                new ConstraintContract("PK_CM_ECT","CM_ENGINE_COMMAND_TRANSITION","P",
                        List.of("COMMAND_ID_","VERSION_"),null,null),
                new ConstraintContract("CK_CM_ECT_INVARIANTS","CM_ENGINE_COMMAND_TRANSITION","C",
                        null,null,
                        "OUTCOME_FORMAT_IN(1,2)AND((VERSION_=0ANDPREVIOUS_DECISION_DIGEST_ISNULLANDFROM_STATUS_=TO_STATUS_ANDACTION_SEQUENCE_ISNULL)OR(VERSION_>0ANDPREVIOUS_DECISION_DIGEST_ISNOTNULL))AND(ACTION_SEQUENCE_ISNULLORACTION_SEQUENCE_>0)"),
                new ConstraintContract("FK_CM_ECT_COMMAND","CM_ENGINE_COMMAND_TRANSITION","R",
                        List.of("COMMAND_ID_"),"CM_ENGINE_COMMAND:ID_",null),
                new ConstraintContract("FK_CM_ECT_ACTION","CM_ENGINE_COMMAND_TRANSITION","R",
                        List.of("COMMAND_ID_","ACTION_SEQUENCE_"),
                        "CM_ENGINE_COMMAND_ACTION:COMMAND_ID_,SEQUENCE_",null));
    }

    private static List<IndexContract> productionIndexes() {
        return List.of(
                ix("IX_CM_ENGCMD_DUE","CM_ENGINE_COMMAND",false,"STATUS_",
                        "SYS_EXTRACT_UTC(NEXT_ATTEMPT_AT_)"),
                ix("IX_CM_ENGCMD_CLAIM","CM_ENGINE_COMMAND",false,"CLAIM_TOKEN_"),
                ix("UQ_CM_ENGCMD_OPERATION","CM_ENGINE_COMMAND",true,
                        "CASEWHENTENANT_ID_ISNULLTHEN1ELSE0END","TENANT_ID_","OPERATION_ID_"),
                ix("UQ_CM_ENGCMD_IDEMPOTENCY","CM_ENGINE_COMMAND",true,
                        "CASEWHENTENANT_ID_ISNULLTHEN1ELSE0END","TENANT_ID_","IDEMPOTENCY_KEY_"),
                ix("IX_CM_ENGCMD_PROD_DUE","CM_ENGINE_COMMAND",false,
                        "STATUS_","SYS_EXTRACT_UTC(NEXT_ATTEMPT_AT_)",
                        "SYS_EXTRACT_UTC(CREATED_AT_)"),
                ix("IX_CM_ENGCMD_LEASE","CM_ENGINE_COMMAND",false,"STATUS_",
                        "SYS_EXTRACT_UTC(LEASE_EXPIRES_AT_)"),
                ix("IX_CM_ENGCMD_CASE_STATUS","CM_ENGINE_COMMAND",false,"TENANT_ID_","CASE_ID_","STATUS_"),
                ix("IX_CM_ENGCMD_REVIEW","CM_ENGINE_COMMAND",false,"STATUS_",
                        "SYS_EXTRACT_UTC(UPDATED_AT_)"),
                ix("UQ_CM_ECA_ACTION","CM_ENGINE_COMMAND_ACTION",true,"COMMAND_ID_","ACTION_ID_"),
                ix("UQ_CM_ECA_SEQUENCE","CM_ENGINE_COMMAND_ACTION",true,"COMMAND_ID_","SEQUENCE_"),
                ix("PK_CM_ECT","CM_ENGINE_COMMAND_TRANSITION",true,"COMMAND_ID_","VERSION_"));
    }

    private static List<ConstraintContract> observationConstraints() {
        return List.of(
                new ConstraintContract("CK_CM_AEO_STATUS","CM_APPLIED_ENGINE_OBSERVATION","C",null,null,
                        "STATUS_IN('CLAIMED','APPLIED','IGNORED_STALE','FAILED')"),
                new ConstraintContract("CK_CM_AEO_STATUS_TS","CM_APPLIED_ENGINE_OBSERVATION","C",null,null,
                        "(STATUS_!='APPLIED'ORAPPLIED_AT_ISNOTNULL)AND(STATUS_!='IGNORED_STALE'ORIGNORED_AT_ISNOTNULL)AND(STATUS_!='FAILED'ORFAILED_AT_ISNOTNULL)"));
    }

    private static List<IndexContract> observationIndexes() {
        return List.of(
                ix("UQ_CM_AEO_AUTH_FINGERPRINT","CM_APPLIED_ENGINE_OBSERVATION",true,
                        "CASEWHENTENANT_ID_ISNULLTHEN1ELSE0END","TENANT_ID_","FINGERPRINT_"),
                ix("IX_CM_AEO_STATUS","CM_APPLIED_ENGINE_OBSERVATION",false,
                        "STATUS_","SYS_EXTRACT_UTC(CLAIMED_AT_)"),
                ix("IX_CM_AEO_ENGINE_ENTITY","CM_APPLIED_ENGINE_OBSERVATION",false,
                        "TENANT_ID_","ENGINE_ID_","CASE_ID_","PROCESS_INSTANCE_ID_",
                        "OBSERVATION_KIND_","ENTITY_ID_","STATUS_"),
                ix("IX_CM_PI_PROC_INST","CM_PLAN_ITEM",false,"CASE_ID_","PROC_INST_ID_"),
                ix("IX_CM_TASK_PROC_INST","CM_TASK",false,"CASE_ID_","PROC_INST_ID_"));
    }

    private static void add(List<ColumnContract> target, String table, boolean partial,
                            ColumnContract... columns) {
        for (ColumnContract column : columns) target.add(column.in(table, partial));
    }

    private static ColumnContract v(String name, int length, boolean nullable, String defaultValue) {
        return new ColumnContract(null,name,"VARCHAR2",length,null,nullable,defaultValue,false);
    }
    private static ColumnContract num(String name, int precision, boolean nullable, String defaultValue) {
        return new ColumnContract(null,name,"NUMBER",precision,0,nullable,defaultValue,false);
    }
    private static ColumnContract ts(String name, boolean nullable, String defaultValue) {
        return new ColumnContract(null,name,"TIMESTAMP(6) WITH TIME ZONE",null,6,
                nullable,defaultValue,false);
    }
    private static ColumnContract clob(String name, boolean nullable, String defaultValue) {
        return new ColumnContract(null,name,"CLOB",null,null,nullable,defaultValue,false);
    }
    private static IndexContract ix(String name, String table, boolean unique, String... entries) {
        return new IndexContract(name, table, unique, List.of(entries));
    }

    static String normalize(String value) {
        if (value == null) return null;
        return value.toUpperCase(Locale.ROOT).replaceAll("[\\s\\\"']", "");
    }

    record ColumnContract(String table, String name, String type, Integer size, Integer scale,
                          boolean nullable, String defaultValue, boolean partialTable) {
        ColumnContract in(String table, boolean partial) {
            return new ColumnContract(table,name,type,size,scale,nullable,
                    normalize(defaultValue),partial);
        }
    }
    record ConstraintContract(String name, String table, String type, List<String> columns,
                              String reference, String expression) {}
    record IndexContract(String name, String table, boolean unique, List<String> entries) {}

    record SchemaContract(List<ColumnContract> columns, List<ConstraintContract> constraints,
                          List<IndexContract> indexes) {
        SchemaContract {
            columns = List.copyOf(columns);
            constraints = List.copyOf(constraints);
            indexes = List.copyOf(indexes);
        }

        List<String> differences(Connection connection) throws SQLException {
            var differences = new ArrayList<String>();
            verifyColumns(connection, differences);
            verifyConstraints(connection, differences);
            verifyIndexes(connection, differences);
            return differences;
        }

        private void verifyColumns(Connection connection, List<String> differences)
                throws SQLException {
            Map<String, ActualColumn> actual = new LinkedHashMap<>();
            try (var statement = connection.prepareStatement("""
                    SELECT TABLE_NAME,COLUMN_NAME,DATA_TYPE,CHAR_LENGTH,CHAR_USED,
                           DATA_PRECISION,DATA_SCALE,NULLABLE,DATA_DEFAULT
                    FROM USER_TAB_COLUMNS
                    """); ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    actual.put(rows.getString(1)+":"+rows.getString(2), new ActualColumn(
                            rows.getString(3), integer(rows,4), rows.getString(5),
                            integer(rows,6), integer(rows,7), "Y".equals(rows.getString(8)),
                            normalize(rows.getString(9))));
                }
            }
            Map<String,Long> exactCounts = columns.stream().filter(c -> !c.partialTable())
                    .collect(java.util.stream.Collectors.groupingBy(ColumnContract::table,
                            LinkedHashMap::new, java.util.stream.Collectors.counting()));
            for (var entry : exactCounts.entrySet()) {
                long count = actual.keySet().stream().filter(k -> k.startsWith(entry.getKey()+":"))
                        .count();
                if (count != entry.getValue()) differences.add(entry.getKey()+" column count");
            }
            for (ColumnContract expected : columns) {
                ActualColumn found = actual.get(expected.table()+":"+expected.name());
                if (found == null || !found.matches(expected)) {
                    differences.add(expected.table()+"."+expected.name()+" signature");
                }
            }
        }

        private void verifyConstraints(Connection connection, List<String> differences)
                throws SQLException {
            for (ConstraintContract expected : constraints) {
                try (var statement = connection.prepareStatement("""
                        SELECT TABLE_NAME,CONSTRAINT_TYPE,STATUS,VALIDATED,SEARCH_CONDITION_VC,
                               R_CONSTRAINT_NAME,DELETE_RULE
                        FROM USER_CONSTRAINTS WHERE CONSTRAINT_NAME=?
                        """)) {
                    statement.setString(1, expected.name());
                    try (ResultSet row = statement.executeQuery()) {
                        if (!row.next() || !expected.table().equals(row.getString(1))
                                || !expected.type().equals(row.getString(2))
                                || !"ENABLED".equals(row.getString(3))
                                || !"VALIDATED".equals(row.getString(4))
                                || expected.expression()!=null
                                   && !normalize(expected.expression()).equals(normalize(row.getString(5)))) {
                            differences.add(expected.name()+" constraint");
                            continue;
                        }
                        if (expected.columns()!=null && !expected.columns().equals(
                                constraintColumns(connection, expected.name()))) {
                            differences.add(expected.name()+" columns");
                        }
                        if (expected.reference()!=null) {
                            String referenced = referencedConstraint(connection, row.getString(6));
                            if (!expected.reference().equals(referenced)
                                    || !"NO ACTION".equals(row.getString(7))) {
                                differences.add(expected.name()+" reference");
                            }
                        }
                    }
                }
            }
            if (columns.stream().anyMatch(column -> "CM_ENGINE_COMMAND".equals(column.table()))
                    && !hasCommandPrimaryKey(connection)) {
                differences.add("CM_ENGINE_COMMAND primary key");
            }
        }

        private void verifyIndexes(Connection connection, List<String> differences)
                throws SQLException {
            for (IndexContract expected : indexes) {
                if (!indexMatches(connection, expected)) {
                    differences.add(expected.name()+" index");
                }
            }
        }

        static boolean indexExists(Connection connection, String name) throws SQLException {
            try (var statement = connection.prepareStatement(
                    "SELECT COUNT(*) FROM USER_INDEXES WHERE INDEX_NAME=?")) {
                statement.setString(1, name);
                try (ResultSet row = statement.executeQuery()) {
                    row.next();
                    return row.getInt(1) == 1;
                }
            }
        }

        static boolean indexMatches(Connection connection, IndexContract expected)
                throws SQLException {
            try (var statement = connection.prepareStatement("""
                    SELECT TABLE_OWNER,TABLE_NAME,UNIQUENESS,STATUS,VISIBILITY
                    FROM USER_INDEXES WHERE INDEX_NAME=?
                    """)) {
                statement.setString(1, expected.name());
                try (ResultSet row = statement.executeQuery()) {
                    return row.next()
                            && currentSchema(connection).equalsIgnoreCase(row.getString(1))
                            && expected.table().equals(row.getString(2))
                            && (expected.unique()?"UNIQUE":"NONUNIQUE").equals(row.getString(3))
                            && "VALID".equals(row.getString(4))
                            && "VISIBLE".equals(row.getString(5))
                            && expected.entries().equals(indexEntries(connection, expected.name()))
                            && !row.next();
                }
            }
        }

        private static boolean hasCommandPrimaryKey(Connection connection) throws SQLException {
            try (var statement = connection.prepareStatement("""
                    SELECT CONSTRAINT_NAME,STATUS,VALIDATED FROM USER_CONSTRAINTS
                    WHERE TABLE_NAME='CM_ENGINE_COMMAND' AND CONSTRAINT_TYPE='P'
                    """); ResultSet row = statement.executeQuery()) {
                if (!row.next()) return false;
                String constraintName = row.getString(1);
                boolean exactConstraint = "ENABLED".equals(row.getString(2))
                        && "VALIDATED".equals(row.getString(3))
                        && List.of("ID_").equals(constraintColumns(connection,constraintName));
                if (!exactConstraint || row.next()) return false;
                try (var index = connection.prepareStatement("""
                        SELECT I.INDEX_NAME,I.TABLE_OWNER,I.TABLE_NAME,I.UNIQUENESS,
                               I.STATUS,I.VISIBILITY
                        FROM USER_INDEXES I JOIN USER_CONSTRAINTS C
                          ON C.INDEX_NAME=I.INDEX_NAME
                        WHERE C.CONSTRAINT_NAME=?
                        """)) {
                    index.setString(1,constraintName);
                    try (ResultSet found=index.executeQuery()) {
                        return found.next()
                                && currentSchema(connection).equalsIgnoreCase(found.getString(2))
                                && "CM_ENGINE_COMMAND".equals(found.getString(3))
                                && "UNIQUE".equals(found.getString(4))
                                && "VALID".equals(found.getString(5))
                                && "VISIBLE".equals(found.getString(6))
                                && List.of("ID_").equals(indexEntries(
                                connection,found.getString(1))) && !found.next();
                    }
                }
            }
        }

        private static String currentSchema(Connection connection) throws SQLException {
            try (var statement=connection.prepareStatement(
                    "SELECT SYS_CONTEXT('USERENV','CURRENT_SCHEMA') FROM DUAL");
                 ResultSet row=statement.executeQuery()) {
                row.next(); return row.getString(1);
            }
        }

        private static List<String> constraintColumns(Connection connection, String name)
                throws SQLException {
            var values = new ArrayList<String>();
            try (var statement = connection.prepareStatement("SELECT COLUMN_NAME FROM USER_CONS_COLUMNS "
                    + "WHERE CONSTRAINT_NAME=? ORDER BY POSITION")) {
                statement.setString(1,name);
                try (ResultSet rows=statement.executeQuery()) { while(rows.next()) values.add(rows.getString(1)); }
            }
            return values;
        }

        private static String referencedConstraint(Connection connection, String name)
                throws SQLException {
            try (var statement = connection.prepareStatement("""
                    SELECT TABLE_NAME FROM USER_CONSTRAINTS WHERE CONSTRAINT_NAME=?
                    """)) {
                statement.setString(1,name);
                try (ResultSet row=statement.executeQuery()) {
                    if (!row.next()) return null;
                    return row.getString(1)+":"+String.join(",",constraintColumns(connection,name));
                }
            }
        }

        private static List<String> indexEntries(Connection connection, String name)
                throws SQLException {
            var values = new ArrayList<String>();
            try (var statement = connection.prepareStatement("""
                    SELECT C.COLUMN_NAME,C.COLUMN_POSITION
                    FROM USER_IND_COLUMNS C
                    WHERE C.INDEX_NAME=? ORDER BY C.COLUMN_POSITION
                    """)) {
                statement.setString(1,name);
                try (ResultSet rows=statement.executeQuery()) {
                    while(rows.next()) {
                        String expression = indexExpression(connection, name, rows.getInt(2));
                        values.add(normalize(expression == null ? rows.getString(1) : expression));
                    }
                }
            }
            return values;
        }

        private static String indexExpression(Connection connection, String index, int position)
                throws SQLException {
            try (var statement = connection.prepareStatement("""
                    SELECT COLUMN_EXPRESSION FROM USER_IND_EXPRESSIONS
                    WHERE INDEX_NAME=? AND COLUMN_POSITION=?
                    """)) {
                statement.setString(1, index);
                statement.setInt(2, position);
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) return null;
                    try (java.io.Reader reader = rows.getCharacterStream(1)) {
                        if (reader == null) return null;
                        StringBuilder value = new StringBuilder();
                        char[] buffer = new char[256];
                        for (int count; (count = reader.read(buffer)) >= 0;) value.append(buffer, 0, count);
                        return value.toString();
                    } catch (java.io.IOException e) {
                        throw new SQLException("Could not read Oracle index expression", e);
                    }
                }
            }
        }

        private static Integer integer(ResultSet rows, int column) throws SQLException {
            int value=rows.getInt(column); return rows.wasNull()?null:value;
        }
    }

    record ActualColumn(String type, Integer charLength, String charUsed,
                        Integer precision, Integer scale, boolean nullable,
                        String defaultValue) {
        boolean matches(ColumnContract expected) {
            if (!expected.type().equals(type) || expected.nullable()!=nullable
                    || !Objects.equals(expected.defaultValue(),defaultValue)) return false;
            return switch (type) {
                case "VARCHAR2" -> Objects.equals(expected.size(),charLength)
                        && "B".equals(charUsed) && precision==null && scale==null;
                case "NUMBER" -> Objects.equals(expected.size(),precision)
                        && Objects.equals(expected.scale(),scale) && charUsed==null;
                case "TIMESTAMP(6) WITH TIME ZONE" -> Objects.equals(expected.scale(),scale)
                        && precision==null && charUsed==null;
                case "CLOB" -> precision==null && scale==null && charUsed==null;
                default -> false;
            };
        }
    }
}
