--------------------------------------------------------------------------------
-- Case Management Service — Oracle Database Schema
-- Target: Oracle 19c+ (JSON stored as CLOB with IS JSON check constraints;
--         on 21c+ you may switch those columns to the native JSON datatype).
--
-- Conventions
--   * Schema prefix CM_. Deployed as its OWN schema in the SAME database as
--     the Camunda 7 engine (design doc §10.2): one transaction manager, but a
--     clean upgrade boundary.
--   * References to Camunda entities (task id, process instance id) are stored
--     as plain VARCHAR2 WITHOUT foreign keys: Camunda deletes runtime rows on
--     completion, so hard FKs would break. Correlation is by ID + history.
--   * Most primary keys are VARCHAR2(64) application-generated IDs. Case IDs
--     are globally unique: '{engineId}:{uuid}' (design principle 6); case
--     definition IDs are tenant-qualified '{tenant}:{key}:{version}'.
--   * Every mutable resource carries VERSION_ (NUMBER) for optimistic locking;
--     the REST layer derives the ETag from it (design principle 2 / App. B).
--   * TIMESTAMP WITH TIME ZONE everywhere; engines run in different regions.
--   * Trailing underscore on reserved-ish names (VERSION_, COMMENT_) to avoid
--     Oracle keyword clashes, mirroring Camunda's own naming style.
--------------------------------------------------------------------------------

--------------------------------------------------------------------------------
-- 1. CASE DEFINITIONS (design doc §3, API /case-definitions)
--------------------------------------------------------------------------------

CREATE TABLE CM_CASE_DEF (
    ID_               VARCHAR2(64)   NOT NULL,       -- {tenant}:{key}:{version}
    KEY_              VARCHAR2(255)  NOT NULL,
    VERSION_NO_       NUMBER(10)     NOT NULL,
    NAME_             VARCHAR2(255)  NOT NULL,
    TENANT_ID_        VARCHAR2(64),
    DESCRIPTION_      VARCHAR2(2000),
    SLA_POLICY_ID_    VARCHAR2(64),
    ROLES_JSON_       CLOB,                           -- ["owner","handler",...]
    ATTACH_CATS_JSON_ CLOB,                           -- attachment categories
    FORMS_JSON_       CLOB,                           -- {formKey: JSON Schema}
    ROUTING_JSON_     CLOB,                           -- [{condition, queueId}]
    DEPLOYED_AT_      TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    DEPLOYED_BY_      VARCHAR2(255),
    CONSTRAINT PK_CM_CASE_DEF PRIMARY KEY (ID_),
    CONSTRAINT UQ_CM_CASE_DEF UNIQUE (KEY_, VERSION_NO_, TENANT_ID_),
    CONSTRAINT CK_CM_DEF_ROLES  CHECK (ROLES_JSON_       IS JSON),
    CONSTRAINT CK_CM_DEF_CATS   CHECK (ATTACH_CATS_JSON_ IS JSON),
    CONSTRAINT CK_CM_DEF_FORMS  CHECK (FORMS_JSON_       IS JSON),
    CONSTRAINT CK_CM_DEF_ROUTE  CHECK (ROUTING_JSON_     IS JSON)
);

CREATE INDEX IX_CM_CASE_DEF_KEY ON CM_CASE_DEF (KEY_, TENANT_ID_);

-- Plan-item templates of a definition (normalized: queried per item state
-- machine evaluation; forms/roles stay JSON because they are read whole).
CREATE TABLE CM_PLAN_ITEM_DEF (
    ID_               VARCHAR2(64)   NOT NULL,
    CASE_DEF_ID_      VARCHAR2(64)   NOT NULL,
    DEF_KEY_          VARCHAR2(255)  NOT NULL,        -- id within the model
    TYPE_             VARCHAR2(20)   NOT NULL,
    NAME_             VARCHAR2(255)  NOT NULL,
    PARENT_STAGE_KEY_ VARCHAR2(255),
    MANUAL_ACT_       NUMBER(1)      DEFAULT 1 NOT NULL,
    REQUIRED_         NUMBER(1)      DEFAULT 0 NOT NULL,
    REPETITION_       NUMBER(1)      DEFAULT 0 NOT NULL,
    ENTRY_CRIT_JSON_  CLOB,                           -- ["expr", ...]
    EXIT_CRIT_JSON_   CLOB,
    FORM_KEY_         VARCHAR2(255),
    PROC_DEF_KEY_     VARCHAR2(255),                  -- for PROCESS_TASK
    CAND_GROUPS_JSON_ CLOB,
    SORT_ORDER_       NUMBER(10)     DEFAULT 0 NOT NULL,
    CONSTRAINT PK_CM_PI_DEF PRIMARY KEY (ID_),
    CONSTRAINT FK_CM_PI_DEF_CASEDEF FOREIGN KEY (CASE_DEF_ID_)
        REFERENCES CM_CASE_DEF (ID_) ON DELETE CASCADE,
    CONSTRAINT UQ_CM_PI_DEF UNIQUE (CASE_DEF_ID_, DEF_KEY_),
    CONSTRAINT CK_CM_PI_DEF_TYPE CHECK
        (TYPE_ IN ('STAGE','HUMAN_TASK','PROCESS_TASK','MILESTONE')),
    CONSTRAINT CK_CM_PI_DEF_ENTRY CHECK (ENTRY_CRIT_JSON_ IS JSON),
    CONSTRAINT CK_CM_PI_DEF_EXIT  CHECK (EXIT_CRIT_JSON_  IS JSON),
    CONSTRAINT CK_CM_PI_DEF_CG    CHECK (CAND_GROUPS_JSON_ IS JSON)
);

-- Who may start / administer a case type (API /case-definitions/{key}/identity-links)
CREATE TABLE CM_DEF_IDENTITY_LINK (
    ID_          VARCHAR2(64)  NOT NULL,
    DEF_KEY_     VARCHAR2(255) NOT NULL,              -- links bind to key, not version
    TENANT_ID_   VARCHAR2(64),
    USER_ID_     VARCHAR2(255),
    GROUP_ID_    VARCHAR2(255),
    TYPE_        VARCHAR2(30)  NOT NULL,
    CONSTRAINT PK_CM_DEF_IDL PRIMARY KEY (ID_),
    CONSTRAINT CK_CM_DEF_IDL_TYPE CHECK (TYPE_ IN ('candidateStarter','administrator')),
    CONSTRAINT CK_CM_DEF_IDL_TGT  CHECK (USER_ID_ IS NOT NULL OR GROUP_ID_ IS NOT NULL)
);

CREATE INDEX IX_CM_DEF_IDL_KEY ON CM_DEF_IDENTITY_LINK (DEF_KEY_, TENANT_ID_);

--------------------------------------------------------------------------------
-- 2. CASES (API /cases)
--------------------------------------------------------------------------------

CREATE TABLE CM_CASE (
    ID_               VARCHAR2(140)  NOT NULL,        -- {engineId}:{uuid}
    ENGINE_ID_        VARCHAR2(64)   NOT NULL,
    TENANT_ID_        VARCHAR2(64),
    CASE_DEF_ID_      VARCHAR2(64)   NOT NULL,
    CASE_DEF_KEY_     VARCHAR2(255)  NOT NULL,        -- denormalized for queries
    CASE_DEF_VER_     NUMBER(10)     NOT NULL,
    BUSINESS_KEY_     VARCHAR2(255),
    TITLE_            VARCHAR2(500),
    STATE_            VARCHAR2(20)   NOT NULL,
    PRIORITY_         VARCHAR2(20)   DEFAULT 'MEDIUM' NOT NULL,
    ASSIGNEE_         VARCHAR2(255),
    QUEUE_ID_         VARCHAR2(64),
    INITIATOR_        VARCHAR2(255),
    SLA_STATUS_       VARCHAR2(20)   DEFAULT 'NONE' NOT NULL, -- denormalized
    OUTCOME_          VARCHAR2(255),
    CANCEL_REASON_    VARCHAR2(2000),
    VARIABLES_JSON_   CLOB,
    VERSION_          NUMBER(19)     DEFAULT 0 NOT NULL,  -- optimistic lock / ETag
    CREATED_AT_       TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    UPDATED_AT_       TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    CLOSED_AT_        TIMESTAMP WITH TIME ZONE,
    CONSTRAINT PK_CM_CASE PRIMARY KEY (ID_),
    CONSTRAINT FK_CM_CASE_DEF FOREIGN KEY (CASE_DEF_ID_)
        REFERENCES CM_CASE_DEF (ID_),
    CONSTRAINT CK_CM_CASE_STATE CHECK
        (STATE_ IN ('CREATED','ACTIVE','SUSPENDED','CLOSED','CANCELLED')),
    CONSTRAINT CK_CM_CASE_PRIO CHECK
        (PRIORITY_ IN ('LOW','MEDIUM','HIGH','CRITICAL')),
    CONSTRAINT CK_CM_CASE_SLA CHECK
        (SLA_STATUS_ IN ('ON_TRACK','WARNING','BREACHED','NONE')),
    CONSTRAINT CK_CM_CASE_VARS CHECK (VARIABLES_JSON_ IS JSON)
);

-- Worklist query patterns from GET /cases (design doc §5)
CREATE INDEX IX_CM_CASE_WORKLIST ON CM_CASE (TENANT_ID_, STATE_, ASSIGNEE_);
CREATE INDEX IX_CM_CASE_DEFKEY   ON CM_CASE (CASE_DEF_KEY_, STATE_);
CREATE INDEX IX_CM_CASE_BKEY     ON CM_CASE (BUSINESS_KEY_);
CREATE INDEX IX_CM_CASE_QUEUE    ON CM_CASE (QUEUE_ID_, STATE_);
CREATE INDEX IX_CM_CASE_SLA      ON CM_CASE (SLA_STATUS_, STATE_);
CREATE INDEX IX_CM_CASE_CREATED  ON CM_CASE (CREATED_AT_);
CREATE INDEX IX_CM_CASE_CLOSED   ON CM_CASE (CLOSED_AT_);   -- /case-history

-- Optional free-text search over title/business key (GET /cases?freeText=):
-- CREATE INDEX IX_CM_CASE_TEXT ON CM_CASE (TITLE_)
--   INDEXTYPE IS CTXSYS.CONTEXT PARAMETERS ('SYNC (ON COMMIT)');

--------------------------------------------------------------------------------
-- 3. RUNTIME PLAN ITEMS (API /cases/{id}/plan-items)
--------------------------------------------------------------------------------

CREATE TABLE CM_PLAN_ITEM (
    ID_               VARCHAR2(64)   NOT NULL,
    CASE_ID_          VARCHAR2(140)  NOT NULL,
    PI_DEF_ID_        VARCHAR2(64),                   -- NULL for ad-hoc items
    TYPE_             VARCHAR2(20)   NOT NULL,
    NAME_             VARCHAR2(255)  NOT NULL,
    STATE_            VARCHAR2(20)   NOT NULL,
    PARENT_STAGE_ID_  VARCHAR2(64),
    AD_HOC_           NUMBER(1)      DEFAULT 0 NOT NULL,
    REPETITION_NO_    NUMBER(10)     DEFAULT 1 NOT NULL,
    CAMUNDA_TASK_ID_  VARCHAR2(64),                   -- loose ref, no FK
    PROC_INST_ID_     VARCHAR2(64),                   -- loose ref, no FK
    TERM_REASON_      VARCHAR2(2000),
    VERSION_          NUMBER(19)     DEFAULT 0 NOT NULL,
    CREATED_AT_       TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    UPDATED_AT_       TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    ENDED_AT_         TIMESTAMP WITH TIME ZONE,
    CONSTRAINT PK_CM_PLAN_ITEM PRIMARY KEY (ID_),
    CONSTRAINT FK_CM_PI_CASE FOREIGN KEY (CASE_ID_)
        REFERENCES CM_CASE (ID_) ON DELETE CASCADE,
    CONSTRAINT FK_CM_PI_PARENT FOREIGN KEY (PARENT_STAGE_ID_)
        REFERENCES CM_PLAN_ITEM (ID_),
    CONSTRAINT CK_CM_PI_TYPE CHECK
        (TYPE_ IN ('STAGE','HUMAN_TASK','PROCESS_TASK','MILESTONE')),
    CONSTRAINT CK_CM_PI_STATE CHECK
        (STATE_ IN ('AVAILABLE','ENABLED','ACTIVE','COMPLETED','TERMINATED'))
);

CREATE INDEX IX_CM_PI_CASE  ON CM_PLAN_ITEM (CASE_ID_, STATE_);
CREATE INDEX IX_CM_PI_CTASK ON CM_PLAN_ITEM (CAMUNDA_TASK_ID_);
CREATE INDEX IX_CM_PI_PROC  ON CM_PLAN_ITEM (PROC_INST_ID_);

--------------------------------------------------------------------------------
-- 4. TASKS (case-context enrichment over Camunda's task service; API /tasks)
--    Camunda's ACT_RU_TASK holds the live task; this table adds case context
--    and survives task completion (Camunda moves rows to history).
--------------------------------------------------------------------------------

CREATE TABLE CM_TASK (
    ID_               VARCHAR2(64)   NOT NULL,
    CASE_ID_          VARCHAR2(140)  NOT NULL,
    PLAN_ITEM_ID_     VARCHAR2(64)   NOT NULL,
    CAMUNDA_TASK_ID_  VARCHAR2(64),                   -- loose ref
    NAME_             VARCHAR2(255)  NOT NULL,
    DESCRIPTION_      VARCHAR2(2000),
    STATE_            VARCHAR2(20)   DEFAULT 'OPEN' NOT NULL,
    ASSIGNEE_         VARCHAR2(255),
    DELEGATED_BY_     VARCHAR2(255),
    CAND_GROUPS_JSON_ CLOB,
    FORM_KEY_         VARCHAR2(255),
    PRIORITY_         NUMBER(3)      DEFAULT 50 NOT NULL,
    DUE_AT_           TIMESTAMP WITH TIME ZONE,
    OUTCOME_          VARCHAR2(255),
    VERSION_          NUMBER(19)     DEFAULT 0 NOT NULL,
    CREATED_AT_       TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    UPDATED_AT_       TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    COMPLETED_AT_     TIMESTAMP WITH TIME ZONE,
    CONSTRAINT PK_CM_TASK PRIMARY KEY (ID_),
    CONSTRAINT FK_CM_TASK_CASE FOREIGN KEY (CASE_ID_)
        REFERENCES CM_CASE (ID_) ON DELETE CASCADE,
    CONSTRAINT FK_CM_TASK_PI FOREIGN KEY (PLAN_ITEM_ID_)
        REFERENCES CM_PLAN_ITEM (ID_),
    CONSTRAINT CK_CM_TASK_STATE CHECK
        (STATE_ IN ('OPEN','CLAIMED','COMPLETED','TERMINATED')),
    CONSTRAINT CK_CM_TASK_CG CHECK (CAND_GROUPS_JSON_ IS JSON)
);

CREATE INDEX IX_CM_TASK_ASSIGNEE ON CM_TASK (ASSIGNEE_, STATE_);
CREATE INDEX IX_CM_TASK_CASE     ON CM_TASK (CASE_ID_);
CREATE INDEX IX_CM_TASK_DUE      ON CM_TASK (DUE_AT_, STATE_);
CREATE INDEX IX_CM_TASK_CTASK    ON CM_TASK (CAMUNDA_TASK_ID_);

-- Linked BPMN processes (API /cases/{id}/processes)
CREATE TABLE CM_LINKED_PROCESS (
    ID_             VARCHAR2(64)   NOT NULL,
    CASE_ID_        VARCHAR2(140)  NOT NULL,
    PLAN_ITEM_ID_   VARCHAR2(64),
    PROC_INST_ID_   VARCHAR2(64)   NOT NULL,          -- Camunda id, loose ref
    PROC_DEF_KEY_   VARCHAR2(255)  NOT NULL,
    STATE_          VARCHAR2(20)   DEFAULT 'ACTIVE' NOT NULL,
    STARTED_AT_     TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    ENDED_AT_       TIMESTAMP WITH TIME ZONE,
    CONSTRAINT PK_CM_LPROC PRIMARY KEY (ID_),
    CONSTRAINT FK_CM_LPROC_CASE FOREIGN KEY (CASE_ID_)
        REFERENCES CM_CASE (ID_) ON DELETE CASCADE,
    CONSTRAINT UQ_CM_LPROC UNIQUE (PROC_INST_ID_),
    CONSTRAINT CK_CM_LPROC_STATE CHECK
        (STATE_ IN ('ACTIVE','COMPLETED','TERMINATED','SUSPENDED'))
);

CREATE INDEX IX_CM_LPROC_CASE ON CM_LINKED_PROCESS (CASE_ID_);

--------------------------------------------------------------------------------
-- 5. MILESTONES (API /cases/{id}/milestones) — runtime state; the milestone
--    itself is a plan item, this table records achievement metadata.
--------------------------------------------------------------------------------

CREATE TABLE CM_MILESTONE (
    ID_            VARCHAR2(64)   NOT NULL,
    CASE_ID_       VARCHAR2(140)  NOT NULL,
    PLAN_ITEM_ID_  VARCHAR2(64)   NOT NULL,
    NAME_          VARCHAR2(255)  NOT NULL,
    ACHIEVED_      NUMBER(1)      DEFAULT 0 NOT NULL,
    ACHIEVED_AT_   TIMESTAMP WITH TIME ZONE,
    ACHIEVED_BY_   VARCHAR2(255),
    CONSTRAINT PK_CM_MILESTONE PRIMARY KEY (ID_),
    CONSTRAINT FK_CM_MS_CASE FOREIGN KEY (CASE_ID_)
        REFERENCES CM_CASE (ID_) ON DELETE CASCADE,
    CONSTRAINT FK_CM_MS_PI FOREIGN KEY (PLAN_ITEM_ID_)
        REFERENCES CM_PLAN_ITEM (ID_)
);

CREATE INDEX IX_CM_MS_CASE ON CM_MILESTONE (CASE_ID_);

--------------------------------------------------------------------------------
-- 6. SLA (design doc §7; API /sla-policies, /cases/{id}/slas)
--------------------------------------------------------------------------------

CREATE TABLE CM_BUSINESS_CALENDAR (
    ID_            VARCHAR2(64)   NOT NULL,
    NAME_          VARCHAR2(255)  NOT NULL,
    TENANT_ID_     VARCHAR2(64),
    DEFINITION_JSON_ CLOB NOT NULL,   -- working hours, holidays, timezone
    CONSTRAINT PK_CM_BCAL PRIMARY KEY (ID_),
    CONSTRAINT CK_CM_BCAL_JSON CHECK (DEFINITION_JSON_ IS JSON)
);

CREATE TABLE CM_SLA_POLICY (
    ID_            VARCHAR2(64)   NOT NULL,
    NAME_          VARCHAR2(255)  NOT NULL,
    TENANT_ID_     VARCHAR2(64),
    SELECTOR_      VARCHAR2(2000),                    -- FEEL/JUEL expression
    CALENDAR_ID_   VARCHAR2(64),
    CONSTRAINT PK_CM_SLA_POLICY PRIMARY KEY (ID_),
    CONSTRAINT FK_CM_SLAP_CAL FOREIGN KEY (CALENDAR_ID_)
        REFERENCES CM_BUSINESS_CALENDAR (ID_)
);

CREATE TABLE CM_SLA_TARGET (
    ID_             VARCHAR2(64)   NOT NULL,
    POLICY_ID_      VARCHAR2(64)   NOT NULL,
    TARGET_KEY_     VARCHAR2(64)   NOT NULL,          -- firstResponse, resolution
    NAME_           VARCHAR2(255)  NOT NULL,
    DURATION_ISO_   VARCHAR2(30)   NOT NULL,          -- PT8H
    WARNING_ISO_    VARCHAR2(30),                     -- PT6H
    PAUSED_STATES_JSON_ CLOB,                         -- legacy name; pause reasons JSON
    BREACH_ACTIONS_JSON_ CLOB,                        -- ["ESCALATE","EMIT_EVENT"]
    CONSTRAINT PK_CM_SLA_TARGET PRIMARY KEY (ID_),
    CONSTRAINT FK_CM_SLAT_POLICY FOREIGN KEY (POLICY_ID_)
        REFERENCES CM_SLA_POLICY (ID_) ON DELETE CASCADE,
    CONSTRAINT UQ_CM_SLA_TARGET UNIQUE (POLICY_ID_, TARGET_KEY_),
    CONSTRAINT CK_CM_SLAT_PS CHECK (PAUSED_STATES_JSON_  IS JSON),
    CONSTRAINT CK_CM_SLAT_BA CHECK (BREACH_ACTIONS_JSON_ IS JSON)
);

-- Runtime clock per case per target. The SLA job scheduler scans DUE_AT_ /
-- WARN_AT_ of RUNNING records; pause/resume shifts deadlines by pause length.
CREATE TABLE CM_SLA_RECORD (
    ID_             VARCHAR2(64)   NOT NULL,
    CASE_ID_        VARCHAR2(140)  NOT NULL,
    TARGET_ID_      VARCHAR2(64)   NOT NULL,
    STATUS_         VARCHAR2(20)   DEFAULT 'RUNNING' NOT NULL,
    STARTED_AT_     TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    DUE_AT_         TIMESTAMP WITH TIME ZONE NOT NULL,
    WARN_AT_        TIMESTAMP WITH TIME ZONE,
    PAUSED_AT_      TIMESTAMP WITH TIME ZONE,
    PAUSED_REASON_  VARCHAR2(500),
    PAUSED_TOTAL_SECS_ NUMBER(19)  DEFAULT 0 NOT NULL,
    MET_AT_         TIMESTAMP WITH TIME ZONE,
    BREACHED_AT_    TIMESTAMP WITH TIME ZONE,
    VERSION_        NUMBER(19)     DEFAULT 0 NOT NULL,
    CONSTRAINT PK_CM_SLA_REC PRIMARY KEY (ID_),
    CONSTRAINT FK_CM_SLAR_CASE FOREIGN KEY (CASE_ID_)
        REFERENCES CM_CASE (ID_) ON DELETE CASCADE,
    CONSTRAINT FK_CM_SLAR_TARGET FOREIGN KEY (TARGET_ID_)
        REFERENCES CM_SLA_TARGET (ID_),
    CONSTRAINT CK_CM_SLAR_STATUS CHECK
        (STATUS_ IN ('RUNNING','PAUSED','MET','BREACHED'))
);

CREATE INDEX IX_CM_SLAR_CASE ON CM_SLA_RECORD (CASE_ID_);
CREATE INDEX IX_CM_SLAR_DUE  ON CM_SLA_RECORD (STATUS_, DUE_AT_);  -- scheduler scan
CREATE INDEX IX_CM_SLAR_WARN ON CM_SLA_RECORD (STATUS_, WARN_AT_);

--------------------------------------------------------------------------------
-- 7. PARTICIPANTS & COLLABORATION (API /cases/{id}/participants|comments|
--    documents|links, /cases/{id}/attachment-categories)
--------------------------------------------------------------------------------

CREATE TABLE CM_PARTICIPANT (
    ID_          VARCHAR2(64)   NOT NULL,
    CASE_ID_     VARCHAR2(140)  NOT NULL,
    USER_ID_     VARCHAR2(255),
    GROUP_ID_    VARCHAR2(255),
    ROLE_        VARCHAR2(64)   NOT NULL,             -- owner, handler, reviewer, watcher
    ADDED_AT_    TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    ADDED_BY_    VARCHAR2(255),
    CONSTRAINT PK_CM_PARTICIPANT PRIMARY KEY (ID_),
    CONSTRAINT FK_CM_PART_CASE FOREIGN KEY (CASE_ID_)
        REFERENCES CM_CASE (ID_) ON DELETE CASCADE,
    CONSTRAINT CK_CM_PART_TGT CHECK (USER_ID_ IS NOT NULL OR GROUP_ID_ IS NOT NULL),
    CONSTRAINT UQ_CM_PARTICIPANT UNIQUE (CASE_ID_, USER_ID_, GROUP_ID_, ROLE_)
);

CREATE INDEX IX_CM_PART_USER ON CM_PARTICIPANT (USER_ID_);   -- "my cases" query

CREATE TABLE CM_COMMENT (
    ID_          VARCHAR2(64)   NOT NULL,
    CASE_ID_     VARCHAR2(140)  NOT NULL,
    AUTHOR_      VARCHAR2(255)  NOT NULL,
    TEXT_        CLOB           NOT NULL,
    VISIBILITY_  VARCHAR2(10)   DEFAULT 'internal' NOT NULL,
    CREATED_AT_  TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT PK_CM_COMMENT PRIMARY KEY (ID_),
    CONSTRAINT FK_CM_COMMENT_CASE FOREIGN KEY (CASE_ID_)
        REFERENCES CM_CASE (ID_) ON DELETE CASCADE,
    CONSTRAINT CK_CM_COMMENT_VIS CHECK (VISIBILITY_ IN ('internal','external'))
);

CREATE INDEX IX_CM_COMMENT_CASE ON CM_COMMENT (CASE_ID_, CREATED_AT_);

CREATE TABLE CM_DOCUMENT (
    ID_          VARCHAR2(64)   NOT NULL,
    CASE_ID_     VARCHAR2(140)  NOT NULL,
    NAME_        VARCHAR2(500)  NOT NULL,
    CATEGORY_    VARCHAR2(255),
    MIME_TYPE_   VARCHAR2(255),
    SIZE_BYTES_  NUMBER(19),
    CONTENT_URL_ VARCHAR2(2000) NOT NULL,             -- DMS/S3 ref, no blobs here
    UPLOADED_BY_ VARCHAR2(255),
    UPLOADED_AT_ TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT PK_CM_DOCUMENT PRIMARY KEY (ID_),
    CONSTRAINT FK_CM_DOC_CASE FOREIGN KEY (CASE_ID_)
        REFERENCES CM_CASE (ID_) ON DELETE CASCADE
);

CREATE INDEX IX_CM_DOC_CASE ON CM_DOCUMENT (CASE_ID_);

-- Case-to-case relations; TARGET_CASE_ID_ has NO foreign key on purpose:
-- it may reference a case on ANOTHER engine (globally unique id, App. F).
CREATE TABLE CM_CASE_LINK (
    ID_             VARCHAR2(64)   NOT NULL,
    CASE_ID_        VARCHAR2(140)  NOT NULL,
    TARGET_CASE_ID_ VARCHAR2(140)  NOT NULL,
    TYPE_           VARCHAR2(30)   NOT NULL,
    CREATED_AT_     TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    CREATED_BY_     VARCHAR2(255),
    CONSTRAINT PK_CM_CASE_LINK PRIMARY KEY (ID_),
    CONSTRAINT FK_CM_LINK_CASE FOREIGN KEY (CASE_ID_)
        REFERENCES CM_CASE (ID_) ON DELETE CASCADE,
    CONSTRAINT CK_CM_LINK_TYPE CHECK
        (TYPE_ IN ('parentOf','childOf','duplicateOf','relatedTo','blockedBy')),
    CONSTRAINT UQ_CM_CASE_LINK UNIQUE (CASE_ID_, TARGET_CASE_ID_, TYPE_)
);

CREATE INDEX IX_CM_LINK_TARGET ON CM_CASE_LINK (TARGET_CASE_ID_);

--------------------------------------------------------------------------------
-- 8. QUEUES & SAVED FILTERS (API /queues, /saved-filters)
--------------------------------------------------------------------------------

CREATE TABLE CM_QUEUE (
    ID_             VARCHAR2(64)   NOT NULL,
    NAME_           VARCHAR2(255)  NOT NULL,
    TENANT_ID_      VARCHAR2(64),
    CAND_GROUPS_JSON_ CLOB,
    CONSTRAINT PK_CM_QUEUE PRIMARY KEY (ID_),
    CONSTRAINT UQ_CM_QUEUE UNIQUE (NAME_, TENANT_ID_),
    CONSTRAINT CK_CM_QUEUE_CG CHECK (CAND_GROUPS_JSON_ IS JSON)
);
-- Queue membership is derived: CM_CASE.QUEUE_ID_ / CM_TASK candidate groups.
-- Item counts and oldest-age come from queries, not stored state.

CREATE TABLE CM_SAVED_FILTER (
    ID_            VARCHAR2(64)   NOT NULL,
    NAME_          VARCHAR2(255)  NOT NULL,
    OWNER_         VARCHAR2(255)  NOT NULL,
    TENANT_ID_     VARCHAR2(64),
    SHARED_        NUMBER(1)      DEFAULT 0 NOT NULL,
    CRITERIA_JSON_ CLOB           NOT NULL,           -- GET /cases params
    SORT_          VARCHAR2(255),
    VERSION_       NUMBER(19)     DEFAULT 0 NOT NULL,
    CREATED_AT_    TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT PK_CM_SAVED_FILTER PRIMARY KEY (ID_),
    CONSTRAINT CK_CM_SF_CRIT CHECK (CRITERIA_JSON_ IS JSON)
);

CREATE INDEX IX_CM_SF_OWNER ON CM_SAVED_FILTER (OWNER_, TENANT_ID_);

--------------------------------------------------------------------------------
-- 9. BULK OPERATIONS (API /cases/bulk, /operations; App. G)
--------------------------------------------------------------------------------

CREATE TABLE CM_BULK_OPERATION (
    ID_             VARCHAR2(64)   NOT NULL,
    TENANT_ID_      VARCHAR2(64),
    ACTION_         VARCHAR2(30)   NOT NULL,
    PARAMS_JSON_    CLOB,
    FILTER_ID_      VARCHAR2(64),
    STATUS_         VARCHAR2(30)   DEFAULT 'PENDING' NOT NULL,
    TOTAL_ITEMS_    NUMBER(10)     DEFAULT 0 NOT NULL,
    PROCESSED_      NUMBER(10)     DEFAULT 0 NOT NULL,
    REQUESTED_BY_   VARCHAR2(255)  NOT NULL,
    IDEMPOTENCY_KEY_ VARCHAR2(128),
    STARTED_AT_     TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    FINISHED_AT_    TIMESTAMP WITH TIME ZONE,
    CONSTRAINT PK_CM_BULK_OP PRIMARY KEY (ID_),
    CONSTRAINT CK_CM_BULK_STATUS CHECK (STATUS_ IN
        ('PENDING','RUNNING','COMPLETED','COMPLETED_WITH_ERRORS','CANCELLED')),
    CONSTRAINT CK_CM_BULK_ACTION CHECK (ACTION_ IN
        ('ASSIGN','CLOSE','CANCEL','SUSPEND','RESUME','SET_PRIORITY','ADD_PARTICIPANT')),
    CONSTRAINT CK_CM_BULK_PARAMS CHECK (PARAMS_JSON_ IS JSON)
);

-- One row per selected case: work queue for the background processor AND the
-- per-item error report of GET /operations/{id}. COMPLETED_WITH_ERRORS = some
-- rows FAILED (App. G: no all-or-nothing rollback).
CREATE TABLE CM_BULK_OPERATION_ITEM (
    OPERATION_ID_ VARCHAR2(64)   NOT NULL,
    CASE_ID_      VARCHAR2(140)  NOT NULL,
    STATUS_       VARCHAR2(20)   DEFAULT 'PENDING' NOT NULL,
    ERROR_        VARCHAR2(2000),
    PROCESSED_AT_ TIMESTAMP WITH TIME ZONE,
    CONSTRAINT PK_CM_BULK_ITEM PRIMARY KEY (OPERATION_ID_, CASE_ID_),
    CONSTRAINT FK_CM_BITEM_OP FOREIGN KEY (OPERATION_ID_)
        REFERENCES CM_BULK_OPERATION (ID_) ON DELETE CASCADE,
    CONSTRAINT CK_CM_BITEM_STATUS CHECK
        (STATUS_ IN ('PENDING','DONE','FAILED','SKIPPED'))
);

--------------------------------------------------------------------------------
-- 10. EVENTS, WEBHOOKS, AUDIT (design doc §6; App. A)
--------------------------------------------------------------------------------

-- Append-only CloudEvents store. SEQ_ is the pull-API cursor
-- (GET /events?after=): a gap-free-enough, monotonic ordering per engine.
-- Consider INTERVAL partitioning by TIME_ for retention management.
CREATE SEQUENCE CM_EVENT_SEQ CACHE 1000;

CREATE TABLE CM_EVENT (
    SEQ_          NUMBER(19)     NOT NULL,
    ID_           VARCHAR2(64)   NOT NULL,            -- CloudEvents id (dedup key)
    SOURCE_       VARCHAR2(255)  NOT NULL,
    TYPE_         VARCHAR2(255)  NOT NULL,
    SUBJECT_      VARCHAR2(140),                      -- usually the case id
    TENANT_ID_    VARCHAR2(64),
    TIME_         TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    DATA_JSON_    CLOB,
    CONSTRAINT PK_CM_EVENT PRIMARY KEY (SEQ_),
    CONSTRAINT UQ_CM_EVENT_ID UNIQUE (ID_),
    CONSTRAINT CK_CM_EVENT_DATA CHECK (DATA_JSON_ IS JSON)
);
-- PARTITION BY RANGE (TIME_) INTERVAL (NUMTOYMINTERVAL(1,'MONTH')) ...

CREATE INDEX IX_CM_EVENT_SUBJECT ON CM_EVENT (SUBJECT_, SEQ_);   -- /cases/{id}/events
CREATE INDEX IX_CM_EVENT_TYPE    ON CM_EVENT (TYPE_, SEQ_);

CREATE TABLE CM_WEBHOOK_SUB (
    ID_            VARCHAR2(64)   NOT NULL,
    TENANT_ID_     VARCHAR2(64),
    URL_           VARCHAR2(2000) NOT NULL,
    EVENT_TYPES_JSON_ CLOB        NOT NULL,           -- ["com.example.case.sla.*"]
    ACTIVE_        NUMBER(1)      DEFAULT 1 NOT NULL,
    SECRET_HASH_   VARCHAR2(255)  NOT NULL,           -- store hashed, never plain
    MAX_RETRIES_   NUMBER(3)      DEFAULT 5 NOT NULL,
    VERSION_       NUMBER(19)     DEFAULT 0 NOT NULL,
    CREATED_AT_    TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT PK_CM_WEBHOOK PRIMARY KEY (ID_),
    CONSTRAINT CK_CM_WH_TYPES CHECK (EVENT_TYPES_JSON_ IS JSON)
);

-- Delivery/retry state machine per (subscription, event). Rows in DEAD state
-- are the dead-letter queue (GET /webhooks/{id}/dead-letters); redelivery
-- resets STATUS_ to PENDING.
CREATE TABLE CM_WEBHOOK_DELIVERY (
    ID_            VARCHAR2(64)   NOT NULL,
    WEBHOOK_ID_    VARCHAR2(64)   NOT NULL,
    EVENT_SEQ_     NUMBER(19)     NOT NULL,
    STATUS_        VARCHAR2(20)   DEFAULT 'PENDING' NOT NULL,
    ATTEMPTS_      NUMBER(3)      DEFAULT 0 NOT NULL,
    NEXT_ATTEMPT_AT_ TIMESTAMP WITH TIME ZONE,
    LAST_STATUS_CODE_ NUMBER(3),
    LAST_ERROR_    VARCHAR2(2000),
    DELIVERED_AT_  TIMESTAMP WITH TIME ZONE,
    CONSTRAINT PK_CM_WH_DELIVERY PRIMARY KEY (ID_),
    CONSTRAINT FK_CM_WHD_SUB FOREIGN KEY (WEBHOOK_ID_)
        REFERENCES CM_WEBHOOK_SUB (ID_) ON DELETE CASCADE,
    CONSTRAINT FK_CM_WHD_EVENT FOREIGN KEY (EVENT_SEQ_)
        REFERENCES CM_EVENT (SEQ_),
    CONSTRAINT UQ_CM_WH_DELIVERY UNIQUE (WEBHOOK_ID_, EVENT_SEQ_),
    CONSTRAINT CK_CM_WHD_STATUS CHECK
        (STATUS_ IN ('PENDING','DELIVERED','RETRYING','DEAD'))
);

CREATE INDEX IX_CM_WHD_DISPATCH ON CM_WEBHOOK_DELIVERY
    (STATUS_, NEXT_ATTEMPT_AT_);                      -- dispatcher scan
CREATE INDEX IX_CM_WHD_DEAD ON CM_WEBHOOK_DELIVERY (WEBHOOK_ID_, STATUS_);

-- Audit log: append-only, richer than the event log (before/after images).
-- Kept separate from CM_EVENT: events are integration contract, audit is
-- compliance record with its own retention (design doc §10.4).
CREATE TABLE CM_AUDIT_LOG (
    ID_           VARCHAR2(64)   NOT NULL,
    CASE_ID_      VARCHAR2(140),                      -- nullable for platform-level actions; no FK: survives case purge
    TENANT_ID_    VARCHAR2(64),
    TS_           TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    ACTOR_        VARCHAR2(255)  NOT NULL,
    ACTION_       VARCHAR2(100)  NOT NULL,            -- planitem.start, case.close
    RESOURCE_TYPE_ VARCHAR2(50)  NOT NULL,
    RESOURCE_ID_  VARCHAR2(140)  NOT NULL,
    BEFORE_JSON_  CLOB,
    AFTER_JSON_   CLOB,
    CONSTRAINT PK_CM_AUDIT PRIMARY KEY (ID_),
    CONSTRAINT CK_CM_AUDIT_B CHECK (BEFORE_JSON_ IS JSON),
    CONSTRAINT CK_CM_AUDIT_A CHECK (AFTER_JSON_  IS JSON)
);
-- PARTITION BY RANGE (TS_) INTERVAL (NUMTOYMINTERVAL(1,'MONTH')) ...

CREATE INDEX IX_CM_AUDIT_CASE ON CM_AUDIT_LOG (CASE_ID_, TS_);

--------------------------------------------------------------------------------
-- 11. IDEMPOTENCY (design principle 5; App. E)
--------------------------------------------------------------------------------

-- key -> stored response, per endpoint scope. A retention job deletes rows
-- older than the retention window (e.g. 48h). REQUEST_HASH_ detects the
-- "same key, different payload" case -> 409.
CREATE TABLE CM_IDEMPOTENCY_KEY (
    KEY_           VARCHAR2(128)  NOT NULL,
    SCOPE_         VARCHAR2(100)  NOT NULL,           -- e.g. POST/cases
    REQUEST_HASH_  VARCHAR2(64)   NOT NULL,           -- SHA-256 of payload
    RESPONSE_STATUS_ NUMBER(3)    NOT NULL,
    RESPONSE_JSON_ CLOB,
    CREATED_AT_    TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT PK_CM_IDEMPOTENCY PRIMARY KEY (KEY_, SCOPE_),
    CONSTRAINT CK_CM_IDEM_RESP CHECK (RESPONSE_JSON_ IS JSON)
);

CREATE INDEX IX_CM_IDEM_CREATED ON CM_IDEMPOTENCY_KEY (CREATED_AT_); -- cleanup job

--------------------------------------------------------------------------------
-- Notes for the implementing team
--------------------------------------------------------------------------------
-- 1. Optimistic locking: UPDATE ... SET VERSION_ = VERSION_ + 1
--    WHERE ID_ = :id AND VERSION_ = :expected; 0 rows updated => HTTP 412.
--    Same semantics as JPA @Version; ETag = VERSION_ (App. B.5).
-- 2. Transactionality: case mutation + CM_EVENT insert + CM_AUDIT_LOG insert +
--    CM_WEBHOOK_DELIVERY fan-out rows are ONE local transaction (transactional
--    outbox). The webhook dispatcher reads CM_WEBHOOK_DELIVERY asynchronously,
--    so no event is emitted for a rolled-back change and none is lost.
-- 3. Variables: stored as one JSON document (read/written whole via the API).
--    If per-variable querying becomes hot, add a JSON search index:
--    CREATE SEARCH INDEX IX_CM_CASE_VARS ON CM_CASE (VARIABLES_JSON_) FOR JSON;
-- 4. Retention/GDPR (design doc §10.4): interval partitioning on CM_EVENT and
--    CM_AUDIT_LOG enables cheap DROP PARTITION retention; case anonymization
--    updates VARIABLES_JSON_/TITLE_/comments in place, never deletes audit rows.
-- 5. Queue counts (GET /queues): derive via
--    SELECT QUEUE_ID_, COUNT(*), MIN(CREATED_AT_) FROM CM_CASE
--    WHERE STATE_ = 'ACTIVE' AND ASSIGNEE_ IS NULL GROUP BY QUEUE_ID_;
--    add a materialized view only if this becomes hot.
