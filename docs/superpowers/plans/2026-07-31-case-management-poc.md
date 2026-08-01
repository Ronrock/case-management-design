# Case Management PoC on Operaton — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a backend reference implementation of the case management service on Operaton that runs a complaint case end-to-end in both embedded and remote engine modes, retiring the four risks in `docs/superpowers/specs/2026-07-31-case-management-poc-design.md`.

**Architecture:** A Maven multi-module build producing a Spring Boot starter. `case-management-core` holds the domain, plan-item state machine and persistence, and depends only on an `EngineGateway` interface. Two gateway implementations exist — in-process Operaton Java API, and `engine-rest` over HTTP — and one contract test suite runs against both. All mutations write a transactional outbox (`CM_EVENT` + `CM_AUDIT_LOG` + webhook deliveries) in the same local transaction.

**Tech Stack:** Java 21 · Maven · Operaton 2.1.3 · Spring Boot 4.0.x (pinned transitively by Operaton) · Spring `JdbcClient` (no ORM) · Oracle 23ai Free · Liquibase · JUEL (`org.operaton.bpm.impl.juel`) · networknt json-schema-validator · Testcontainers · JUnit 5 · AssertJ · ArchUnit

## Global Constraints

- **Java 21.** `<maven.compiler.release>21</maven.compiler.release>`. Operaton requires 17+; 21 is the target here.
- **Jackson: two generations, no mixing.** `case-management-core` has **Jackson 2 only** (`com.fasterxml.jackson.*`, declared explicitly, BOM-managed 2.21.5). The web-facing modules (`rest`, `engine-remote`, `spring-boot-starter`, `poc-app`) carry **both**: Jackson 3 (`tools.jackson.*`) is what Spring Boot 4 auto-configures for HTTP JSON, while Jackson 2 arrives transitively through core. Rule: core code and anything touching `json-schema-validator` uses `com.fasterxml.*`; Spring-managed HTTP JSON uses `tools.jackson.*`; never both in one class. Established empirically in Task 1 — see its report.
- **Operaton 2.1.3** — the latest stable release on Maven Central. `2.2.0-M2` is a milestone; `2.2.0-SNAPSHOT` is the local clone at `/Volumes/dockdrive/dev/operaton` and is **for reading only, never a build dependency**.
- **Spring Boot version is not declared by us.** Operaton 2.1.3 pins Spring Boot `4.0.7` / Spring Framework `7.0.8` via `operaton-core-internal-dependencies`. Import Operaton's BOM and let it win. Never add a `spring-boot-starter-parent`.
- **Base package:** `org.casemgmt`. Module-specific subpackages are named per task.
- **`case-management-core` must not import any `org.operaton.bpm.engine` type.** Enforced by ArchUnit in Task 25. The `EngineGateway` interface and its DTOs live in core; implementations do not. The one deliberate exception is `org.operaton.bpm.impl.juel` (Task 7) — an expression library, not the process engine, and the ArchUnit rule is scoped accordingly.
- **Every mutable table carries `VERSION_`.** Updates are always `UPDATE … SET VERSION_ = VERSION_ + 1 WHERE ID_ = :id AND VERSION_ = :expected`; zero rows affected means a concurrency conflict, never a retry.
- **The DDL in `db-design.sql` is the source of truth for the schema.** It is copied into the build, never re-typed. PoC-only additions go in a separate, clearly-labelled changeset.
- **`casemgmt.events.type-prefix` has no default.** Startup fails if webhooks are enabled and it is unset.
- **No case-type knowledge outside `case-management-poc-app`.** No `core`, `rest`, or gateway class may contain the string `complaint`.
- **Timestamps are `OffsetDateTime`** in Java and `TIMESTAMP WITH TIME ZONE` in Oracle. Never `LocalDateTime`.
- **Tests run against real Oracle** via Testcontainers. No H2 substitutes; the DDL uses `IS JSON` constraints that H2 does not implement.
- **Commit after every task**, using the message given in the task's final step.

## File Structure

```
pom.xml                                    root aggregator
docker-compose.yml                         Oracle 23ai Free + schema bootstrap
docker/oracle-init/01-create-schemas.sql   creates OPERATON and CM users

case-management-core/
  src/main/java/org/casemgmt/
    domain/          CaseInstance, PlanItem, CaseDefinition … records + enums
    engine/          EngineGateway interface + its DTOs (no Operaton imports)
    repo/            JdbcClient repositories, one per aggregate
    rules/           JuelCriterionEvaluator, PlanModelEvaluator
    service/         CaseService, PlanItemService, CaseTaskService, …
    event/           EventPublisher (outbox writer), CloudEvent envelope, AuditWriter
    sla/             BusinessCalendar, SlaService, SlaSweeper
    error/           CaseConflictException, OptimisticLockException, NotFoundException
  src/main/resources/db/changelog/
    db.changelog-master.xml
    cm-poc-additions.xml                   CM_ENGINE_COMMAND + ENGINE_SYNC_ columns
    sql/cm-schema-v1.sql                   copied from repo-root db-design.sql at build

case-management-engine-embedded/
  src/main/java/org/casemgmt/engine/embedded/EmbeddedEngineGateway.java

case-management-engine-remote/
  src/main/java/org/casemgmt/engine/remote/
    RemoteEngineGateway.java, EngineCommandOutbox.java, EngineCommandDispatcher.java

case-management-rest/
  src/main/java/org/casemgmt/rest/
    controller/      CaseController, PlanItemController, TaskController, …
    dto/             request/response records
    filter/          IdempotencyFilter, ETagSupport
    policy/          ActionPolicy, AvailableAction
    error/           ProblemDetailHandler

case-management-spring-boot-starter/
  src/main/java/org/casemgmt/starter/
    CaseManagementAutoConfiguration.java, CaseManagementProperties.java,
    EmbeddedEngineAutoConfiguration.java, RemoteEngineAutoConfiguration.java
  src/main/resources/META-INF/spring/
    org.springframework.boot.autoconfigure.AutoConfiguration.imports

case-management-poc-app/
  src/main/resources/
    application.yaml
    definitions/complaint-v1.json          the case definition + form schemas
    processes/decision-letter.bpmn
  src/test/java/org/casemgmt/poc/
    GenericConsumerIT.java                 drives the API with no case-type constants
    ComplaintEndToEndIT.java
FINDINGS.md                                risk verdicts (created in Task 28)
```

---

## Phase 0 — Foundation

### Task 1: Build skeleton and dependency verification

**Files:**
- Create: `pom.xml`
- Create: `case-management-core/pom.xml`, `case-management-engine-embedded/pom.xml`, `case-management-engine-remote/pom.xml`, `case-management-rest/pom.xml`, `case-management-spring-boot-starter/pom.xml`, `case-management-poc-app/pom.xml`
- Create: `case-management-core/src/test/java/org/casemgmt/BuildEnvironmentTest.java`

**Interfaces:**
- Consumes: nothing
- Produces: Maven modules with groupId `org.casemgmt`, version `0.1.0-SNAPSHOT`; every later task adds code to one of these modules.

- [ ] **Step 1: Write the failing test**

`case-management-core/src/test/java/org/casemgmt/BuildEnvironmentTest.java`:

```java
package org.casemgmt;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class BuildEnvironmentTest {

    @Test
    void runsOnJava21OrLater() {
        assertThat(Runtime.version().feature()).isGreaterThanOrEqualTo(21);
    }

    @Test
    void springJdbcClientIsOnTheClasspath() throws Exception {
        // Fails with ClassNotFoundException if the Spring BOM did not resolve.
        Class.forName("org.springframework.jdbc.core.simple.JdbcClient");
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./mvnw -q -pl case-management-core test`
Expected: FAIL — there is no `pom.xml` yet, Maven reports "The goal you specified requires a project to execute but there is no POM in this directory".

- [ ] **Step 3: Write the root aggregator pom**

`pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <groupId>org.casemgmt</groupId>
  <artifactId>case-management-root</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <packaging>pom</packaging>
  <name>Case Management on Operaton</name>

  <modules>
    <module>case-management-core</module>
    <module>case-management-engine-embedded</module>
    <module>case-management-engine-remote</module>
    <module>case-management-rest</module>
    <module>case-management-spring-boot-starter</module>
    <module>case-management-poc-app</module>
  </modules>

  <properties>
    <maven.compiler.release>21</maven.compiler.release>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <version.operaton>2.1.3</version.operaton>
    <version.testcontainers>1.21.3</version.testcontainers>
    <version.json-schema-validator>1.5.8</version.json-schema-validator>
    <version.archunit>1.4.1</version.archunit>
    <version.swagger-request-validator>2.44.9</version.swagger-request-validator>
  </properties>

  <dependencyManagement>
    <dependencies>
      <!-- Operaton pins Spring Boot 4.0.7 / Spring 7.0.8. Import it FIRST so it wins. -->
      <dependency>
        <groupId>org.operaton.bpm</groupId>
        <artifactId>operaton-bom</artifactId>
        <version>${version.operaton}</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
      <dependency>
        <groupId>org.operaton.bpm.springboot</groupId>
        <artifactId>operaton-bpm-spring-boot-starter</artifactId>
        <version>${version.operaton}</version>
      </dependency>
      <dependency>
        <groupId>org.operaton.bpm.springboot</groupId>
        <artifactId>operaton-bpm-spring-boot-starter-rest</artifactId>
        <version>${version.operaton}</version>
      </dependency>
      <dependency>
        <groupId>org.testcontainers</groupId>
        <artifactId>testcontainers-bom</artifactId>
        <version>${version.testcontainers}</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
      <dependency>
        <groupId>com.networknt</groupId>
        <artifactId>json-schema-validator</artifactId>
        <version>${version.json-schema-validator}</version>
      </dependency>
      <dependency>
        <groupId>com.tngtech.archunit</groupId>
        <artifactId>archunit-junit5</artifactId>
        <version>${version.archunit}</version>
        <scope>test</scope>
      </dependency>
      <dependency>
        <groupId>com.atlassian.oai</groupId>
        <artifactId>swagger-request-validator-core</artifactId>
        <version>${version.swagger-request-validator}</version>
        <scope>test</scope>
      </dependency>

      <!-- Internal modules -->
      <dependency>
        <groupId>org.casemgmt</groupId>
        <artifactId>case-management-core</artifactId>
        <version>${project.version}</version>
      </dependency>
      <dependency>
        <groupId>org.casemgmt</groupId>
        <artifactId>case-management-engine-embedded</artifactId>
        <version>${project.version}</version>
      </dependency>
      <dependency>
        <groupId>org.casemgmt</groupId>
        <artifactId>case-management-engine-remote</artifactId>
        <version>${project.version}</version>
      </dependency>
      <dependency>
        <groupId>org.casemgmt</groupId>
        <artifactId>case-management-rest</artifactId>
        <version>${project.version}</version>
      </dependency>
      <dependency>
        <groupId>org.casemgmt</groupId>
        <artifactId>case-management-spring-boot-starter</artifactId>
        <version>${project.version}</version>
      </dependency>
    </dependencies>
  </dependencyManagement>

  <dependencies>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-test</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <pluginManagement>
      <plugins>
        <plugin>
          <groupId>org.apache.maven.plugins</groupId>
          <artifactId>maven-surefire-plugin</artifactId>
          <version>3.5.2</version>
        </plugin>
        <plugin>
          <groupId>org.apache.maven.plugins</groupId>
          <artifactId>maven-failsafe-plugin</artifactId>
          <version>3.5.2</version>
          <executions>
            <execution>
              <goals><goal>integration-test</goal><goal>verify</goal></goals>
            </execution>
          </executions>
        </plugin>
      </plugins>
    </pluginManagement>
  </build>
</project>
```

- [ ] **Step 4: Write the module poms**

`case-management-core/pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>org.casemgmt</groupId>
    <artifactId>case-management-root</artifactId>
    <version>0.1.0-SNAPSHOT</version>
  </parent>
  <artifactId>case-management-core</artifactId>

  <dependencies>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-jdbc</artifactId>
    </dependency>
    <dependency>
      <groupId>org.liquibase</groupId>
      <artifactId>liquibase-core</artifactId>
    </dependency>
    <dependency>
      <groupId>com.networknt</groupId>
      <artifactId>json-schema-validator</artifactId>
    </dependency>
    <!-- JUEL: pulled in on its own, NOT via operaton-engine, so core stays engine-free.
         org.operaton.bpm.impl.juel is an expression library, not the process engine. -->
    <dependency>
      <groupId>org.operaton.bpm.juel</groupId>
      <artifactId>operaton-juel</artifactId>
      <version>${version.operaton}</version>
    </dependency>
    <dependency>
      <groupId>org.testcontainers</groupId>
      <artifactId>oracle-free</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>com.oracle.database.jdbc</groupId>
      <artifactId>ojdbc11</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>
</project>
```

The remaining five module poms follow the same shape. Their `<artifactId>` and `<dependencies>` differ only as listed:

- `case-management-engine-embedded`: depends on `case-management-core` and `org.operaton.bpm.springboot:operaton-bpm-spring-boot-starter`.
- `case-management-engine-remote`: depends on `case-management-core` and `org.springframework.boot:spring-boot-starter-web` (for `RestClient`).
- `case-management-rest`: depends on `case-management-core`, `spring-boot-starter-web`, `spring-boot-starter-validation`, `spring-boot-starter-security`.
- `case-management-spring-boot-starter`: depends on `case-management-core`, `case-management-rest`, and both gateway modules with `<optional>true</optional>`.
- `case-management-poc-app`: `<packaging>jar</packaging>`, depends on `case-management-spring-boot-starter`, `operaton-bpm-spring-boot-starter`, `operaton-bpm-spring-boot-starter-rest`, `com.oracle.database.jdbc:ojdbc11` (compile scope), plus test-scoped `oracle-free`, `archunit-junit5`, `swagger-request-validator-core`.

Write each with the same `<parent>` block as above.

- [ ] **Step 5: Run the test to verify it passes**

Run: `./mvnw -q -pl case-management-core test`
Expected: PASS, both tests green.

- [ ] **Step 6: Verify the resolved dependency versions**

Run: `./mvnw -q -pl case-management-core dependency:tree | grep -E 'spring-core|spring-boot|jackson|ojdbc'`

Expected: `spring-boot` at **4.0.7**, `spring-core` at **7.0.8**.

**Record what Jackson resolves to.** Spring Boot 4 manages Jackson 3, whose packages are `tools.jackson.databind.*`, not `com.fasterxml.jackson.databind.*`. Later tasks import `tools.jackson.databind.ObjectMapper`. If the tree shows Jackson 2 instead, note it in `FINDINGS.md` and use `com.fasterxml.jackson.*` consistently from here on. Do not mix the two.

Note also that `com.networknt:json-schema-validator` brings its own Jackson 2 dependency. That is expected and harmless — the two Jackson generations have different coordinates and package names and coexist.

- [ ] **Step 7: Commit**

```bash
git add pom.xml case-management-*/pom.xml case-management-core/src
git commit -m "build: multi-module skeleton on Operaton 2.1.3 / Java 21"
```

---

### Task 2: Oracle environment and schema migration

**Files:**
- Create: `docker-compose.yml`, `docker/oracle-init/01-create-schemas.sql`
- Create: `case-management-core/src/main/resources/db/changelog/db.changelog-master.xml`
- Create: `case-management-core/src/main/resources/db/changelog/cm-poc-additions.xml`
- Modify: `case-management-core/pom.xml` (add the resource-copy execution)
- Create: `case-management-core/src/test/java/org/casemgmt/OracleTestBase.java`
- Create: `case-management-core/src/test/java/org/casemgmt/SchemaMigrationTest.java`

**Interfaces:**
- Consumes: modules from Task 1
- Produces: `OracleTestBase` — an abstract JUnit base class exposing `protected static DataSource dataSource()` and `protected JdbcClient jdbc()` against a migrated Oracle container. **Every persistence test in later tasks extends it.**

- [ ] **Step 1: Write the failing test**

`case-management-core/src/test/java/org/casemgmt/SchemaMigrationTest.java`:

```java
package org.casemgmt;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class SchemaMigrationTest extends OracleTestBase {

    @Test
    void createsAll25TablesFromTheDesignDdl() {
        Integer tables = jdbc().sql("SELECT COUNT(*) FROM USER_TABLES WHERE TABLE_NAME LIKE 'CM!_%' ESCAPE '!'")
                .query(Integer.class).single();
        // 25 from db-design.sql + CM_ENGINE_COMMAND from the PoC changeset,
        // minus none. DATABASECHANGELOG* do not match the CM_ prefix.
        assertThat(tables).isEqualTo(26);
    }

    @Test
    void enforcesTheIsJsonConstraintOnCaseVariables() {
        jdbc().sql("""
                INSERT INTO CM_CASE_DEF (ID_, KEY_, VERSION_NO_, NAME_)
                VALUES ('d:1', 'd', 1, 'D')""").update();

        assertThatThrownBy(() -> jdbc().sql("""
                INSERT INTO CM_CASE (ID_, ENGINE_ID_, CASE_DEF_ID_, CASE_DEF_KEY_, CASE_DEF_VER_,
                                     STATE_, VARIABLES_JSON_)
                VALUES ('e:1', 'e', 'd:1', 'd', 1, 'ACTIVE', 'not json')""").update())
                .hasMessageContaining("CK_CM_CASE_VARS");
    }

    @Test
    void addsEngineSyncColumnToTasks() {
        Integer count = jdbc().sql("""
                SELECT COUNT(*) FROM USER_TAB_COLUMNS
                WHERE TABLE_NAME = 'CM_TASK' AND COLUMN_NAME = 'ENGINE_SYNC_'""")
                .query(Integer.class).single();
        assertThat(count).isEqualTo(1);
    }
}
```

Add the static import `static org.assertj.core.api.Assertions.assertThatThrownBy;`.

- [ ] **Step 2: Write the Oracle test base**

`case-management-core/src/test/java/org/casemgmt/OracleTestBase.java`:

```java
package org.casemgmt;

import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.oracle.OracleContainer;

import javax.sql.DataSource;
import java.sql.Connection;

@Testcontainers
public abstract class OracleTestBase {

    // Reused across all test classes in the JVM: starting Oracle takes ~40s.
    private static final OracleContainer ORACLE =
            new OracleContainer("gvenzl/oracle-free:23-slim-faststart")
                    .withUsername("cm")
                    .withPassword("cm")
                    .withReuse(true);

    private static DataSource dataSource;

    static {
        ORACLE.start();
        DriverManagerDataSource ds = new DriverManagerDataSource(
                ORACLE.getJdbcUrl(), ORACLE.getUsername(), ORACLE.getPassword());
        ds.setDriverClassName("oracle.jdbc.OracleDriver");
        dataSource = ds;
        migrate(ds);
    }

    private static void migrate(DataSource ds) {
        try (Connection c = ds.getConnection()) {
            var database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(c));
            try (var liquibase = new Liquibase(
                    "db/changelog/db.changelog-master.xml",
                    new ClassLoaderResourceAccessor(), database)) {
                liquibase.update("");
            }
        } catch (Exception e) {
            throw new IllegalStateException("Schema migration failed", e);
        }
    }

    protected static DataSource dataSource() {
        return dataSource;
    }

    protected JdbcClient jdbc() {
        return JdbcClient.create(dataSource);
    }
}
```

- [ ] **Step 3: Run it and watch it fail**

Run: `./mvnw -q -pl case-management-core test -Dtest=SchemaMigrationTest`
Expected: FAIL — `Schema migration failed … db/changelog/db.changelog-master.xml does not exist`.

- [ ] **Step 4: Copy the DDL into the build**

`db-design.sql` at the repo root stays the single source of truth. Add this to `case-management-core/pom.xml` inside `<build>`:

```xml
<plugins>
  <plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-resources-plugin</artifactId>
    <executions>
      <execution>
        <id>copy-design-ddl</id>
        <phase>generate-resources</phase>
        <goals><goal>copy-resources</goal></goals>
        <configuration>
          <outputDirectory>${project.build.outputDirectory}/db/changelog/sql</outputDirectory>
          <resources>
            <resource>
              <directory>${project.basedir}/..</directory>
              <includes><include>db-design.sql</include></includes>
            </resource>
          </resources>
        </configuration>
      </execution>
    </executions>
  </plugin>
</plugins>
```

- [ ] **Step 5: Write the changelogs**

`case-management-core/src/main/resources/db/changelog/db.changelog-master.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                   xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                   xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                       http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-latest.xsd">

  <!-- The design DDL, copied verbatim from the repo root at build time.
       It is plain ;-terminated DDL: no PL/SQL blocks, no / delimiters. -->
  <changeSet id="cm-schema-v1" author="casemgmt" runOnChange="false">
    <sqlFile path="db/changelog/sql/db-design.sql"
             relativeToChangelogFile="false"
             splitStatements="true"
             endDelimiter=";"
             stripComments="true"/>
  </changeSet>

  <include file="db/changelog/cm-poc-additions.xml"/>
</databaseChangeLog>
```

`case-management-core/src/main/resources/db/changelog/cm-poc-additions.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                   xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                   xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                       http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-latest.xsd">

  <!-- PoC-ONLY additions (spec §3.5). These are NOT part of the target design:
       they exist because remote mode cannot join the local transaction. -->

  <changeSet id="cm-poc-engine-command" author="casemgmt">
    <createTable tableName="CM_ENGINE_COMMAND">
      <column name="ID_" type="VARCHAR2(64)"><constraints primaryKey="true" nullable="false"/></column>
      <column name="CASE_ID_" type="VARCHAR2(140)"><constraints nullable="false"/></column>
      <column name="TYPE_" type="VARCHAR2(30)"><constraints nullable="false"/></column>
      <column name="PAYLOAD_JSON_" type="CLOB"/>
      <column name="STATUS_" type="VARCHAR2(20)" defaultValue="PENDING"><constraints nullable="false"/></column>
      <column name="ATTEMPTS_" type="NUMBER(3)" defaultValueNumeric="0"><constraints nullable="false"/></column>
      <column name="NEXT_ATTEMPT_AT_" type="TIMESTAMP WITH TIME ZONE"/>
      <column name="LAST_ERROR_" type="VARCHAR2(2000)"/>
      <column name="CREATED_AT_" type="TIMESTAMP WITH TIME ZONE" defaultValueComputed="SYSTIMESTAMP">
        <constraints nullable="false"/>
      </column>
    </createTable>
    <sql>
      ALTER TABLE CM_ENGINE_COMMAND ADD CONSTRAINT CK_CM_ENGCMD_STATUS
        CHECK (STATUS_ IN ('PENDING','RETRYING','DONE','DEAD'))
    </sql>
    <createIndex tableName="CM_ENGINE_COMMAND" indexName="IX_CM_ENGCMD_DUE">
      <column name="STATUS_"/><column name="NEXT_ATTEMPT_AT_"/>
    </createIndex>
  </changeSet>

  <changeSet id="cm-poc-engine-sync-columns" author="casemgmt">
    <addColumn tableName="CM_TASK">
      <column name="ENGINE_SYNC_" type="VARCHAR2(20)" defaultValue="SYNCED">
        <constraints nullable="false"/>
      </column>
    </addColumn>
    <addColumn tableName="CM_LINKED_PROCESS">
      <column name="ENGINE_SYNC_" type="VARCHAR2(20)" defaultValue="SYNCED">
        <constraints nullable="false"/>
      </column>
    </addColumn>
  </changeSet>
</databaseChangeLog>
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `./mvnw -q -pl case-management-core test -Dtest=SchemaMigrationTest`
Expected: PASS, all three tests.

If `cm-schema-v1` fails on a statement, **do not edit `db-design.sql` to work around Liquibase**. Read the failing statement from the error, and if it is a genuine DDL bug, fix it in `db-design.sql` and record the fix in `FINDINGS.md` — validating that file is one of the reasons the PoC runs on real Oracle.

- [ ] **Step 7: Write the local dev environment**

`docker/oracle-init/01-create-schemas.sql`:

```sql
-- Runs once on first container start (gvenzl images execute /container-entrypoint-initdb.d).
ALTER SESSION SET CONTAINER = FREEPDB1;

CREATE USER cm IDENTIFIED BY cm QUOTA UNLIMITED ON USERS;
GRANT CREATE SESSION, CREATE TABLE, CREATE SEQUENCE, CREATE VIEW TO cm;

CREATE USER operaton IDENTIFIED BY operaton QUOTA UNLIMITED ON USERS;
GRANT CREATE SESSION, CREATE TABLE, CREATE SEQUENCE, CREATE VIEW TO operaton;
```

`docker-compose.yml`:

```yaml
services:
  oracle:
    image: gvenzl/oracle-free:23-slim-faststart
    environment:
      ORACLE_PASSWORD: oracle
    ports:
      - "1521:1521"
    volumes:
      - ./docker/oracle-init:/container-entrypoint-initdb.d
    healthcheck:
      test: ["CMD", "healthcheck.sh"]
      interval: 10s
      timeout: 5s
      retries: 30
```

- [ ] **Step 8: Verify the dev database comes up**

Run: `docker compose up -d && docker compose ps`
Expected: the `oracle` service reaches `healthy` within ~60s. Then `docker compose down`.

- [ ] **Step 9: Commit**

```bash
git add docker-compose.yml docker/ case-management-core/
git commit -m "build: Oracle 23ai environment with Liquibase migration from db-design.sql"
```

---

### Task 3: Core domain types

**Files:**
- Create: `case-management-core/src/main/java/org/casemgmt/domain/` — `CaseState.java`, `PlanItemType.java`, `PlanItemState.java`, `TaskState.java`, `CasePriority.java`, `CaseInstance.java`, `PlanItem.java`, `CaseDefinition.java`, `PlanItemDefinition.java`, `CaseTask.java`, `CaseIds.java`
- Create: `case-management-core/src/test/java/org/casemgmt/domain/CaseIdsTest.java`
- Create: `case-management-core/src/test/java/org/casemgmt/domain/CaseStateTest.java`

**Interfaces:**
- Consumes: nothing
- Produces: the vocabulary every later task uses. Exact signatures below — later tasks reference these names without redefining them.

- [ ] **Step 1: Write the failing tests**

`case-management-core/src/test/java/org/casemgmt/domain/CaseIdsTest.java`:

```java
package org.casemgmt.domain;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class CaseIdsTest {

    @Test
    void generatesGloballyUniqueIdsPrefixedWithTheEngineId() {
        String id = CaseIds.newCaseId("eng-a");
        assertThat(id).startsWith("eng-a:").hasSizeGreaterThan(10);
        assertThat(CaseIds.engineIdOf(id)).isEqualTo("eng-a");
    }

    @Test
    void twoIdsFromTheSameEngineDiffer() {
        assertThat(CaseIds.newCaseId("eng-a")).isNotEqualTo(CaseIds.newCaseId("eng-a"));
    }

    @Test
    void rejectsEngineIdsContainingTheSeparator() {
        assertThatThrownBy(() -> CaseIds.newCaseId("eng:a"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not contain ':'");
    }
}
```

`case-management-core/src/test/java/org/casemgmt/domain/CaseStateTest.java`:

```java
package org.casemgmt.domain;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class CaseStateTest {

    @Test
    void activeCasesCanCloseCancelAndSuspend() {
        assertThat(CaseState.ACTIVE.canTransitionTo(CaseState.CLOSED)).isTrue();
        assertThat(CaseState.ACTIVE.canTransitionTo(CaseState.CANCELLED)).isTrue();
        assertThat(CaseState.ACTIVE.canTransitionTo(CaseState.SUSPENDED)).isTrue();
    }

    @Test
    void cancelledIsTerminal() {
        for (CaseState target : CaseState.values()) {
            assertThat(CaseState.CANCELLED.canTransitionTo(target)).isFalse();
        }
    }

    @Test
    void closedCasesReactivateToActive() {
        assertThat(CaseState.CLOSED.canTransitionTo(CaseState.ACTIVE)).isTrue();
        assertThat(CaseState.CLOSED.canTransitionTo(CaseState.SUSPENDED)).isFalse();
    }
}
```

- [ ] **Step 2: Run them and watch them fail**

Run: `./mvnw -q -pl case-management-core test -Dtest='CaseIdsTest,CaseStateTest'`
Expected: FAIL — compilation error, `package org.casemgmt.domain does not exist`.

- [ ] **Step 3: Write the enums**

```java
package org.casemgmt.domain;

import java.util.EnumSet;
import java.util.Set;

public enum CaseState {
    CREATED, ACTIVE, SUSPENDED, CLOSED, CANCELLED;

    private static final java.util.Map<CaseState, Set<CaseState>> ALLOWED = java.util.Map.of(
            CREATED,   EnumSet.of(ACTIVE, CANCELLED),
            ACTIVE,    EnumSet.of(SUSPENDED, CLOSED, CANCELLED),
            SUSPENDED, EnumSet.of(ACTIVE, CANCELLED),
            CLOSED,    EnumSet.of(ACTIVE),          // reactivate
            CANCELLED, EnumSet.noneOf(CaseState.class));

    public boolean canTransitionTo(CaseState target) {
        return ALLOWED.get(this).contains(target);
    }

    public boolean isTerminal() {
        return this == CANCELLED;
    }
}
```

```java
package org.casemgmt.domain;

public enum PlanItemType { STAGE, HUMAN_TASK, PROCESS_TASK, MILESTONE }
```

```java
package org.casemgmt.domain;

public enum PlanItemState {
    AVAILABLE, ENABLED, ACTIVE, COMPLETED, TERMINATED;

    public boolean isEnded() {
        return this == COMPLETED || this == TERMINATED;
    }
}
```

```java
package org.casemgmt.domain;

public enum TaskState { OPEN, CLAIMED, COMPLETED, TERMINATED }
```

```java
package org.casemgmt.domain;

public enum CasePriority { LOW, MEDIUM, HIGH, CRITICAL }
```

- [ ] **Step 4: Write `CaseIds`**

```java
package org.casemgmt.domain;

import java.util.UUID;

public final class CaseIds {

    private CaseIds() {}

    /** Globally unique case id: {engineId}:{uuid} — spec §Appendix F. */
    public static String newCaseId(String engineId) {
        if (engineId == null || engineId.isBlank()) {
            throw new IllegalArgumentException("engineId must not be blank");
        }
        if (engineId.indexOf(':') >= 0) {
            throw new IllegalArgumentException("engineId must not contain ':' — got " + engineId);
        }
        return engineId + ":" + UUID.randomUUID();
    }

    public static String engineIdOf(String caseId) {
        int sep = caseId.indexOf(':');
        if (sep < 0) throw new IllegalArgumentException("Not a global case id: " + caseId);
        return caseId.substring(0, sep);
    }

    /** Local (non-case) entity id: plan items, tasks, comments, events. */
    public static String newId() {
        return UUID.randomUUID().toString();
    }
}
```

- [ ] **Step 5: Write the records**

```java
package org.casemgmt.domain;

import java.time.OffsetDateTime;
import java.util.Map;

public record CaseInstance(
        String id, String engineId, String tenantId,
        String caseDefId, String caseDefKey, int caseDefVersion,
        String businessKey, String title,
        CaseState state, CasePriority priority,
        String assignee, String queueId, String initiator,
        String slaStatus, String outcome, String cancelReason,
        Map<String, Object> variables,
        long version,
        OffsetDateTime createdAt, OffsetDateTime updatedAt, OffsetDateTime closedAt) {

    public CaseInstance withState(CaseState newState) {
        return new CaseInstance(id, engineId, tenantId, caseDefId, caseDefKey, caseDefVersion,
                businessKey, title, newState, priority, assignee, queueId, initiator,
                slaStatus, outcome, cancelReason, variables, version, createdAt, updatedAt, closedAt);
    }

    public CaseInstance withVariables(Map<String, Object> newVariables) {
        return new CaseInstance(id, engineId, tenantId, caseDefId, caseDefKey, caseDefVersion,
                businessKey, title, state, priority, assignee, queueId, initiator,
                slaStatus, outcome, cancelReason, newVariables, version, createdAt, updatedAt, closedAt);
    }
}
```

```java
package org.casemgmt.domain;

import java.time.OffsetDateTime;

public record PlanItem(
        String id, String caseId, String planItemDefId,
        PlanItemType type, String name, PlanItemState state,
        String parentStageId, boolean adHoc, int repetitionNo,
        String engineTaskId, String processInstanceId, String terminationReason,
        long version,
        OffsetDateTime createdAt, OffsetDateTime updatedAt, OffsetDateTime endedAt) {

    public PlanItem withState(PlanItemState newState) {
        return new PlanItem(id, caseId, planItemDefId, type, name, newState, parentStageId,
                adHoc, repetitionNo, engineTaskId, processInstanceId, terminationReason,
                version, createdAt, updatedAt, endedAt);
    }
}
```

```java
package org.casemgmt.domain;

import java.util.List;

public record PlanItemDefinition(
        String id, String caseDefId, String defKey,
        PlanItemType type, String name, String parentStageKey,
        boolean manualActivation, boolean required, boolean repetition,
        List<String> entryCriteria, List<String> exitCriteria,
        String formKey, String processDefinitionKey, List<String> candidateGroups,
        int sortOrder) {}
```

```java
package org.casemgmt.domain;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public record CaseDefinition(
        String id, String key, int versionNo, String name, String tenantId,
        String description, String slaPolicyId,
        List<String> roles, List<String> attachmentCategories,
        Map<String, Object> forms,
        List<PlanItemDefinition> planItems,
        OffsetDateTime deployedAt, String deployedBy) {

    public PlanItemDefinition planItem(String defKey) {
        return planItems.stream().filter(p -> p.defKey().equals(defKey)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No plan item '" + defKey + "' in " + id));
    }
}
```

```java
package org.casemgmt.domain;

import java.time.OffsetDateTime;
import java.util.List;

public record CaseTask(
        String id, String caseId, String planItemId, String engineTaskId,
        String name, String description, TaskState state,
        String assignee, String delegatedBy, List<String> candidateGroups,
        String formKey, int priority, OffsetDateTime dueAt, String outcome,
        EngineSync engineSync,
        long version,
        OffsetDateTime createdAt, OffsetDateTime updatedAt, OffsetDateTime completedAt) {

    /** PoC-only: remote mode cannot create the engine task in the local transaction (spec §3.5). */
    public enum EngineSync { PENDING, SYNCED, FAILED }
}
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./mvnw -q -pl case-management-core test -Dtest='CaseIdsTest,CaseStateTest'`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add case-management-core/src
git commit -m "feat(core): domain records, state enums and global id generation"
```

---

## Phase 1 — Persistence

### Task 4: Case repository with optimistic locking

**Files:**
- Create: `case-management-core/src/main/java/org/casemgmt/repo/JsonCodec.java`
- Create: `case-management-core/src/main/java/org/casemgmt/repo/CaseRepository.java`
- Create: `case-management-core/src/main/java/org/casemgmt/repo/CaseQuery.java`
- Create: `case-management-core/src/main/java/org/casemgmt/error/OptimisticLockException.java`
- Create: `case-management-core/src/main/java/org/casemgmt/error/NotFoundException.java`
- Create: `case-management-core/src/test/java/org/casemgmt/repo/CaseRepositoryTest.java`

**Interfaces:**
- Consumes: `CaseInstance`, `CaseState`, `CasePriority`, `CaseIds` (Task 3); `OracleTestBase` (Task 2)
- Produces:
  - `JsonCodec.toJson(Object) : String`, `JsonCodec.toMap(String) : Map<String,Object>`, `JsonCodec.toList(String) : List<String>`
  - `CaseRepository.insert(CaseInstance) : void`
  - `CaseRepository.findById(String) : Optional<CaseInstance>`
  - `CaseRepository.require(String) : CaseInstance` — throws `NotFoundException`
  - `CaseRepository.update(CaseInstance, long expectedVersion) : CaseInstance` — throws `OptimisticLockException`, returns the instance with `version` incremented
  - `CaseRepository.query(CaseQuery) : List<CaseInstance>`
  - `CaseQuery(String tenantId, CaseState state, String assignee, String caseDefKey, String businessKey, int offset, int limit)`

- [ ] **Step 1: Write the failing test**

`case-management-core/src/test/java/org/casemgmt/repo/CaseRepositoryTest.java`:

```java
package org.casemgmt.repo;

import org.casemgmt.OracleTestBase;
import org.casemgmt.domain.*;
import org.casemgmt.error.NotFoundException;
import org.casemgmt.error.OptimisticLockException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class CaseRepositoryTest extends OracleTestBase {

    private CaseRepository repo;

    @BeforeEach
    void setUp() {
        repo = new CaseRepository(jdbc());
        jdbc().sql("DELETE FROM CM_CASE").update();
        jdbc().sql("DELETE FROM CM_CASE_DEF").update();
        jdbc().sql("""
                INSERT INTO CM_CASE_DEF (ID_, KEY_, VERSION_NO_, NAME_)
                VALUES ('complaint:1', 'complaint', 1, 'Complaint')""").update();
    }

    private CaseInstance newCase(String id) {
        return new CaseInstance(id, "eng-a", "t1", "complaint:1", "complaint", 1,
                "BK-1", "Broken widget", CaseState.ACTIVE, CasePriority.HIGH,
                null, null, "alice", "NONE", null, null,
                Map.of("amount", 250, "channel", "web"), 0L,
                OffsetDateTime.now(), OffsetDateTime.now(), null);
    }

    @Test
    void roundTripsACaseIncludingJsonVariables() {
        CaseInstance c = newCase("eng-a:1");
        repo.insert(c);

        CaseInstance loaded = repo.require("eng-a:1");
        assertThat(loaded.title()).isEqualTo("Broken widget");
        assertThat(loaded.state()).isEqualTo(CaseState.ACTIVE);
        assertThat(loaded.priority()).isEqualTo(CasePriority.HIGH);
        assertThat(loaded.variables()).containsEntry("channel", "web");
        assertThat(loaded.version()).isZero();
    }

    @Test
    void updateIncrementsTheVersion() {
        repo.insert(newCase("eng-a:2"));
        CaseInstance loaded = repo.require("eng-a:2");

        CaseInstance updated = repo.update(loaded.withState(CaseState.CLOSED), loaded.version());

        assertThat(updated.version()).isEqualTo(1L);
        assertThat(repo.require("eng-a:2").state()).isEqualTo(CaseState.CLOSED);
    }

    @Test
    void updateWithAStaleVersionThrows() {
        repo.insert(newCase("eng-a:3"));
        CaseInstance loaded = repo.require("eng-a:3");
        repo.update(loaded.withState(CaseState.CLOSED), loaded.version());   // now v1

        assertThatThrownBy(() -> repo.update(loaded.withState(CaseState.CANCELLED), 0L))
                .isInstanceOf(OptimisticLockException.class)
                .hasMessageContaining("eng-a:3");
    }

    @Test
    void requireThrowsForUnknownIds() {
        assertThatThrownBy(() -> repo.require("eng-a:nope"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void queriesByStateAndAssignee() {
        repo.insert(newCase("eng-a:4"));
        CaseInstance closed = newCase("eng-a:5").withState(CaseState.CLOSED);
        repo.insert(closed);

        var active = repo.query(new CaseQuery("t1", CaseState.ACTIVE, null, null, null, 0, 50));

        assertThat(active).extracting(CaseInstance::id).containsExactly("eng-a:4");
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./mvnw -q -pl case-management-core test -Dtest=CaseRepositoryTest`
Expected: FAIL — `package org.casemgmt.repo does not exist`.

- [ ] **Step 3: Write the errors and the JSON codec**

```java
package org.casemgmt.error;

public class NotFoundException extends RuntimeException {
    public NotFoundException(String resourceType, String id) {
        super(resourceType + " not found: " + id);
    }
}
```

```java
package org.casemgmt.error;

public class OptimisticLockException extends RuntimeException {
    public OptimisticLockException(String resourceType, String id, long expectedVersion) {
        super(resourceType + " " + id + " was modified concurrently (expected version "
                + expectedVersion + ")");
    }
}
```

```java
package org.casemgmt.repo;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/**
 * Single place where JSON columns are (de)serialised.
 *
 * Jackson 2 (com.fasterxml), NOT Jackson 3 — settled by Task 1: case-management-core
 * has only Jackson 2 on its classpath, declared explicitly in its pom and resolved at
 * the BOM-managed 2.21.5. Jackson 3 (tools.jackson.*) exists only in the web-facing
 * modules, where Spring Boot 4 auto-configures it. Never mix the two in one class.
 *
 * Oracle CLOB binding note: JdbcClient binds these as String, which Oracle JDBC
 * handles for values under 32 KB. Larger documents need a streaming bind — if any
 * test hits ORA-01461, record it in FINDINGS.md rather than silently truncating.
 */
public final class JsonCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonCodec() {}

    public static String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise " + value.getClass(), e);
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> toMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return MAPPER.readValue(json, Map.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not parse JSON column value", e);
        }
    }

    @SuppressWarnings("unchecked")
    public static List<String> toList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json, List.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not parse JSON column value", e);
        }
    }
}
```

Jackson 2's `writeValueAsString`/`readValue` throw checked `JsonProcessingException`, hence the wrapping — Jackson 3's equivalents are unchecked, so do not copy this pattern into web-module code.

- [ ] **Step 4: Write the query record and the repository**

```java
package org.casemgmt.repo;

import org.casemgmt.domain.CaseState;

public record CaseQuery(String tenantId, CaseState state, String assignee,
                        String caseDefKey, String businessKey, int offset, int limit) {}
```

```java
package org.casemgmt.repo;

import org.casemgmt.domain.*;
import org.casemgmt.error.NotFoundException;
import org.casemgmt.error.OptimisticLockException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class CaseRepository {

    private static final String COLUMNS = """
            ID_, ENGINE_ID_, TENANT_ID_, CASE_DEF_ID_, CASE_DEF_KEY_, CASE_DEF_VER_,
            BUSINESS_KEY_, TITLE_, STATE_, PRIORITY_, ASSIGNEE_, QUEUE_ID_, INITIATOR_,
            SLA_STATUS_, OUTCOME_, CANCEL_REASON_, VARIABLES_JSON_, VERSION_,
            CREATED_AT_, UPDATED_AT_, CLOSED_AT_""";

    private final JdbcClient jdbc;

    public CaseRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(CaseInstance c) {
        jdbc.sql("""
                INSERT INTO CM_CASE (ID_, ENGINE_ID_, TENANT_ID_, CASE_DEF_ID_, CASE_DEF_KEY_,
                    CASE_DEF_VER_, BUSINESS_KEY_, TITLE_, STATE_, PRIORITY_, ASSIGNEE_, QUEUE_ID_,
                    INITIATOR_, SLA_STATUS_, OUTCOME_, CANCEL_REASON_, VARIABLES_JSON_, VERSION_,
                    CREATED_AT_, UPDATED_AT_, CLOSED_AT_)
                VALUES (:id, :engineId, :tenantId, :caseDefId, :caseDefKey, :caseDefVer,
                    :businessKey, :title, :state, :priority, :assignee, :queueId, :initiator,
                    :slaStatus, :outcome, :cancelReason, :variables, :version,
                    :createdAt, :updatedAt, :closedAt)""")
            .param("id", c.id()).param("engineId", c.engineId()).param("tenantId", c.tenantId())
            .param("caseDefId", c.caseDefId()).param("caseDefKey", c.caseDefKey())
            .param("caseDefVer", c.caseDefVersion()).param("businessKey", c.businessKey())
            .param("title", c.title()).param("state", c.state().name())
            .param("priority", c.priority().name()).param("assignee", c.assignee())
            .param("queueId", c.queueId()).param("initiator", c.initiator())
            .param("slaStatus", c.slaStatus() == null ? "NONE" : c.slaStatus())
            .param("outcome", c.outcome()).param("cancelReason", c.cancelReason())
            .param("variables", JsonCodec.toJson(c.variables()))
            .param("version", c.version())
            .param("createdAt", c.createdAt()).param("updatedAt", c.updatedAt())
            .param("closedAt", c.closedAt())
            .update();
    }

    public Optional<CaseInstance> findById(String id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM CM_CASE WHERE ID_ = :id")
                .param("id", id)
                .query(CaseRepository::map)
                .optional();
    }

    public CaseInstance require(String id) {
        return findById(id).orElseThrow(() -> new NotFoundException("Case", id));
    }

    /**
     * Optimistic update. Zero rows affected means someone else wrote first —
     * never retried here, always surfaced as 412 by the REST layer.
     */
    public CaseInstance update(CaseInstance c, long expectedVersion) {
        int rows = jdbc.sql("""
                UPDATE CM_CASE SET
                    TITLE_ = :title, STATE_ = :state, PRIORITY_ = :priority,
                    ASSIGNEE_ = :assignee, QUEUE_ID_ = :queueId, SLA_STATUS_ = :slaStatus,
                    OUTCOME_ = :outcome, CANCEL_REASON_ = :cancelReason,
                    VARIABLES_JSON_ = :variables, CLOSED_AT_ = :closedAt,
                    UPDATED_AT_ = SYSTIMESTAMP, VERSION_ = VERSION_ + 1
                WHERE ID_ = :id AND VERSION_ = :expected""")
            .param("title", c.title()).param("state", c.state().name())
            .param("priority", c.priority().name()).param("assignee", c.assignee())
            .param("queueId", c.queueId())
            .param("slaStatus", c.slaStatus() == null ? "NONE" : c.slaStatus())
            .param("outcome", c.outcome()).param("cancelReason", c.cancelReason())
            .param("variables", JsonCodec.toJson(c.variables()))
            .param("closedAt", c.closedAt())
            .param("id", c.id()).param("expected", expectedVersion)
            .update();

        if (rows == 0) {
            throw new OptimisticLockException("Case", c.id(), expectedVersion);
        }
        return require(c.id());
    }

    public List<CaseInstance> query(CaseQuery q) {
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM CM_CASE WHERE 1 = 1");
        List<Object[]> params = new ArrayList<>();
        if (q.tenantId() != null)    { sql.append(" AND TENANT_ID_ = :tenantId");     params.add(new Object[]{"tenantId", q.tenantId()}); }
        if (q.state() != null)       { sql.append(" AND STATE_ = :state");            params.add(new Object[]{"state", q.state().name()}); }
        if (q.assignee() != null)    { sql.append(" AND ASSIGNEE_ = :assignee");      params.add(new Object[]{"assignee", q.assignee()}); }
        if (q.caseDefKey() != null)  { sql.append(" AND CASE_DEF_KEY_ = :defKey");    params.add(new Object[]{"defKey", q.caseDefKey()}); }
        if (q.businessKey() != null) { sql.append(" AND BUSINESS_KEY_ = :bk");        params.add(new Object[]{"bk", q.businessKey()}); }
        sql.append(" ORDER BY CREATED_AT_ DESC OFFSET :offset ROWS FETCH NEXT :limit ROWS ONLY");

        var spec = jdbc.sql(sql.toString());
        for (Object[] p : params) {
            spec = spec.param((String) p[0], p[1]);
        }
        return spec.param("offset", q.offset())
                   .param("limit", q.limit() <= 0 ? 50 : q.limit())
                   .query(CaseRepository::map)
                   .list();
    }

    private static CaseInstance map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new CaseInstance(
                rs.getString("ID_"), rs.getString("ENGINE_ID_"), rs.getString("TENANT_ID_"),
                rs.getString("CASE_DEF_ID_"), rs.getString("CASE_DEF_KEY_"), rs.getInt("CASE_DEF_VER_"),
                rs.getString("BUSINESS_KEY_"), rs.getString("TITLE_"),
                CaseState.valueOf(rs.getString("STATE_")),
                CasePriority.valueOf(rs.getString("PRIORITY_")),
                rs.getString("ASSIGNEE_"), rs.getString("QUEUE_ID_"), rs.getString("INITIATOR_"),
                rs.getString("SLA_STATUS_"), rs.getString("OUTCOME_"), rs.getString("CANCEL_REASON_"),
                JsonCodec.toMap(rs.getString("VARIABLES_JSON_")),
                rs.getLong("VERSION_"),
                rs.getObject("CREATED_AT_", OffsetDateTime.class),
                rs.getObject("UPDATED_AT_", OffsetDateTime.class),
                rs.getObject("CLOSED_AT_", OffsetDateTime.class));
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./mvnw -q -pl case-management-core test -Dtest=CaseRepositoryTest`
Expected: PASS, all five tests.

- [ ] **Step 6: Commit**

```bash
git add case-management-core/src
git commit -m "feat(core): case repository with optimistic locking and JSON variables"
```

---

### Task 5: Case definition deployment

**Files:**
- Create: `case-management-core/src/main/java/org/casemgmt/repo/CaseDefinitionRepository.java`
- Create: `case-management-core/src/main/java/org/casemgmt/service/CaseDefinitionService.java`
- Create: `case-management-core/src/test/java/org/casemgmt/service/CaseDefinitionServiceTest.java`
- Create: `case-management-core/src/test/resources/definitions/test-definition.json`

**Interfaces:**
- Consumes: `CaseDefinition`, `PlanItemDefinition`, `PlanItemType` (Task 3); `JsonCodec` (Task 4)
- Produces:
  - `CaseDefinitionService.deploy(String json, String deployedBy) : CaseDefinition` — assigns the next version for the key, returns the stored definition
  - `CaseDefinitionRepository.findLatest(String key, String tenantId) : Optional<CaseDefinition>`
  - `CaseDefinitionRepository.listLatest(String tenantId) : List<CaseDefinition>` — latest version per key, backs `GET /case-definitions`
  - `CaseDefinitionRepository.findById(String id) : Optional<CaseDefinition>`
  - `CaseDefinitionRepository.require(String id) : CaseDefinition`
  - `CaseDefinitionRepository.formSchema(String key, String formKey) : Optional<Map<String,Object>>`

- [ ] **Step 1: Write the test fixture**

`case-management-core/src/test/resources/definitions/test-definition.json` — deliberately domain-free, since core must not know about complaints:

```json
{
  "key": "widget-review",
  "name": "Widget Review",
  "tenantId": "t1",
  "roles": ["owner", "handler", "reviewer"],
  "forms": {
    "reviewForm": {
      "$schema": "https://json-schema.org/draft/2020-12/schema",
      "type": "object",
      "required": ["outcome"],
      "properties": {
        "outcome": { "type": "string", "enum": ["approve", "reject"] },
        "note": { "type": "string" }
      }
    }
  },
  "planItems": [
    { "defKey": "intake", "type": "STAGE", "name": "Intake", "manualActivation": false, "sortOrder": 10 },
    { "defKey": "review", "type": "HUMAN_TASK", "name": "Review", "parentStageKey": "intake",
      "manualActivation": false, "required": true, "formKey": "reviewForm",
      "candidateGroups": ["reviewers"], "sortOrder": 20 },
    { "defKey": "reviewed", "type": "MILESTONE", "name": "Reviewed",
      "entryCriteria": ["${items.review.state == 'COMPLETED'}"], "sortOrder": 30 }
  ]
}
```

- [ ] **Step 2: Write the failing test**

`case-management-core/src/test/java/org/casemgmt/service/CaseDefinitionServiceTest.java`:

```java
package org.casemgmt.service;

import org.casemgmt.OracleTestBase;
import org.casemgmt.domain.*;
import org.casemgmt.repo.CaseDefinitionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class CaseDefinitionServiceTest extends OracleTestBase {

    private CaseDefinitionService service;
    private String json;

    @BeforeEach
    void setUp() throws Exception {
        jdbc().sql("DELETE FROM CM_PLAN_ITEM_DEF").update();
        jdbc().sql("DELETE FROM CM_CASE_DEF").update();
        service = new CaseDefinitionService(new CaseDefinitionRepository(jdbc()));
        json = new String(getClass().getResourceAsStream("/definitions/test-definition.json")
                .readAllBytes(), StandardCharsets.UTF_8);
    }

    @Test
    void deploysVersionOneAndExplodesPlanItems() {
        CaseDefinition def = service.deploy(json, "alice");

        assertThat(def.id()).isEqualTo("widget-review:1");
        assertThat(def.versionNo()).isEqualTo(1);
        assertThat(def.planItems()).hasSize(3);
        assertThat(def.planItem("review").required()).isTrue();
        assertThat(def.planItem("review").candidateGroups()).containsExactly("reviewers");
        assertThat(def.planItem("reviewed").entryCriteria())
                .containsExactly("${items.review.state == 'COMPLETED'}");
    }

    @Test
    void redeployingTheSameKeyIncrementsTheVersion() {
        service.deploy(json, "alice");
        CaseDefinition second = service.deploy(json, "alice");

        assertThat(second.versionNo()).isEqualTo(2);
        assertThat(second.id()).isEqualTo("widget-review:2");
    }

    @Test
    void findLatestReturnsTheHighestVersion() {
        service.deploy(json, "alice");
        service.deploy(json, "alice");

        var latest = new CaseDefinitionRepository(jdbc()).findLatest("widget-review", "t1");

        assertThat(latest).isPresent();
        assertThat(latest.get().versionNo()).isEqualTo(2);
    }

    @Test
    void servesFormSchemasByKey() {
        service.deploy(json, "alice");

        var schema = new CaseDefinitionRepository(jdbc()).formSchema("widget-review", "reviewForm");

        assertThat(schema).isPresent();
        assertThat(schema.get()).containsKey("properties");
    }

    @Test
    void planItemDefaultsManualActivationToFalseWhenAbsent() {
        CaseDefinition def = service.deploy(json, "alice");
        assertThat(def.planItem("reviewed").manualActivation()).isFalse();
    }
}
```

- [ ] **Step 3: Run it and watch it fail**

Run: `./mvnw -q -pl case-management-core test -Dtest=CaseDefinitionServiceTest`
Expected: FAIL — `cannot find symbol: class CaseDefinitionService`.

- [ ] **Step 4: Write the repository**

```java
package org.casemgmt.repo;

import org.casemgmt.domain.*;
import org.casemgmt.error.NotFoundException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class CaseDefinitionRepository {

    private final JdbcClient jdbc;

    public CaseDefinitionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public int nextVersion(String key, String tenantId) {
        Integer max = jdbc.sql("""
                SELECT MAX(VERSION_NO_) FROM CM_CASE_DEF
                WHERE KEY_ = :key AND (TENANT_ID_ = :tenant OR (:tenant IS NULL AND TENANT_ID_ IS NULL))""")
                .param("key", key).param("tenant", tenantId)
                .query(Integer.class).optional().orElse(0);
        return max == null ? 1 : max + 1;
    }

    public void insert(CaseDefinition d) {
        jdbc.sql("""
                INSERT INTO CM_CASE_DEF (ID_, KEY_, VERSION_NO_, NAME_, TENANT_ID_, DESCRIPTION_,
                    SLA_POLICY_ID_, ROLES_JSON_, ATTACH_CATS_JSON_, FORMS_JSON_, DEPLOYED_AT_, DEPLOYED_BY_)
                VALUES (:id, :key, :ver, :name, :tenant, :desc, :sla, :roles, :cats, :forms,
                    :deployedAt, :deployedBy)""")
            .param("id", d.id()).param("key", d.key()).param("ver", d.versionNo())
            .param("name", d.name()).param("tenant", d.tenantId()).param("desc", d.description())
            .param("sla", d.slaPolicyId())
            .param("roles", JsonCodec.toJson(d.roles()))
            .param("cats", JsonCodec.toJson(d.attachmentCategories()))
            .param("forms", JsonCodec.toJson(d.forms()))
            .param("deployedAt", d.deployedAt()).param("deployedBy", d.deployedBy())
            .update();

        for (PlanItemDefinition p : d.planItems()) {
            jdbc.sql("""
                    INSERT INTO CM_PLAN_ITEM_DEF (ID_, CASE_DEF_ID_, DEF_KEY_, TYPE_, NAME_,
                        PARENT_STAGE_KEY_, MANUAL_ACT_, REQUIRED_, REPETITION_,
                        ENTRY_CRIT_JSON_, EXIT_CRIT_JSON_, FORM_KEY_, PROC_DEF_KEY_,
                        CAND_GROUPS_JSON_, SORT_ORDER_)
                    VALUES (:id, :defId, :key, :type, :name, :parent, :manual, :required, :repetition,
                        :entry, :exit, :formKey, :procKey, :groups, :sort)""")
                .param("id", p.id()).param("defId", d.id()).param("key", p.defKey())
                .param("type", p.type().name()).param("name", p.name())
                .param("parent", p.parentStageKey())
                .param("manual", p.manualActivation() ? 1 : 0)
                .param("required", p.required() ? 1 : 0)
                .param("repetition", p.repetition() ? 1 : 0)
                .param("entry", JsonCodec.toJson(p.entryCriteria()))
                .param("exit", JsonCodec.toJson(p.exitCriteria()))
                .param("formKey", p.formKey()).param("procKey", p.processDefinitionKey())
                .param("groups", JsonCodec.toJson(p.candidateGroups()))
                .param("sort", p.sortOrder())
                .update();
        }
    }

    public Optional<CaseDefinition> findById(String id) {
        return jdbc.sql("""
                SELECT ID_, KEY_, VERSION_NO_, NAME_, TENANT_ID_, DESCRIPTION_, SLA_POLICY_ID_,
                       ROLES_JSON_, ATTACH_CATS_JSON_, FORMS_JSON_, DEPLOYED_AT_, DEPLOYED_BY_
                FROM CM_CASE_DEF WHERE ID_ = :id""")
                .param("id", id)
                .query((rs, n) -> mapDefinition(rs, planItems(id)))
                .optional();
    }

    public CaseDefinition require(String id) {
        return findById(id).orElseThrow(() -> new NotFoundException("CaseDefinition", id));
    }

    /** Latest version of every deployed key — backs GET /case-definitions. */
    public List<CaseDefinition> listLatest(String tenantId) {
        return jdbc.sql("""
                SELECT ID_ FROM CM_CASE_DEF d
                WHERE VERSION_NO_ = (SELECT MAX(VERSION_NO_) FROM CM_CASE_DEF x
                                     WHERE x.KEY_ = d.KEY_
                                       AND (x.TENANT_ID_ = d.TENANT_ID_
                                            OR (x.TENANT_ID_ IS NULL AND d.TENANT_ID_ IS NULL)))
                  AND (:tenant IS NULL OR TENANT_ID_ = :tenant)
                ORDER BY KEY_""")
            .param("tenant", tenantId)
            .query(String.class).list().stream()
            .map(this::findById)
            .flatMap(Optional::stream)
            .toList();
    }

    public Optional<CaseDefinition> findLatest(String key, String tenantId) {
        return jdbc.sql("""
                SELECT ID_ FROM CM_CASE_DEF
                WHERE KEY_ = :key AND (TENANT_ID_ = :tenant OR (:tenant IS NULL AND TENANT_ID_ IS NULL))
                ORDER BY VERSION_NO_ DESC FETCH FIRST 1 ROWS ONLY""")
                .param("key", key).param("tenant", tenantId)
                .query(String.class).optional()
                .flatMap(this::findById);
    }

    public Optional<Map<String, Object>> formSchema(String key, String formKey) {
        return findLatest(key, "t1").or(() -> findLatest(key, null))
                .map(CaseDefinition::forms)
                .map(forms -> forms.get(formKey))
                .filter(Map.class::isInstance)
                .map(o -> (Map<String, Object>) o);
    }

    private List<PlanItemDefinition> planItems(String caseDefId) {
        return jdbc.sql("""
                SELECT ID_, CASE_DEF_ID_, DEF_KEY_, TYPE_, NAME_, PARENT_STAGE_KEY_, MANUAL_ACT_,
                       REQUIRED_, REPETITION_, ENTRY_CRIT_JSON_, EXIT_CRIT_JSON_, FORM_KEY_,
                       PROC_DEF_KEY_, CAND_GROUPS_JSON_, SORT_ORDER_
                FROM CM_PLAN_ITEM_DEF WHERE CASE_DEF_ID_ = :id ORDER BY SORT_ORDER_""")
                .param("id", caseDefId)
                .query((rs, n) -> new PlanItemDefinition(
                        rs.getString("ID_"), rs.getString("CASE_DEF_ID_"), rs.getString("DEF_KEY_"),
                        PlanItemType.valueOf(rs.getString("TYPE_")), rs.getString("NAME_"),
                        rs.getString("PARENT_STAGE_KEY_"),
                        rs.getInt("MANUAL_ACT_") == 1, rs.getInt("REQUIRED_") == 1,
                        rs.getInt("REPETITION_") == 1,
                        JsonCodec.toList(rs.getString("ENTRY_CRIT_JSON_")),
                        JsonCodec.toList(rs.getString("EXIT_CRIT_JSON_")),
                        rs.getString("FORM_KEY_"), rs.getString("PROC_DEF_KEY_"),
                        JsonCodec.toList(rs.getString("CAND_GROUPS_JSON_")),
                        rs.getInt("SORT_ORDER_")))
                .list();
    }

    private static CaseDefinition mapDefinition(java.sql.ResultSet rs, List<PlanItemDefinition> items)
            throws java.sql.SQLException {
        return new CaseDefinition(
                rs.getString("ID_"), rs.getString("KEY_"), rs.getInt("VERSION_NO_"),
                rs.getString("NAME_"), rs.getString("TENANT_ID_"), rs.getString("DESCRIPTION_"),
                rs.getString("SLA_POLICY_ID_"),
                JsonCodec.toList(rs.getString("ROLES_JSON_")),
                JsonCodec.toList(rs.getString("ATTACH_CATS_JSON_")),
                JsonCodec.toMap(rs.getString("FORMS_JSON_")),
                items,
                rs.getObject("DEPLOYED_AT_", OffsetDateTime.class),
                rs.getString("DEPLOYED_BY_"));
    }
}
```

- [ ] **Step 5: Write the deployment service**

```java
package org.casemgmt.service;

import org.casemgmt.domain.*;
import org.casemgmt.repo.CaseDefinitionRepository;
import org.casemgmt.repo.JsonCodec;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class CaseDefinitionService {

    private final CaseDefinitionRepository repo;

    public CaseDefinitionService(CaseDefinitionRepository repo) {
        this.repo = repo;
    }

    /** Parses the definition JSON, assigns the next version for its key and stores it. */
    public CaseDefinition deploy(String json, String deployedBy) {
        Map<String, Object> doc = JsonCodec.toMap(json);
        String key = required(doc, "key");
        String tenantId = (String) doc.get("tenantId");
        int version = repo.nextVersion(key, tenantId);
        String id = key + ":" + version;

        List<PlanItemDefinition> items = new ArrayList<>();
        List<Map<String, Object>> raw = (List<Map<String, Object>>) doc.getOrDefault("planItems", List.of());
        for (Map<String, Object> p : raw) {
            items.add(new PlanItemDefinition(
                    CaseIds.newId(), id, required(p, "defKey"),
                    PlanItemType.valueOf(required(p, "type")),
                    (String) p.getOrDefault("name", p.get("defKey")),
                    (String) p.get("parentStageKey"),
                    bool(p, "manualActivation"), bool(p, "required"), bool(p, "repetition"),
                    strings(p, "entryCriteria"), strings(p, "exitCriteria"),
                    (String) p.get("formKey"), (String) p.get("processDefinitionKey"),
                    strings(p, "candidateGroups"),
                    p.get("sortOrder") instanceof Number n ? n.intValue() : 0));
        }

        CaseDefinition def = new CaseDefinition(id, key, version,
                (String) doc.getOrDefault("name", key), tenantId,
                (String) doc.get("description"), (String) doc.get("slaPolicyId"),
                strings(doc, "roles"), strings(doc, "attachmentCategories"),
                (Map<String, Object>) doc.getOrDefault("forms", Map.of()),
                items, OffsetDateTime.now(), deployedBy);

        repo.insert(def);
        return def;
    }

    private static String required(Map<String, Object> m, String field) {
        Object v = m.get(field);
        if (v == null) throw new IllegalArgumentException("Definition is missing required field: " + field);
        return v.toString();
    }

    private static boolean bool(Map<String, Object> m, String field) {
        return m.get(field) instanceof Boolean b && b;
    }

    private static List<String> strings(Map<String, Object> m, String field) {
        Object v = m.get(field);
        return v instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of();
    }
}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `./mvnw -q -pl case-management-core test -Dtest=CaseDefinitionServiceTest`
Expected: PASS, all five tests.

- [ ] **Step 7: Commit**

```bash
git add case-management-core/src
git commit -m "feat(core): case definition deployment with versioning and form schemas"
```

---

### Task 6: Runtime repositories

**Files:**
- Create: `case-management-core/src/main/java/org/casemgmt/repo/PlanItemRepository.java`
- Create: `case-management-core/src/main/java/org/casemgmt/repo/CaseTaskRepository.java`
- Create: `case-management-core/src/main/java/org/casemgmt/repo/MilestoneRepository.java`
- Create: `case-management-core/src/main/java/org/casemgmt/repo/CommentRepository.java`
- Create: `case-management-core/src/main/java/org/casemgmt/repo/ParticipantRepository.java`
- Create: `case-management-core/src/main/java/org/casemgmt/repo/LinkedProcessRepository.java`
- Create: `case-management-core/src/test/java/org/casemgmt/repo/RuntimeRepositoriesTest.java`

**Interfaces:**
- Consumes: `PlanItem`, `CaseTask`, domain enums (Task 3); `OptimisticLockException` (Task 4)
- Produces:
  - `PlanItemRepository.insert(PlanItem)`, `.findByCase(String caseId) : List<PlanItem>`, `.require(String id) : PlanItem`, `.updateState(PlanItem item, long expectedVersion) : PlanItem`, `.bindEngineTask(String planItemId, String engineTaskId)`, `.bindProcessInstance(String planItemId, String procInstId)`
  - `CaseTaskRepository.insert(CaseTask)`, `.require(String id)`, `.findByCase(String caseId) : List<CaseTask>`, `.findByEngineTaskId(String) : Optional<CaseTask>`, `.update(CaseTask, long expectedVersion) : CaseTask`, `.worklist(String assignee, List<String> groups, int limit) : List<CaseTask>`, `.markSync(String taskId, CaseTask.EngineSync sync, String engineTaskId)`
  - `MilestoneRepository.insert(String id, String caseId, String planItemId, String name)`, `.achieve(String milestoneId, String actor)`, `.findByCase(String caseId) : List<MilestoneRow>` where `MilestoneRow(String id, String caseId, String planItemId, String name, boolean achieved, OffsetDateTime achievedAt, String achievedBy)`
  - `CommentRepository.insert(String id, String caseId, String author, String text, String visibility)`, `.findByCase(String caseId, String visibilityFilter) : List<CommentRow>` where `CommentRow(String id, String caseId, String author, String text, String visibility, OffsetDateTime createdAt)`
  - `ParticipantRepository.insert(String id, String caseId, String userId, String groupId, String role)`, `.rolesOf(String caseId, String userId, List<String> groups) : Set<String>`
  - `LinkedProcessRepository.insert(String id, String caseId, String planItemId, String procInstId, String procDefKey)`, `.findByCase(String caseId) : List<LinkedProcessRow>` where `LinkedProcessRow(String id, String caseId, String planItemId, String processInstanceId, String processDefinitionKey, String state)`

- [ ] **Step 1: Write the failing test**

`case-management-core/src/test/java/org/casemgmt/repo/RuntimeRepositoriesTest.java`:

```java
package org.casemgmt.repo;

import org.casemgmt.OracleTestBase;
import org.casemgmt.domain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class RuntimeRepositoriesTest extends OracleTestBase {

    private PlanItemRepository planItems;
    private CaseTaskRepository tasks;
    private ParticipantRepository participants;
    private CommentRepository comments;

    @BeforeEach
    void setUp() {
        jdbc().sql("DELETE FROM CM_COMMENT").update();
        jdbc().sql("DELETE FROM CM_PARTICIPANT").update();
        jdbc().sql("DELETE FROM CM_TASK").update();
        jdbc().sql("DELETE FROM CM_PLAN_ITEM").update();
        jdbc().sql("DELETE FROM CM_CASE").update();
        jdbc().sql("DELETE FROM CM_CASE_DEF").update();
        jdbc().sql("INSERT INTO CM_CASE_DEF (ID_, KEY_, VERSION_NO_, NAME_) VALUES ('d:1','d',1,'D')").update();

        new CaseRepository(jdbc()).insert(new CaseInstance("eng-a:1", "eng-a", "t1", "d:1", "d", 1,
                null, "T", CaseState.ACTIVE, CasePriority.MEDIUM, null, null, "alice", "NONE",
                null, null, Map.of(), 0L, OffsetDateTime.now(), OffsetDateTime.now(), null));

        planItems = new PlanItemRepository(jdbc());
        tasks = new CaseTaskRepository(jdbc());
        participants = new ParticipantRepository(jdbc());
        comments = new CommentRepository(jdbc());
    }

    private PlanItem item(String id, PlanItemState state) {
        return new PlanItem(id, "eng-a:1", "pd-1", PlanItemType.HUMAN_TASK, "Review", state,
                null, false, 1, null, null, null, 0L,
                OffsetDateTime.now(), OffsetDateTime.now(), null);
    }

    @Test
    void planItemsRoundTripAndUpdateStateOptimistically() {
        planItems.insert(item("pi-1", PlanItemState.AVAILABLE));

        PlanItem loaded = planItems.require("pi-1");
        PlanItem updated = planItems.updateState(loaded.withState(PlanItemState.ACTIVE), loaded.version());

        assertThat(updated.state()).isEqualTo(PlanItemState.ACTIVE);
        assertThat(updated.version()).isEqualTo(1L);
        assertThatThrownBy(() -> planItems.updateState(loaded.withState(PlanItemState.COMPLETED), 0L))
                .isInstanceOf(org.casemgmt.error.OptimisticLockException.class);
    }

    @Test
    void tasksAreFoundByEngineTaskIdAndByWorklist() {
        planItems.insert(item("pi-2", PlanItemState.ACTIVE));
        tasks.insert(new CaseTask("t-1", "eng-a:1", "pi-2", "engine-task-9", "Review", null,
                TaskState.OPEN, null, null, List.of("reviewers"), "reviewForm", 50, null, null,
                CaseTask.EngineSync.SYNCED, 0L, OffsetDateTime.now(), OffsetDateTime.now(), null));

        assertThat(tasks.findByEngineTaskId("engine-task-9")).isPresent();
        assertThat(tasks.worklist(null, List.of("reviewers"), 20))
                .extracting(CaseTask::id).containsExactly("t-1");
    }

    @Test
    void worklistExcludesTasksNotYetSyncedToTheEngine() {
        planItems.insert(item("pi-3", PlanItemState.ACTIVE));
        tasks.insert(new CaseTask("t-2", "eng-a:1", "pi-3", null, "Pending", null,
                TaskState.OPEN, null, null, List.of("reviewers"), null, 50, null, null,
                CaseTask.EngineSync.PENDING, 0L, OffsetDateTime.now(), OffsetDateTime.now(), null));

        assertThat(tasks.worklist(null, List.of("reviewers"), 20)).isEmpty();

        tasks.markSync("t-2", CaseTask.EngineSync.SYNCED, "engine-task-10");

        assertThat(tasks.worklist(null, List.of("reviewers"), 20))
                .extracting(CaseTask::id).containsExactly("t-2");
    }

    @Test
    void participantRolesAreResolvedForUserAndGroups() {
        participants.insert("p-1", "eng-a:1", "alice", null, "owner");
        participants.insert("p-2", "eng-a:1", null, "reviewers", "reviewer");

        assertThat(participants.rolesOf("eng-a:1", "alice", List.of())).containsExactly("owner");
        assertThat(participants.rolesOf("eng-a:1", "bob", List.of("reviewers"))).containsExactly("reviewer");
        assertThat(participants.rolesOf("eng-a:1", "carol", List.of())).isEmpty();
    }

    @Test
    void commentsFilterByVisibility() {
        comments.insert("c-1", "eng-a:1", "alice", "internal note", "internal");
        comments.insert("c-2", "eng-a:1", "alice", "dear customer", "external");

        assertThat(comments.findByCase("eng-a:1", "external")).hasSize(1);
        assertThat(comments.findByCase("eng-a:1", null)).hasSize(2);
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./mvnw -q -pl case-management-core test -Dtest=RuntimeRepositoriesTest`
Expected: FAIL — `cannot find symbol: class PlanItemRepository`.

- [ ] **Step 3: Write `PlanItemRepository`**

```java
package org.casemgmt.repo;

import org.casemgmt.domain.*;
import org.casemgmt.error.NotFoundException;
import org.casemgmt.error.OptimisticLockException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public class PlanItemRepository {

    private static final String COLUMNS = """
            ID_, CASE_ID_, PI_DEF_ID_, TYPE_, NAME_, STATE_, PARENT_STAGE_ID_, AD_HOC_,
            REPETITION_NO_, CAMUNDA_TASK_ID_, PROC_INST_ID_, TERM_REASON_, VERSION_,
            CREATED_AT_, UPDATED_AT_, ENDED_AT_""";

    private final JdbcClient jdbc;

    public PlanItemRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(PlanItem p) {
        jdbc.sql("""
                INSERT INTO CM_PLAN_ITEM (ID_, CASE_ID_, PI_DEF_ID_, TYPE_, NAME_, STATE_,
                    PARENT_STAGE_ID_, AD_HOC_, REPETITION_NO_, CAMUNDA_TASK_ID_, PROC_INST_ID_,
                    TERM_REASON_, VERSION_, CREATED_AT_, UPDATED_AT_, ENDED_AT_)
                VALUES (:id, :caseId, :defId, :type, :name, :state, :parent, :adHoc, :rep,
                    :taskId, :procId, :reason, :version, :createdAt, :updatedAt, :endedAt)""")
            .param("id", p.id()).param("caseId", p.caseId()).param("defId", p.planItemDefId())
            .param("type", p.type().name()).param("name", p.name()).param("state", p.state().name())
            .param("parent", p.parentStageId()).param("adHoc", p.adHoc() ? 1 : 0)
            .param("rep", p.repetitionNo()).param("taskId", p.engineTaskId())
            .param("procId", p.processInstanceId()).param("reason", p.terminationReason())
            .param("version", p.version()).param("createdAt", p.createdAt())
            .param("updatedAt", p.updatedAt()).param("endedAt", p.endedAt())
            .update();
    }

    public Optional<PlanItem> findById(String id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM CM_PLAN_ITEM WHERE ID_ = :id")
                .param("id", id).query(PlanItemRepository::map).optional();
    }

    public PlanItem require(String id) {
        return findById(id).orElseThrow(() -> new NotFoundException("PlanItem", id));
    }

    public List<PlanItem> findByCase(String caseId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM CM_PLAN_ITEM WHERE CASE_ID_ = :caseId ORDER BY CREATED_AT_")
                .param("caseId", caseId).query(PlanItemRepository::map).list();
    }

    public PlanItem updateState(PlanItem p, long expectedVersion) {
        int rows = jdbc.sql("""
                UPDATE CM_PLAN_ITEM SET STATE_ = :state, TERM_REASON_ = :reason,
                    ENDED_AT_ = CASE WHEN :state IN ('COMPLETED','TERMINATED') THEN SYSTIMESTAMP ELSE ENDED_AT_ END,
                    UPDATED_AT_ = SYSTIMESTAMP, VERSION_ = VERSION_ + 1
                WHERE ID_ = :id AND VERSION_ = :expected""")
            .param("state", p.state().name()).param("reason", p.terminationReason())
            .param("id", p.id()).param("expected", expectedVersion)
            .update();
        if (rows == 0) throw new OptimisticLockException("PlanItem", p.id(), expectedVersion);
        return require(p.id());
    }

    public void bindEngineTask(String planItemId, String engineTaskId) {
        jdbc.sql("UPDATE CM_PLAN_ITEM SET CAMUNDA_TASK_ID_ = :taskId, UPDATED_AT_ = SYSTIMESTAMP WHERE ID_ = :id")
            .param("taskId", engineTaskId).param("id", planItemId).update();
    }

    public void bindProcessInstance(String planItemId, String procInstId) {
        jdbc.sql("UPDATE CM_PLAN_ITEM SET PROC_INST_ID_ = :procId, UPDATED_AT_ = SYSTIMESTAMP WHERE ID_ = :id")
            .param("procId", procInstId).param("id", planItemId).update();
    }

    private static PlanItem map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new PlanItem(rs.getString("ID_"), rs.getString("CASE_ID_"), rs.getString("PI_DEF_ID_"),
                PlanItemType.valueOf(rs.getString("TYPE_")), rs.getString("NAME_"),
                PlanItemState.valueOf(rs.getString("STATE_")), rs.getString("PARENT_STAGE_ID_"),
                rs.getInt("AD_HOC_") == 1, rs.getInt("REPETITION_NO_"),
                rs.getString("CAMUNDA_TASK_ID_"), rs.getString("PROC_INST_ID_"),
                rs.getString("TERM_REASON_"), rs.getLong("VERSION_"),
                rs.getObject("CREATED_AT_", OffsetDateTime.class),
                rs.getObject("UPDATED_AT_", OffsetDateTime.class),
                rs.getObject("ENDED_AT_", OffsetDateTime.class));
    }
}
```

- [ ] **Step 4: Write `CaseTaskRepository`**

```java
package org.casemgmt.repo;

import org.casemgmt.domain.CaseTask;
import org.casemgmt.domain.TaskState;
import org.casemgmt.error.NotFoundException;
import org.casemgmt.error.OptimisticLockException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public class CaseTaskRepository {

    private static final String COLUMNS = """
            ID_, CASE_ID_, PLAN_ITEM_ID_, CAMUNDA_TASK_ID_, NAME_, DESCRIPTION_, STATE_,
            ASSIGNEE_, DELEGATED_BY_, CAND_GROUPS_JSON_, FORM_KEY_, PRIORITY_, DUE_AT_,
            OUTCOME_, ENGINE_SYNC_, VERSION_, CREATED_AT_, UPDATED_AT_, COMPLETED_AT_""";

    private final JdbcClient jdbc;

    public CaseTaskRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(CaseTask t) {
        jdbc.sql("""
                INSERT INTO CM_TASK (ID_, CASE_ID_, PLAN_ITEM_ID_, CAMUNDA_TASK_ID_, NAME_,
                    DESCRIPTION_, STATE_, ASSIGNEE_, DELEGATED_BY_, CAND_GROUPS_JSON_, FORM_KEY_,
                    PRIORITY_, DUE_AT_, OUTCOME_, ENGINE_SYNC_, VERSION_, CREATED_AT_, UPDATED_AT_,
                    COMPLETED_AT_)
                VALUES (:id, :caseId, :planItemId, :engineTaskId, :name, :description, :state,
                    :assignee, :delegatedBy, :groups, :formKey, :priority, :dueAt, :outcome,
                    :sync, :version, :createdAt, :updatedAt, :completedAt)""")
            .param("id", t.id()).param("caseId", t.caseId()).param("planItemId", t.planItemId())
            .param("engineTaskId", t.engineTaskId()).param("name", t.name())
            .param("description", t.description()).param("state", t.state().name())
            .param("assignee", t.assignee()).param("delegatedBy", t.delegatedBy())
            .param("groups", JsonCodec.toJson(t.candidateGroups())).param("formKey", t.formKey())
            .param("priority", t.priority()).param("dueAt", t.dueAt()).param("outcome", t.outcome())
            .param("sync", t.engineSync().name()).param("version", t.version())
            .param("createdAt", t.createdAt()).param("updatedAt", t.updatedAt())
            .param("completedAt", t.completedAt())
            .update();
    }

    public Optional<CaseTask> findById(String id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM CM_TASK WHERE ID_ = :id")
                .param("id", id).query(CaseTaskRepository::map).optional();
    }

    public CaseTask require(String id) {
        return findById(id).orElseThrow(() -> new NotFoundException("Task", id));
    }

    public Optional<CaseTask> findByEngineTaskId(String engineTaskId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM CM_TASK WHERE CAMUNDA_TASK_ID_ = :tid")
                .param("tid", engineTaskId).query(CaseTaskRepository::map).optional();
    }

    public List<CaseTask> findByCase(String caseId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM CM_TASK WHERE CASE_ID_ = :caseId ORDER BY CREATED_AT_")
                .param("caseId", caseId).query(CaseTaskRepository::map).list();
    }

    /**
     * Worklist. Tasks whose engine counterpart is not yet created are invisible:
     * claiming them would fail against the engine (spec §3.5).
     */
    public List<CaseTask> worklist(String assignee, List<String> groups, int limit) {
        return jdbc.sql("""
                SELECT """ + COLUMNS + """
                 FROM CM_TASK
                WHERE STATE_ IN ('OPEN','CLAIMED')
                  AND ENGINE_SYNC_ = 'SYNCED'
                  AND (:assignee IS NULL OR ASSIGNEE_ = :assignee)
                  AND (:groupsJson IS NULL OR EXISTS (
                        SELECT 1 FROM JSON_TABLE(CAND_GROUPS_JSON_, '$[*]' COLUMNS (g VARCHAR2(255) PATH '$')) jt
                        WHERE jt.g MEMBER OF (SELECT * FROM JSON_TABLE(:groupsJson, '$[*]'
                                              COLUMNS (g VARCHAR2(255) PATH '$')))))
                ORDER BY CREATED_AT_ FETCH FIRST :limit ROWS ONLY""")
            .param("assignee", assignee)
            .param("groupsJson", groups == null || groups.isEmpty() ? null : JsonCodec.toJson(groups))
            .param("limit", limit)
            .query(CaseTaskRepository::map)
            .list();
    }

    public CaseTask update(CaseTask t, long expectedVersion) {
        int rows = jdbc.sql("""
                UPDATE CM_TASK SET STATE_ = :state, ASSIGNEE_ = :assignee, DELEGATED_BY_ = :delegatedBy,
                    OUTCOME_ = :outcome, DUE_AT_ = :dueAt,
                    COMPLETED_AT_ = CASE WHEN :state = 'COMPLETED' THEN SYSTIMESTAMP ELSE COMPLETED_AT_ END,
                    UPDATED_AT_ = SYSTIMESTAMP, VERSION_ = VERSION_ + 1
                WHERE ID_ = :id AND VERSION_ = :expected""")
            .param("state", t.state().name()).param("assignee", t.assignee())
            .param("delegatedBy", t.delegatedBy()).param("outcome", t.outcome())
            .param("dueAt", t.dueAt()).param("id", t.id()).param("expected", expectedVersion)
            .update();
        if (rows == 0) throw new OptimisticLockException("Task", t.id(), expectedVersion);
        return require(t.id());
    }

    public void markSync(String taskId, CaseTask.EngineSync sync, String engineTaskId) {
        jdbc.sql("""
                UPDATE CM_TASK SET ENGINE_SYNC_ = :sync,
                    CAMUNDA_TASK_ID_ = COALESCE(:engineTaskId, CAMUNDA_TASK_ID_),
                    UPDATED_AT_ = SYSTIMESTAMP
                WHERE ID_ = :id""")
            .param("sync", sync.name()).param("engineTaskId", engineTaskId).param("id", taskId)
            .update();
    }

    private static CaseTask map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new CaseTask(rs.getString("ID_"), rs.getString("CASE_ID_"), rs.getString("PLAN_ITEM_ID_"),
                rs.getString("CAMUNDA_TASK_ID_"), rs.getString("NAME_"), rs.getString("DESCRIPTION_"),
                TaskState.valueOf(rs.getString("STATE_")), rs.getString("ASSIGNEE_"),
                rs.getString("DELEGATED_BY_"), JsonCodec.toList(rs.getString("CAND_GROUPS_JSON_")),
                rs.getString("FORM_KEY_"), rs.getInt("PRIORITY_"),
                rs.getObject("DUE_AT_", OffsetDateTime.class), rs.getString("OUTCOME_"),
                CaseTask.EngineSync.valueOf(rs.getString("ENGINE_SYNC_")),
                rs.getLong("VERSION_"),
                rs.getObject("CREATED_AT_", OffsetDateTime.class),
                rs.getObject("UPDATED_AT_", OffsetDateTime.class),
                rs.getObject("COMPLETED_AT_", OffsetDateTime.class));
    }
}
```

If the `JSON_TABLE … MEMBER OF` predicate in `worklist` does not compile on Oracle 23ai, replace the group filter with an `IN` list built in Java from `groups` — the JSON approach is a nicety, not a requirement. Record which one you used.

- [ ] **Step 5: Write the four small repositories**

```java
package org.casemgmt.repo;

import org.springframework.jdbc.core.simple.JdbcClient;
import java.time.OffsetDateTime;
import java.util.List;

public class MilestoneRepository {

    public record MilestoneRow(String id, String caseId, String planItemId, String name,
                               boolean achieved, OffsetDateTime achievedAt, String achievedBy) {}

    private final JdbcClient jdbc;

    public MilestoneRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public void insert(String id, String caseId, String planItemId, String name) {
        jdbc.sql("""
                INSERT INTO CM_MILESTONE (ID_, CASE_ID_, PLAN_ITEM_ID_, NAME_, ACHIEVED_)
                VALUES (:id, :caseId, :planItemId, :name, 0)""")
            .param("id", id).param("caseId", caseId).param("planItemId", planItemId)
            .param("name", name).update();
    }

    public void achieve(String milestoneId, String actor) {
        jdbc.sql("""
                UPDATE CM_MILESTONE SET ACHIEVED_ = 1, ACHIEVED_AT_ = SYSTIMESTAMP, ACHIEVED_BY_ = :actor
                WHERE ID_ = :id AND ACHIEVED_ = 0""")
            .param("actor", actor).param("id", milestoneId).update();
    }

    public List<MilestoneRow> findByCase(String caseId) {
        return jdbc.sql("""
                SELECT ID_, CASE_ID_, PLAN_ITEM_ID_, NAME_, ACHIEVED_, ACHIEVED_AT_, ACHIEVED_BY_
                FROM CM_MILESTONE WHERE CASE_ID_ = :caseId""")
            .param("caseId", caseId)
            .query((rs, n) -> new MilestoneRow(rs.getString("ID_"), rs.getString("CASE_ID_"),
                    rs.getString("PLAN_ITEM_ID_"), rs.getString("NAME_"),
                    rs.getInt("ACHIEVED_") == 1,
                    rs.getObject("ACHIEVED_AT_", OffsetDateTime.class),
                    rs.getString("ACHIEVED_BY_")))
            .list();
    }

    public java.util.Optional<MilestoneRow> findByPlanItem(String planItemId) {
        return jdbc.sql("""
                SELECT ID_, CASE_ID_, PLAN_ITEM_ID_, NAME_, ACHIEVED_, ACHIEVED_AT_, ACHIEVED_BY_
                FROM CM_MILESTONE WHERE PLAN_ITEM_ID_ = :id""")
            .param("id", planItemId)
            .query((rs, n) -> new MilestoneRow(rs.getString("ID_"), rs.getString("CASE_ID_"),
                    rs.getString("PLAN_ITEM_ID_"), rs.getString("NAME_"),
                    rs.getInt("ACHIEVED_") == 1,
                    rs.getObject("ACHIEVED_AT_", OffsetDateTime.class),
                    rs.getString("ACHIEVED_BY_")))
            .optional();
    }
}
```

```java
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
```

```java
package org.casemgmt.repo;

import org.springframework.jdbc.core.simple.JdbcClient;
import java.util.List;
import java.util.Set;
import java.util.LinkedHashSet;

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
```

```java
package org.casemgmt.repo;

import org.springframework.jdbc.core.simple.JdbcClient;
import java.util.List;

public class LinkedProcessRepository {

    public record LinkedProcessRow(String id, String caseId, String planItemId,
                                   String processInstanceId, String processDefinitionKey, String state) {}

    private final JdbcClient jdbc;

    public LinkedProcessRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public void insert(String id, String caseId, String planItemId, String procInstId, String procDefKey) {
        jdbc.sql("""
                INSERT INTO CM_LINKED_PROCESS (ID_, CASE_ID_, PLAN_ITEM_ID_, PROC_INST_ID_,
                    PROC_DEF_KEY_, STATE_)
                VALUES (:id, :caseId, :planItemId, :procInstId, :procDefKey, 'ACTIVE')""")
            .param("id", id).param("caseId", caseId).param("planItemId", planItemId)
            .param("procInstId", procInstId).param("procDefKey", procDefKey).update();
    }

    public void markState(String procInstId, String state) {
        jdbc.sql("""
                UPDATE CM_LINKED_PROCESS SET STATE_ = :state,
                    ENDED_AT_ = CASE WHEN :state IN ('COMPLETED','TERMINATED') THEN SYSTIMESTAMP ELSE ENDED_AT_ END
                WHERE PROC_INST_ID_ = :procInstId""")
            .param("state", state).param("procInstId", procInstId).update();
    }

    public List<LinkedProcessRow> findByCase(String caseId) {
        return jdbc.sql("""
                SELECT ID_, CASE_ID_, PLAN_ITEM_ID_, PROC_INST_ID_, PROC_DEF_KEY_, STATE_
                FROM CM_LINKED_PROCESS WHERE CASE_ID_ = :caseId ORDER BY STARTED_AT_""")
            .param("caseId", caseId)
            .query((rs, n) -> new LinkedProcessRow(rs.getString("ID_"), rs.getString("CASE_ID_"),
                    rs.getString("PLAN_ITEM_ID_"), rs.getString("PROC_INST_ID_"),
                    rs.getString("PROC_DEF_KEY_"), rs.getString("STATE_")))
            .list();
    }
}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `./mvnw -q -pl case-management-core test -Dtest=RuntimeRepositoriesTest`
Expected: PASS, all five tests.

- [ ] **Step 7: Commit**

```bash
git add case-management-core/src
git commit -m "feat(core): plan item, task, milestone, comment, participant and process repositories"
```

---

## Phase 2 — The plan-item state machine (Risk R1)

### Task 7: Sandboxed JUEL criterion evaluator

**Files:**
- Create: `case-management-core/src/main/java/org/casemgmt/rules/EvaluationContext.java`
- Create: `case-management-core/src/main/java/org/casemgmt/rules/CriterionEvaluator.java`
- Create: `case-management-core/src/main/java/org/casemgmt/rules/JuelCriterionEvaluator.java`
- Create: `case-management-core/src/test/java/org/casemgmt/rules/JuelCriterionEvaluatorTest.java`

**Interfaces:**
- Consumes: nothing beyond the JDK and `operaton-juel`
- Produces:
  - `EvaluationContext(Map<String,Object> caseAttributes, Map<String,Object> variables, Map<String,Map<String,Object>> items)`
  - `CriterionEvaluator.matches(String expression, EvaluationContext ctx) : boolean`
  - `CriterionEvaluator.allMatch(List<String> expressions, EvaluationContext ctx) : boolean` — vacuously true for an empty list
  - `JuelCriterionEvaluator implements CriterionEvaluator`

**Why this shape:** the context is nested `Map`s, and the resolver chain contains **only** `MapELResolver` and `ListELResolver`. JUEL's own `SimpleResolver` includes a `BeanELResolver`, which would let a deployed definition call arbitrary getters and reach `getClass().getClassLoader()`. Definitions arrive over the API from other teams, so that is a remote-code-execution path, not a theoretical concern.

- [ ] **Step 1: Write the failing test**

`case-management-core/src/test/java/org/casemgmt/rules/JuelCriterionEvaluatorTest.java`:

```java
package org.casemgmt.rules;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class JuelCriterionEvaluatorTest {

    private final CriterionEvaluator evaluator = new JuelCriterionEvaluator();

    private EvaluationContext context() {
        return new EvaluationContext(
                Map.of("state", "ACTIVE", "priority", "HIGH"),
                Map.of("amount", 1500, "channel", "web", "escalated", false),
                Map.of("assess", Map.of("state", "COMPLETED"),
                       "investigate", Map.of("state", "AVAILABLE")));
    }

    @Test
    void readsSiblingPlanItemState() {
        assertThat(evaluator.matches("${items.assess.state == 'COMPLETED'}", context())).isTrue();
        assertThat(evaluator.matches("${items.investigate.state == 'COMPLETED'}", context())).isFalse();
    }

    @Test
    void readsCaseVariablesAndAttributes() {
        assertThat(evaluator.matches("${vars.amount > 1000}", context())).isTrue();
        assertThat(evaluator.matches("${case.priority == 'HIGH'}", context())).isTrue();
        assertThat(evaluator.matches("${vars.escalated}", context())).isFalse();
    }

    @Test
    void combinesConditions() {
        assertThat(evaluator.matches(
                "${items.assess.state == 'COMPLETED' && vars.amount > 1000}", context())).isTrue();
    }

    @Test
    void emptyCriteriaListMeansNoGate() {
        assertThat(evaluator.allMatch(List.of(), context())).isTrue();
    }

    @Test
    void allMatchRequiresEveryExpression() {
        assertThat(evaluator.allMatch(
                List.of("${vars.amount > 1000}", "${case.state == 'ACTIVE'}"), context())).isTrue();
        assertThat(evaluator.allMatch(
                List.of("${vars.amount > 1000}", "${case.state == 'CLOSED'}"), context())).isFalse();
    }

    @Test
    void unknownVariablesAreNullRatherThanExplosive() {
        assertThat(evaluator.matches("${vars.doesNotExist == null}", context())).isTrue();
    }

    @Test
    void nonBooleanResultIsRejected() {
        assertThatThrownBy(() -> evaluator.matches("${vars.amount}", context()))
                .isInstanceOf(CriterionEvaluationException.class)
                .hasMessageContaining("must evaluate to a boolean");
    }

    @Test
    void malformedExpressionIsRejectedWithTheExpressionInTheMessage() {
        assertThatThrownBy(() -> evaluator.matches("${items.assess.state ==}", context()))
                .isInstanceOf(CriterionEvaluationException.class)
                .hasMessageContaining("items.assess.state ==");
    }

    // ---- sandbox ----

    @Test
    void cannotReachJavaTypesThroughAValue() {
        // The base here is an Integer, not a Map or List, so NO resolver in the
        // sandboxed chain handles the property lookup and it throws.
        //
        // Do NOT "simplify" this to ${case.class.name}: `case` is a Map, so
        // MapELResolver claims the lookup and returns null for the missing key.
        // That form evaluates to false with OR without a BeanELResolver present,
        // so it would assert nothing about the sandbox. Verified empirically.
        assertThatThrownBy(() -> evaluator.matches("${vars.amount.class.name == 'x'}", context()))
                .isInstanceOf(CriterionEvaluationException.class);
    }

    @Test
    void cannotInvokeMethodsOnValues() {
        assertThatThrownBy(() -> evaluator.matches("${vars.channel.getClass() != null}", context()))
                .isInstanceOf(CriterionEvaluationException.class);
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./mvnw -q -pl case-management-core test -Dtest=JuelCriterionEvaluatorTest`
Expected: FAIL — `package org.casemgmt.rules does not exist`.

- [ ] **Step 3: Write the context, the interface and the exception**

```java
package org.casemgmt.rules;

import java.util.Map;

/**
 * Everything a criterion may read. Nested maps only — no domain objects, because
 * exposing objects would require a BeanELResolver and with it method access.
 */
public record EvaluationContext(
        Map<String, Object> caseAttributes,
        Map<String, Object> variables,
        Map<String, Map<String, Object>> items) {}
```

```java
package org.casemgmt.rules;

import java.util.List;

public interface CriterionEvaluator {

    boolean matches(String expression, EvaluationContext context);

    default boolean allMatch(List<String> expressions, EvaluationContext context) {
        if (expressions == null || expressions.isEmpty()) {
            return true;   // no entry criteria means the item is not gated
        }
        return expressions.stream().allMatch(e -> matches(e, context));
    }
}
```

```java
package org.casemgmt.rules;

public class CriterionEvaluationException extends RuntimeException {
    public CriterionEvaluationException(String expression, String problem, Throwable cause) {
        super("Criterion [" + expression + "] " + problem, cause);
    }
}
```

- [ ] **Step 4: Write the sandboxed evaluator**

```java
package org.casemgmt.rules;

import jakarta.el.*;
import org.operaton.bpm.impl.juel.ExpressionFactoryImpl;
import org.operaton.bpm.impl.juel.SimpleContext;

import java.util.Map;

/**
 * JUEL with a deliberately minimal resolver chain.
 *
 * Only MapELResolver and ListELResolver are registered. There is NO BeanELResolver,
 * so expressions cannot call methods or walk into Java types — `${x.getClass()}`
 * fails to resolve instead of escaping the sandbox. Case definitions are deployed
 * over the API by other teams; without this, POST /case-definitions would be an
 * arbitrary-code-execution endpoint.
 */
public class JuelCriterionEvaluator implements CriterionEvaluator {

    private final ExpressionFactory factory = new ExpressionFactoryImpl();

    @Override
    public boolean matches(String expression, EvaluationContext context) {
        ELContext elContext = elContext(context);
        Object value;
        try {
            ValueExpression ve = factory.createValueExpression(elContext, expression, Object.class);
            value = ve.getValue(elContext);
        } catch (ELException e) {
            throw new CriterionEvaluationException(expression, "could not be evaluated", e);
        }
        if (value == null) {
            throw new CriterionEvaluationException(expression, "must evaluate to a boolean but was null", null);
        }
        if (!(value instanceof Boolean b)) {
            throw new CriterionEvaluationException(expression,
                    "must evaluate to a boolean but was " + value.getClass().getSimpleName(), null);
        }
        return b;
    }

    private ELContext elContext(EvaluationContext context) {
        CompositeELResolver resolver = new CompositeELResolver();
        resolver.add(new MapELResolver(true));    // read-only
        resolver.add(new ListELResolver(true));   // read-only

        SimpleContext ctx = new SimpleContext(resolver);
        bind(ctx, "case", context.caseAttributes());
        bind(ctx, "vars", context.variables());
        bind(ctx, "items", context.items());
        return ctx;
    }

    private void bind(SimpleContext ctx, String name, Map<String, ?> value) {
        ctx.setVariable(name, factory.createValueExpression(
                value == null ? Map.of() : value, Object.class));
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./mvnw -q -pl case-management-core test -Dtest=JuelCriterionEvaluatorTest`
Expected: PASS, all ten tests.

The two sandbox tests are the load-bearing ones. If either fails — meaning a bean or method call *did* resolve — stop and fix the resolver chain before continuing; do not weaken the test.

- [ ] **Step 6: Commit**

```bash
git add case-management-core/src
git commit -m "feat(core): sandboxed JUEL criterion evaluator with no bean resolver"
```

---

### Task 8: Plan model instantiation and entry criteria

**Files:**
- Create: `case-management-core/src/main/java/org/casemgmt/rules/CaseSnapshot.java`
- Create: `case-management-core/src/main/java/org/casemgmt/rules/Transition.java`
- Create: `case-management-core/src/main/java/org/casemgmt/rules/PlanModelInstantiator.java`
- Create: `case-management-core/src/main/java/org/casemgmt/rules/PlanModelEvaluator.java`
- Create: `case-management-core/src/main/java/org/casemgmt/rules/PlanModelLoopException.java`
- Create: `case-management-core/src/test/java/org/casemgmt/rules/PlanModelFixtures.java`
- Create: `case-management-core/src/test/java/org/casemgmt/rules/PlanModelEvaluatorTest.java`

**Interfaces:**
- Consumes: `CriterionEvaluator`, `EvaluationContext` (Task 7); `CaseDefinition`, `PlanItemDefinition`, `PlanItem`, `CaseInstance` (Task 3)
- Produces:
  - `CaseSnapshot(CaseInstance caseInstance, CaseDefinition definition, List<PlanItem> planItems)` with helper `items(String defKey) : List<PlanItem>` and `definitionOf(PlanItem) : PlanItemDefinition`
  - `Transition(String planItemId, PlanItemState from, PlanItemState to, String reason)`
  - `PlanModelInstantiator.initialItems(String caseId, CaseDefinition def) : List<PlanItem>`
  - `PlanModelEvaluator.evaluate(CaseSnapshot snapshot) : List<Transition>` — **pure**: computes transitions, performs no I/O, applies nothing
  - `PlanModelEvaluator.MAX_ITERATIONS = 20`

**Why pure:** the evaluator is risk R1 and needs hundreds of cheap table-driven tests. Keeping it free of repositories means those tests need no database and run in milliseconds. Applying transitions is a separate concern (Task 15).

- [ ] **Step 1: Write the fixtures**

`case-management-core/src/test/java/org/casemgmt/rules/PlanModelFixtures.java`:

```java
package org.casemgmt.rules;

import org.casemgmt.domain.*;

import java.time.OffsetDateTime;
import java.util.*;

/** Domain-free plan models for evaluator tests. No case type appears here. */
public final class PlanModelFixtures {

    private PlanModelFixtures() {}

    public static PlanItemDefinition def(String key, PlanItemType type) {
        return new PlanItemDefinition("pd-" + key, "d:1", key, type, key, null,
                false, false, false, List.of(), List.of(), null, null, List.of(), 10);
    }

    public static PlanItemDefinition def(String key, PlanItemType type, String parentStageKey,
                                         boolean manualActivation, boolean required, boolean repetition,
                                         List<String> entryCriteria, List<String> exitCriteria,
                                         int sortOrder) {
        return new PlanItemDefinition("pd-" + key, "d:1", key, type, key, parentStageKey,
                manualActivation, required, repetition, entryCriteria, exitCriteria,
                null, null, List.of(), sortOrder);
    }

    public static CaseDefinition definition(PlanItemDefinition... items) {
        return new CaseDefinition("d:1", "d", 1, "D", "t1", null, null,
                List.of(), List.of(), Map.of(), List.of(items), OffsetDateTime.now(), "test");
    }

    public static PlanItem item(String id, String defKey, PlanItemType type, PlanItemState state) {
        return item(id, defKey, type, state, null);
    }

    public static PlanItem item(String id, String defKey, PlanItemType type,
                                PlanItemState state, String parentStageId) {
        return new PlanItem(id, "eng-a:1", "pd-" + defKey, type, defKey, state, parentStageId,
                false, 1, null, null, null, 0L, OffsetDateTime.now(), OffsetDateTime.now(), null);
    }

    public static CaseInstance caseInstance(Map<String, Object> variables) {
        return new CaseInstance("eng-a:1", "eng-a", "t1", "d:1", "d", 1, null, "T",
                CaseState.ACTIVE, CasePriority.MEDIUM, null, null, "alice", "NONE", null, null,
                variables, 0L, OffsetDateTime.now(), OffsetDateTime.now(), null);
    }

    public static CaseSnapshot snapshot(CaseDefinition def, List<PlanItem> items,
                                        Map<String, Object> variables) {
        return new CaseSnapshot(caseInstance(variables), def, items);
    }
}
```

- [ ] **Step 2: Write the failing test**

`case-management-core/src/test/java/org/casemgmt/rules/PlanModelEvaluatorTest.java`:

```java
package org.casemgmt.rules;

import org.casemgmt.domain.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.casemgmt.rules.PlanModelFixtures.*;

class PlanModelEvaluatorTest {

    private final PlanModelEvaluator evaluator = new PlanModelEvaluator(new JuelCriterionEvaluator());

    @Test
    void instantiatesEveryDefinedPlanItemAsAvailable() {
        CaseDefinition def = definition(
                def("stage", PlanItemType.STAGE),
                def("task", PlanItemType.HUMAN_TASK));

        List<PlanItem> items = new PlanModelInstantiator().initialItems("eng-a:1", def);

        assertThat(items).hasSize(2);
        assertThat(items).allMatch(i -> i.state() == PlanItemState.AVAILABLE);
        assertThat(items).allMatch(i -> i.caseId().equals("eng-a:1"));
    }

    @Test
    void ungatedAutoActivatingItemGoesStraightToActive() {
        CaseDefinition def = definition(def("task", PlanItemType.HUMAN_TASK));
        var snapshot = snapshot(def, List.of(item("pi-1", "task", PlanItemType.HUMAN_TASK,
                PlanItemState.AVAILABLE)), Map.of());

        List<Transition> transitions = evaluator.evaluate(snapshot);

        assertThat(transitions).singleElement()
                .satisfies(t -> {
                    assertThat(t.planItemId()).isEqualTo("pi-1");
                    assertThat(t.from()).isEqualTo(PlanItemState.AVAILABLE);
                    assertThat(t.to()).isEqualTo(PlanItemState.ACTIVE);
                });
    }

    @Test
    void manualActivationStopsAtEnabled() {
        CaseDefinition def = definition(
                def("task", PlanItemType.HUMAN_TASK, null, true, false, false, List.of(), List.of(), 10));
        var snapshot = snapshot(def, List.of(item("pi-1", "task", PlanItemType.HUMAN_TASK,
                PlanItemState.AVAILABLE)), Map.of());

        assertThat(evaluator.evaluate(snapshot)).singleElement()
                .extracting(Transition::to).isEqualTo(PlanItemState.ENABLED);
    }

    @Test
    void enabledItemsAreNotStartedAutomatically() {
        CaseDefinition def = definition(
                def("task", PlanItemType.HUMAN_TASK, null, true, false, false, List.of(), List.of(), 10));
        var snapshot = snapshot(def, List.of(item("pi-1", "task", PlanItemType.HUMAN_TASK,
                PlanItemState.ENABLED)), Map.of());

        assertThat(evaluator.evaluate(snapshot)).isEmpty();
    }

    @Test
    void unmetEntryCriterionKeepsTheItemAvailable() {
        CaseDefinition def = definition(
                def("gated", PlanItemType.HUMAN_TASK, null, false, false, false,
                        List.of("${vars.amount > 1000}"), List.of(), 10));
        var snapshot = snapshot(def, List.of(item("pi-1", "gated", PlanItemType.HUMAN_TASK,
                PlanItemState.AVAILABLE)), Map.of("amount", 100));

        assertThat(evaluator.evaluate(snapshot)).isEmpty();
    }

    @Test
    void metEntryCriterionActivatesTheItem() {
        CaseDefinition def = definition(
                def("gated", PlanItemType.HUMAN_TASK, null, false, false, false,
                        List.of("${vars.amount > 1000}"), List.of(), 10));
        var snapshot = snapshot(def, List.of(item("pi-1", "gated", PlanItemType.HUMAN_TASK,
                PlanItemState.AVAILABLE)), Map.of("amount", 5000));

        assertThat(evaluator.evaluate(snapshot)).singleElement()
                .extracting(Transition::to).isEqualTo(PlanItemState.ACTIVE);
    }

    @Test
    void criteriaSeeSiblingStates() {
        CaseDefinition def = definition(
                def("first", PlanItemType.HUMAN_TASK),
                def("second", PlanItemType.HUMAN_TASK, null, false, false, false,
                        List.of("${items.first.state == 'COMPLETED'}"), List.of(), 20));
        var snapshot = snapshot(def, List.of(
                item("pi-1", "first", PlanItemType.HUMAN_TASK, PlanItemState.COMPLETED),
                item("pi-2", "second", PlanItemType.HUMAN_TASK, PlanItemState.AVAILABLE)), Map.of());

        assertThat(evaluator.evaluate(snapshot)).singleElement()
                .satisfies(t -> assertThat(t.planItemId()).isEqualTo("pi-2"));
    }

    @Test
    void reachesAFixpointAcrossChainedCriteria() {
        // a completes -> milestone m achieves -> b activates. One evaluate() call must do both.
        CaseDefinition def = definition(
                def("a", PlanItemType.HUMAN_TASK),
                def("m", PlanItemType.MILESTONE, null, false, false, false,
                        List.of("${items.a.state == 'COMPLETED'}"), List.of(), 20),
                def("b", PlanItemType.HUMAN_TASK, null, false, false, false,
                        List.of("${items.m.state == 'COMPLETED'}"), List.of(), 30));
        var snapshot = snapshot(def, List.of(
                item("pi-a", "a", PlanItemType.HUMAN_TASK, PlanItemState.COMPLETED),
                item("pi-m", "m", PlanItemType.MILESTONE, PlanItemState.AVAILABLE),
                item("pi-b", "b", PlanItemType.HUMAN_TASK, PlanItemState.AVAILABLE)), Map.of());

        List<Transition> transitions = evaluator.evaluate(snapshot);

        assertThat(transitions).extracting(Transition::planItemId).containsExactly("pi-m", "pi-b");
        assertThat(transitions).extracting(Transition::to)
                .containsExactly(PlanItemState.COMPLETED, PlanItemState.ACTIVE);
    }

    @Test
    void evaluatesInSortOrderSoTransitionsAreDeterministic() {
        CaseDefinition def = definition(
                def("late", PlanItemType.HUMAN_TASK, null, false, false, false, List.of(), List.of(), 99),
                def("early", PlanItemType.HUMAN_TASK, null, false, false, false, List.of(), List.of(), 1));
        var snapshot = snapshot(def, List.of(
                item("pi-late", "late", PlanItemType.HUMAN_TASK, PlanItemState.AVAILABLE),
                item("pi-early", "early", PlanItemType.HUMAN_TASK, PlanItemState.AVAILABLE)), Map.of());

        assertThat(evaluator.evaluate(snapshot))
                .extracting(Transition::planItemId).containsExactly("pi-early", "pi-late");
    }

    @Test
    void loopGuardThrowsWhenTransitionsNeverSettle() {
        // The cap CANNOT be reached by any real model: with a fixed item set the state
        // machine is monotone — states only advance and ended items are skipped — so
        // evaluation always settles within about two passes per item. (A model of two
        // mutually-triggering milestones settles in two rounds and throws nothing.)
        // The guard exists for a FUTURE change that breaks monotonicity, so the only
        // honest way to test it is to force an endless transition stream through a seam.
        var alwaysTransitions = new PlanModelEvaluator(new JuelCriterionEvaluator()) {
            @Override
            List<Transition> singlePass(CaseSnapshot snapshot) {
                return List.of(new Transition("pi-1", PlanItemState.AVAILABLE,
                        PlanItemState.ACTIVE, "never settles"));
            }
        };
        CaseDefinition def = definition(def("task", PlanItemType.HUMAN_TASK));
        var snapshot = snapshot(def, List.of(item("pi-1", "task", PlanItemType.HUMAN_TASK,
                PlanItemState.AVAILABLE)), Map.of());

        assertThatThrownBy(() -> alwaysTransitions.evaluate(snapshot))
                .isInstanceOf(PlanModelLoopException.class)
                .hasMessageContaining("20");
    }

    @Test
    void endedItemsAreNeverReconsidered() {
        CaseDefinition def = definition(def("task", PlanItemType.HUMAN_TASK));
        var snapshot = snapshot(def, List.of(
                item("pi-1", "task", PlanItemType.HUMAN_TASK, PlanItemState.COMPLETED),
                item("pi-2", "task", PlanItemType.HUMAN_TASK, PlanItemState.TERMINATED)), Map.of());

        assertThat(evaluator.evaluate(snapshot)).isEmpty();
    }
}
```

- [ ] **Step 3: Run it and watch it fail**

Run: `./mvnw -q -pl case-management-core test -Dtest=PlanModelEvaluatorTest`
Expected: FAIL — `cannot find symbol: class CaseSnapshot`.

- [ ] **Step 4: Write the snapshot, transition and loop exception**

```java
package org.casemgmt.rules;

import org.casemgmt.domain.*;

import java.util.Comparator;
import java.util.List;

public record CaseSnapshot(CaseInstance caseInstance, CaseDefinition definition, List<PlanItem> planItems) {

    /**
     * All runtime instances of a definition key, oldest first (repetition creates several).
     *
     * Ordered by repetitionNo, NOT by createdAt: repeat instances are stamped with
     * independent OffsetDateTime.now() calls, so two created within the same clock tick
     * would make latest() ambiguous — and every cross-item criterion reading
     * items.<defKey>.state would then silently see whichever sorted last.
     */
    public List<PlanItem> items(String defKey) {
        return planItems.stream()
                .filter(i -> defKey.equals(defKeyOf(i)))
                .sorted(Comparator.comparingInt(PlanItem::repetitionNo)
                        .thenComparing(PlanItem::createdAt))
                .toList();
    }

    /** The most recent instance of a definition key, which is what criteria see. */
    public PlanItem latest(String defKey) {
        List<PlanItem> all = items(defKey);
        return all.isEmpty() ? null : all.get(all.size() - 1);
    }

    public PlanItemDefinition definitionOf(PlanItem item) {
        return definition.planItems().stream()
                .filter(d -> d.id().equals(item.planItemDefId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Plan item " + item.id() + " references unknown definition " + item.planItemDefId()));
    }

    private String defKeyOf(PlanItem item) {
        return definition.planItems().stream()
                .filter(d -> d.id().equals(item.planItemDefId()))
                .map(PlanItemDefinition::defKey)
                .findFirst().orElse(null);
    }

    public CaseSnapshot withPlanItems(List<PlanItem> updated) {
        return new CaseSnapshot(caseInstance, definition, updated);
    }
}
```

```java
package org.casemgmt.rules;

import org.casemgmt.domain.PlanItemState;

public record Transition(String planItemId, PlanItemState from, PlanItemState to, String reason) {}
```

```java
package org.casemgmt.rules;

public class PlanModelLoopException extends RuntimeException {
    public PlanModelLoopException(String caseId, int maxIterations) {
        super("Plan model for case " + caseId + " did not reach a fixpoint within "
                + maxIterations + " iterations — check for mutually-triggering criteria");
    }
}
```

- [ ] **Step 5: Write the instantiator**

```java
package org.casemgmt.rules;

import org.casemgmt.domain.*;

import java.time.OffsetDateTime;
import java.util.List;

public class PlanModelInstantiator {

    /**
     * One AVAILABLE runtime item per definition; the evaluator advances them from there.
     *
     * Two passes, because parentStageId must point at the runtime id of the parent's
     * instance: create every item first, then resolve parents. Leaving parentStageId null
     * (the obvious one-pass version) makes containment silently inert for every case.
     * A parentStageKey naming a stage that is not in the model is a broken definition and
     * throws — definitions arrive over the API from other teams, and silently exempting
     * such an item from containment is the failure this check exists to prevent.
     */
    public List<PlanItem> initialItems(String caseId, CaseDefinition definition) {
        OffsetDateTime now = OffsetDateTime.now();
        Map<String, String> instanceIdByDefKey = new LinkedHashMap<>();
        definition.planItems().forEach(d -> instanceIdByDefKey.put(d.defKey(), CaseIds.newId()));

        return definition.planItems().stream()
                .map(d -> {
                    String parentId = null;
                    if (d.parentStageKey() != null) {
                        parentId = instanceIdByDefKey.get(d.parentStageKey());
                        if (parentId == null) {
                            throw new IllegalArgumentException("Plan item '" + d.defKey()
                                    + "' names parent stage '" + d.parentStageKey()
                                    + "', which does not exist in definition " + definition.id());
                        }
                    }
                    return new PlanItem(instanceIdByDefKey.get(d.defKey()), caseId, d.id(), d.type(),
                            d.defKey(), PlanItemState.AVAILABLE, parentId, false, 1,
                            null, null, null, 0L, now, now, null);
                })
                .toList();
    }

    /** A further instance of a repeatable item (spec §3.2 repetition). */
    public PlanItem repeat(PlanItem previous, PlanItemDefinition definition) {
        OffsetDateTime now = OffsetDateTime.now();
        return new PlanItem(CaseIds.newId(), previous.caseId(), definition.id(), definition.type(),
                definition.defKey(), PlanItemState.AVAILABLE, previous.parentStageId(), false,
                previous.repetitionNo() + 1, null, null, null, 0L, now, now, null);
    }
}
```

- [ ] **Step 6: Write the evaluator**

```java
package org.casemgmt.rules;

import org.casemgmt.domain.*;

import java.util.*;

/**
 * Re-evaluates the plan model after every case mutation (spec §4.3).
 *
 * Pure: computes the transitions that should happen and returns them. Applying them —
 * writing rows, creating engine tasks, emitting events — belongs to the service layer.
 */
public class PlanModelEvaluator {

    public static final int MAX_ITERATIONS = 20;

    private final CriterionEvaluator criteria;

    public PlanModelEvaluator(CriterionEvaluator criteria) {
        this.criteria = criteria;
    }

    public List<Transition> evaluate(CaseSnapshot snapshot) {
        List<Transition> all = new ArrayList<>();
        CaseSnapshot current = snapshot;

        for (int iteration = 0; iteration < MAX_ITERATIONS; iteration++) {
            List<Transition> round = singlePass(current);
            if (round.isEmpty()) {
                return all;
            }
            all.addAll(round);
            current = apply(current, round);
        }
        throw new PlanModelLoopException(snapshot.caseInstance().id(), MAX_ITERATIONS);
    }

    private List<Transition> singlePass(CaseSnapshot snapshot) {
        List<Transition> transitions = new ArrayList<>();
        EvaluationContext context = contextOf(snapshot);

        // defKey breaks sortOrder ties so evaluation order is total, not incidental.
        List<PlanItem> ordered = snapshot.planItems().stream()
                .sorted(Comparator.comparingInt((PlanItem i) -> snapshot.definitionOf(i).sortOrder())
                        .thenComparing(i -> snapshot.definitionOf(i).defKey()))
                .toList();

        for (PlanItem item : ordered) {
            if (item.state().isEnded()) {
                continue;
            }
            PlanItemDefinition def = snapshot.definitionOf(item);

            if (criteria.allMatch(def.exitCriteria(), context) && !def.exitCriteria().isEmpty()) {
                transitions.add(new Transition(item.id(), item.state(), PlanItemState.TERMINATED,
                        "exit criterion met"));
                continue;
            }
            if (item.state() == PlanItemState.AVAILABLE
                    && criteria.allMatch(def.entryCriteria(), context)) {
                transitions.add(new Transition(item.id(), PlanItemState.AVAILABLE,
                        targetOnEntry(def), "entry criterion met"));
            }
        }
        return transitions;
    }

    /**
     * Milestones complete on entry — they mark a fact, they are not worked on.
     * Manual-activation items stop at ENABLED and wait for an explicit start.
     */
    private PlanItemState targetOnEntry(PlanItemDefinition def) {
        if (def.type() == PlanItemType.MILESTONE) {
            return PlanItemState.COMPLETED;
        }
        return def.manualActivation() ? PlanItemState.ENABLED : PlanItemState.ACTIVE;
    }

    private EvaluationContext contextOf(CaseSnapshot snapshot) {
        CaseInstance c = snapshot.caseInstance();
        Map<String, Object> caseAttributes = new LinkedHashMap<>();
        caseAttributes.put("state", c.state().name());
        caseAttributes.put("priority", c.priority().name());
        caseAttributes.put("businessKey", c.businessKey());
        caseAttributes.put("assignee", c.assignee());

        Map<String, Map<String, Object>> items = new LinkedHashMap<>();
        for (PlanItemDefinition def : snapshot.definition().planItems()) {
            PlanItem latest = snapshot.latest(def.defKey());
            items.put(def.defKey(), Map.of(
                    "state", latest == null ? PlanItemState.AVAILABLE.name() : latest.state().name(),
                    "type", def.type().name()));
        }
        return new EvaluationContext(caseAttributes, c.variables(), items);
    }

    private CaseSnapshot apply(CaseSnapshot snapshot, List<Transition> transitions) {
        Map<String, PlanItemState> byId = new HashMap<>();
        transitions.forEach(t -> byId.put(t.planItemId(), t.to()));

        List<PlanItem> updated = snapshot.planItems().stream()
                .map(i -> byId.containsKey(i.id()) ? i.withState(byId.get(i.id())) : i)
                .toList();
        return snapshot.withPlanItems(updated);
    }
}
```

- [ ] **Step 7: Run the test to verify it passes**

Run: `./mvnw -q -pl case-management-core test -Dtest=PlanModelEvaluatorTest`
Expected: PASS, all eleven tests.

- [ ] **Step 8: Commit**

```bash
git add case-management-core/src
git commit -m "feat(core): plan model evaluator with entry criteria and fixpoint loop"
```

---

### Task 9: Required items, repetition and stage completion

**Files:**
- Modify: `case-management-core/src/main/java/org/casemgmt/rules/PlanModelEvaluator.java`
- Create: `case-management-core/src/main/java/org/casemgmt/rules/StageCompletion.java`
- Create: `case-management-core/src/test/java/org/casemgmt/rules/StageCompletionTest.java`
- Create: `case-management-core/src/test/java/org/casemgmt/rules/RepetitionTest.java`

**Carried forward from Task 8's review (Important).** The evaluator never reads `parentStageId` / `parentStageKey`, so a child plan item activates regardless of its parent stage's state — a human task inside a stage that is still `ENABLED` and was never started goes `ACTIVE` anyway. The complaint model only survives this by duplicating the stage's entry criteria onto each child, which is fragile and not what CMMN containment means. **This task must fix it:** a plan item with a parent stage is only considered for entry while that stage is `ACTIVE`. Add `StageCompletion.isContained(CaseSnapshot, PlanItem) : boolean` (or an equivalent guard inside `singlePass`), and a test proving a child of an `ENABLED` stage stays `AVAILABLE` until the stage starts.

**Interfaces:**
- Consumes: everything from Task 8
- Produces:
  - `StageCompletion.canComplete(CaseSnapshot, PlanItem stage) : boolean`
  - `StageCompletion.isContained(CaseSnapshot, PlanItem item) : boolean` — false when the item's parent stage exists and is not `ACTIVE`
  - `StageCompletion.blockingItems(CaseSnapshot, PlanItem stage) : List<PlanItem>` — required items that are not ended, used by both the evaluator and the `409` message
  - `StageCompletion.caseCanClose(CaseSnapshot) : boolean` and `.caseBlockers(CaseSnapshot) : List<PlanItem>`
  - `PlanModelEvaluator.evaluate` additionally completes stages whose children are all ended, and re-instantiates repeatable items

- [ ] **Step 1: Write the failing tests**

`case-management-core/src/test/java/org/casemgmt/rules/StageCompletionTest.java`:

```java
package org.casemgmt.rules;

import org.casemgmt.domain.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.casemgmt.rules.PlanModelFixtures.*;

class StageCompletionTest {

    private final StageCompletion completion = new StageCompletion();
    private final PlanModelEvaluator evaluator = new PlanModelEvaluator(new JuelCriterionEvaluator());

    private CaseDefinition stageWithTwoChildren(boolean secondRequired) {
        return definition(
                def("stage", PlanItemType.STAGE, null, false, false, false, List.of(), List.of(), 10),
                def("required", PlanItemType.HUMAN_TASK, "stage", false, true, false,
                        List.of(), List.of(), 20),
                def("optional", PlanItemType.HUMAN_TASK, "stage", false, secondRequired, false,
                        List.of(), List.of(), 30));
    }

    @Test
    void stageWithAnUnfinishedRequiredChildCannotComplete() {
        var snapshot = snapshot(stageWithTwoChildren(false), List.of(
                item("pi-stage", "stage", PlanItemType.STAGE, PlanItemState.ACTIVE),
                item("pi-req", "required", PlanItemType.HUMAN_TASK, PlanItemState.ACTIVE, "pi-stage"),
                item("pi-opt", "optional", PlanItemType.HUMAN_TASK, PlanItemState.COMPLETED, "pi-stage")),
                Map.of());

        PlanItem stage = snapshot.planItems().get(0);

        assertThat(completion.canComplete(snapshot, stage)).isFalse();
        assertThat(completion.blockingItems(snapshot, stage))
                .extracting(PlanItem::id).containsExactly("pi-req");
    }

    @Test
    void stageCompletesWhenRequiredChildrenAreDoneEvenIfOptionalOnesAreNot() {
        var snapshot = snapshot(stageWithTwoChildren(false), List.of(
                item("pi-stage", "stage", PlanItemType.STAGE, PlanItemState.ACTIVE),
                item("pi-req", "required", PlanItemType.HUMAN_TASK, PlanItemState.COMPLETED, "pi-stage"),
                item("pi-opt", "optional", PlanItemType.HUMAN_TASK, PlanItemState.AVAILABLE, "pi-stage")),
                Map.of());

        assertThat(completion.canComplete(snapshot, snapshot.planItems().get(0))).isTrue();
    }

    @Test
    void evaluatorCompletesASatisfiedStage() {
        var snapshot = snapshot(stageWithTwoChildren(false), List.of(
                item("pi-stage", "stage", PlanItemType.STAGE, PlanItemState.ACTIVE),
                item("pi-req", "required", PlanItemType.HUMAN_TASK, PlanItemState.COMPLETED, "pi-stage"),
                item("pi-opt", "optional", PlanItemType.HUMAN_TASK, PlanItemState.COMPLETED, "pi-stage")),
                Map.of());

        assertThat(evaluator.evaluate(snapshot))
                .anySatisfy(t -> {
                    assertThat(t.planItemId()).isEqualTo("pi-stage");
                    assertThat(t.to()).isEqualTo(PlanItemState.COMPLETED);
                });
    }

    @Test
    void caseCannotCloseWhileARequiredItemIsOpen() {
        var snapshot = snapshot(stageWithTwoChildren(false), List.of(
                item("pi-stage", "stage", PlanItemType.STAGE, PlanItemState.ACTIVE),
                item("pi-req", "required", PlanItemType.HUMAN_TASK, PlanItemState.ACTIVE, "pi-stage")),
                Map.of());

        assertThat(completion.caseCanClose(snapshot)).isFalse();
        assertThat(completion.caseBlockers(snapshot)).extracting(PlanItem::name)
                .containsExactly("required");
    }
}
```

`case-management-core/src/test/java/org/casemgmt/rules/RepetitionTest.java`:

```java
package org.casemgmt.rules;

import org.casemgmt.domain.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.casemgmt.rules.PlanModelFixtures.*;

class RepetitionTest {

    @Test
    void repeatCreatesANewAvailableInstanceWithAnIncrementedCounter() {
        PlanItemDefinition def = def("investigate", PlanItemType.HUMAN_TASK, "stage",
                true, false, true, List.of(), List.of(), 20);
        PlanItem first = item("pi-1", "investigate", PlanItemType.HUMAN_TASK,
                PlanItemState.COMPLETED, "pi-stage");

        PlanItem second = new PlanModelInstantiator().repeat(first, def);

        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(second.state()).isEqualTo(PlanItemState.AVAILABLE);
        assertThat(second.repetitionNo()).isEqualTo(2);
        assertThat(second.parentStageId()).isEqualTo("pi-stage");
    }

    @Test
    void criteriaSeeTheMostRecentInstanceOfARepeatedItem() {
        CaseDefinition def = definition(
                def("investigate", PlanItemType.HUMAN_TASK, null, true, false, true,
                        List.of(), List.of(), 10),
                def("after", PlanItemType.HUMAN_TASK, null, false, false, false,
                        List.of("${items.investigate.state == 'ACTIVE'}"), List.of(), 20));

        var snapshot = snapshot(def, List.of(
                item("pi-1", "investigate", PlanItemType.HUMAN_TASK, PlanItemState.COMPLETED),
                item("pi-2", "investigate", PlanItemType.HUMAN_TASK, PlanItemState.ACTIVE),
                item("pi-after", "after", PlanItemType.HUMAN_TASK, PlanItemState.AVAILABLE)),
                Map.of());

        assertThat(new PlanModelEvaluator(new JuelCriterionEvaluator()).evaluate(snapshot))
                .extracting(Transition::planItemId).contains("pi-after");
    }
}
```

Note: `PlanModelFixtures.item(...)` sets `createdAt` to `OffsetDateTime.now()` for every item, so "most recent instance" needs a stable ordering. Before running, change `PlanModelFixtures.item` to stagger creation times:

```java
    private static int seq = 0;

    public static PlanItem item(String id, String defKey, PlanItemType type,
                                PlanItemState state, String parentStageId) {
        OffsetDateTime created = OffsetDateTime.now().plusNanos(1_000_000L * seq++);
        return new PlanItem(id, "eng-a:1", "pd-" + defKey, type, defKey, state, parentStageId,
                false, 1, null, null, null, 0L, created, created, null);
    }
```

- [ ] **Step 2: Run them and watch them fail**

Run: `./mvnw -q -pl case-management-core test -Dtest='StageCompletionTest,RepetitionTest'`
Expected: FAIL — `cannot find symbol: class StageCompletion`.

- [ ] **Step 3: Write `StageCompletion`**

```java
package org.casemgmt.rules;

import org.casemgmt.domain.PlanItem;
import org.casemgmt.domain.PlanItemDefinition;

import java.util.List;

/**
 * "Required" gating (spec §3.2): a stage cannot complete, and a case cannot close,
 * while a required plan item is unfinished. blockingItems() feeds both the evaluator
 * and the 409 response body, so the API can say exactly what is in the way.
 */
public class StageCompletion {

    /**
     * CMMN autocomplete semantics: a stage may complete only when no required child is
     * unfinished AND no child is still ACTIVE.
     *
     * The tempting rule — "no required child unfinished AND some child has ended" — lets a
     * stage complete in the very same evaluation pass that admits a sibling child beneath
     * it, leaving a COMPLETED stage with a live ACTIVE child that nothing ever revisits.
     * Requiring the absence of ACTIVE children makes that state unreachable by
     * construction rather than by ordering luck.
     */
    public boolean canComplete(CaseSnapshot snapshot, PlanItem stage) {
        return blockingItems(snapshot, stage).isEmpty()
                && children(snapshot, stage).stream()
                        .noneMatch(c -> c.state() == PlanItemState.ACTIVE);
    }

    /**
     * Descendants a completing stage must terminate: everything not yet started, at ANY
     * depth. They are emitted as real Transitions so the service layer persists them —
     * never mutated silently. Because canComplete already excludes ACTIVE children, this
     * never terminates work in progress.
     *
     * Depth matters: sweeping only direct children strands the children of an unstarted
     * substage beneath a stage that has ended.
     */
    public List<PlanItem> childrenToTerminateOnCompletion(CaseSnapshot snapshot, PlanItem stage) {
        return descendants(snapshot, stage).stream()
                .filter(c -> c.state() == PlanItemState.AVAILABLE || c.state() == PlanItemState.ENABLED)
                .toList();
    }

    /**
     * An exit criterion is unconditional: it terminates the stage and EVERYTHING beneath
     * it, including ACTIVE work, at any depth. That is CMMN exit-sentry semantics, and it
     * differs deliberately from the completion sweep above, which can never meet an ACTIVE
     * child because canComplete already excluded that case.
     */
    public List<PlanItem> childrenToCascadeTerminate(CaseSnapshot snapshot, PlanItem stage) {
        return descendants(snapshot, stage).stream()
                .filter(c -> !c.state().isEnded())
                .toList();
    }

    /**
     * Every item beneath this stage, transitively. The visited set is not an optimisation:
     * a malformed model can contain a parentStageId cycle, and recursing it would blow the
     * stack instead of naming the problem.
     */
    private List<PlanItem> descendants(CaseSnapshot snapshot, PlanItem stage) {
        List<PlanItem> found = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        visited.add(stage.id());
        Deque<PlanItem> queue = new ArrayDeque<>(children(snapshot, stage));

        while (!queue.isEmpty()) {
            PlanItem next = queue.removeFirst();
            if (!visited.add(next.id())) {
                throw new IllegalStateException(
                        "parentStageId cycle detected at plan item " + next.id());
            }
            found.add(next);
            queue.addAll(children(snapshot, next));
        }
        return found;
    }

    public List<PlanItem> blockingItems(CaseSnapshot snapshot, PlanItem stage) {
        return children(snapshot, stage).stream()
                .filter(child -> !child.state().isEnded())
                .filter(child -> snapshot.definitionOf(child).required())
                .toList();
    }

    public boolean caseCanClose(CaseSnapshot snapshot) {
        return caseBlockers(snapshot).isEmpty();
    }

    public List<PlanItem> caseBlockers(CaseSnapshot snapshot) {
        return snapshot.planItems().stream()
                .filter(i -> !i.state().isEnded())
                .filter(i -> snapshot.definitionOf(i).required())
                .toList();
    }

    private List<PlanItem> children(CaseSnapshot snapshot, PlanItem stage) {
        return snapshot.planItems().stream()
                .filter(i -> stage.id().equals(i.parentStageId()))
                .toList();
    }

    /** Definition-level lookup used when a stage has no instantiated children yet. */
    public List<PlanItemDefinition> childDefinitions(CaseSnapshot snapshot, PlanItem stage) {
        String stageKey = snapshot.definitionOf(stage).defKey();
        return snapshot.definition().planItems().stream()
                .filter(d -> stageKey.equals(d.parentStageKey()))
                .toList();
    }
}
```

- [ ] **Step 4: Extend the evaluator with stage completion and repetition**

In `PlanModelEvaluator`, add the field and constructor wiring:

```java
    private final StageCompletion stageCompletion = new StageCompletion();
```

Then, inside `singlePass`, after the entry-criteria block and still within the `for (PlanItem item : ordered)` loop, add:

```java
            if (def.type() == PlanItemType.STAGE && item.state() == PlanItemState.ACTIVE
                    && stageCompletion.canComplete(snapshot, item)) {
                transitions.add(new Transition(item.id(), PlanItemState.ACTIVE,
                        PlanItemState.COMPLETED, "no required child unfinished, no child active"));
                // Unstarted descendants die with the stage, and the terminations are
                // reported so the service layer persists them.
                for (PlanItem child : stageCompletion.childrenToTerminateOnCompletion(snapshot, item)) {
                    transitions.add(new Transition(child.id(), child.state(),
                            PlanItemState.TERMINATED, "parent stage completed"));
                }
            }
```

**Precedence, and why it must be decided rather than fall out of loop order.** A stage can satisfy its
exit criteria *and* be autocompletable in the same round. The exit criterion is an explicit statement by
the model's author and wins: compute exit-terminating stages first, exclude them from the autocomplete
set so the two are disjoint by construction, and check them first in the loop. Getting this backwards
silently discards the exit criterion and reports the stage as COMPLETED — a bug that reordering
introduces easily and no test catches unless one exists specifically for the overlap:

```java
        Set<String> terminatingStageIds = /* stages whose exitCriteria are satisfied */;
        // Autocomplete never claims a stage that is already exiting.
        List<PlanItem> completingStages = candidates.stream()
                .filter(i -> !terminatingStageIds.contains(i.id()))
                .toList();
```

Repetition is handled by the service layer rather than the evaluator, because it creates rows rather than moving states. Add this query method to `PlanModelEvaluator` for the service to call after applying transitions:

```java
    /**
     * Definition keys that should get a fresh AVAILABLE instance: repeatable items whose
     * latest instance has just ended and whose entry criteria still hold.
     */
    public List<PlanItemDefinition> repeatable(CaseSnapshot snapshot) {
        EvaluationContext context = contextOf(snapshot);
        return snapshot.definition().planItems().stream()
                .filter(PlanItemDefinition::repetition)
                .filter(def -> {
                    PlanItem latest = snapshot.latest(def.defKey());
                    return latest != null && latest.state().isEnded();
                })
                .filter(def -> criteria.allMatch(def.entryCriteria(), context))
                .toList();
    }
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./mvnw -q -pl case-management-core test -Dtest='StageCompletionTest,RepetitionTest,PlanModelEvaluatorTest'`
Expected: PASS — all tests from Task 8 still green plus the six new ones.

- [ ] **Step 6: Commit**

```bash
git add case-management-core/src
git commit -m "feat(core): required-item gating, stage completion and repetition"
```

---

## Phase 3 — Engine integration (Risk R2)

### Task 10: EngineGateway interface and its contract test

**Files:**
- Create: `case-management-core/src/main/java/org/casemgmt/engine/EngineGateway.java`
- Create: `case-management-core/src/main/java/org/casemgmt/engine/EngineDtos.java`
- Create: `case-management-core/src/main/java/org/casemgmt/engine/EngineException.java`
- Create: `case-management-core/src/testFixtures/java/org/casemgmt/engine/EngineGatewayContract.java` (plain `src/test/java` is fine if test-jar packaging is avoided — see Step 4)
- Modify: `case-management-core/pom.xml` (produce a test-jar so both gateway modules can run the contract)

**Interfaces:**
- Consumes: nothing (core-only types)
- Produces:
  - `EngineGateway` with the six methods below
  - DTOs: `HumanTaskRequest`, `EngineTaskRef`, `StartProcessRequest`, `EngineProcessRef`, `EngineTaskQuery`
  - `EngineGatewayContract` — an abstract JUnit class with one abstract factory method `protected abstract EngineGateway gateway()` plus `protected abstract String deployTestProcess()`. Tasks 11 and 12 each subclass it.

- [ ] **Step 1: Write the interface and DTOs**

```java
package org.casemgmt.engine;

import java.util.List;
import java.util.Map;

/**
 * Everything the case service needs from a BPMN engine — and nothing else.
 *
 * Implementations live in case-management-engine-embedded (in-process Operaton Java API)
 * and case-management-engine-remote (engine-rest over HTTP). Core code depends only on
 * this interface, which is what allows either deployment mode (spec §3.4).
 */
public interface EngineGateway {

    EngineTaskRef createHumanTask(HumanTaskRequest request);

    void claimTask(String engineTaskId, String userId);

    void completeTask(String engineTaskId, Map<String, Object> variables);

    EngineProcessRef startProcess(StartProcessRequest request);

    void cancelProcess(String processInstanceId, String reason);

    List<EngineTaskRef> findTasks(EngineTaskQuery query);
}
```

```java
package org.casemgmt.engine;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public final class EngineDtos {
    private EngineDtos() {}
}
```

Put each record in its own file in the same package:

```java
package org.casemgmt.engine;

import java.util.List;
import java.util.Map;

public record HumanTaskRequest(String caseId, String planItemId, String name,
                               String assignee, List<String> candidateGroups,
                               String formKey, Map<String, Object> variables) {}
```

```java
package org.casemgmt.engine;

import java.time.OffsetDateTime;

public record EngineTaskRef(String engineTaskId, String name, String assignee,
                            String caseId, OffsetDateTime createdAt) {}
```

```java
package org.casemgmt.engine;

import java.util.Map;

public record StartProcessRequest(String caseId, String planItemId,
                                  String processDefinitionKey, Map<String, Object> variables) {}
```

```java
package org.casemgmt.engine;

public record EngineProcessRef(String processInstanceId, String processDefinitionKey) {}
```

```java
package org.casemgmt.engine;

import java.util.List;

public record EngineTaskQuery(String assignee, List<String> candidateGroups,
                              String caseId, int maxResults) {}
```

```java
package org.casemgmt.engine;

public class EngineException extends RuntimeException {
    public EngineException(String message, Throwable cause) { super(message, cause); }
    public EngineException(String message) { super(message); }
}
```

- [ ] **Step 2: Write the contract test**

`case-management-core/src/test/java/org/casemgmt/engine/EngineGatewayContract.java`:

```java
package org.casemgmt.engine;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * One suite, both implementations (spec §9). If embedded and remote disagree here,
 * the interface is lying about one of them.
 */
public abstract class EngineGatewayContract {

    protected abstract EngineGateway gateway();

    /** Deploys the test BPMN process and returns its definition key. */
    protected abstract String deployTestProcess();

    @Test
    void createsAHumanTaskCarryingTheCaseId() {
        EngineTaskRef ref = gateway().createHumanTask(new HumanTaskRequest(
                "eng-a:1", "pi-1", "Review", null, List.of("reviewers"), "reviewForm",
                Map.of("amount", 100)));

        assertThat(ref.engineTaskId()).isNotBlank();
        assertThat(ref.name()).isEqualTo("Review");
        assertThat(ref.caseId()).isEqualTo("eng-a:1");
    }

    @Test
    void findsCreatedTasksByCandidateGroup() {
        gateway().createHumanTask(new HumanTaskRequest(
                "eng-a:2", "pi-2", "Grouped", null, List.of("special-group"), null, Map.of()));

        List<EngineTaskRef> found = gateway().findTasks(
                new EngineTaskQuery(null, List.of("special-group"), null, 10));

        assertThat(found).isNotEmpty();
        assertThat(found).allSatisfy(t -> assertThat(t.engineTaskId()).isNotBlank());
    }

    @Test
    void findsTasksByCaseId() {
        gateway().createHumanTask(new HumanTaskRequest(
                "eng-a:3", "pi-3", "ByCase", null, List.of(), null, Map.of()));

        assertThat(gateway().findTasks(new EngineTaskQuery(null, null, "eng-a:3", 10)))
                .hasSize(1)
                .allSatisfy(t -> assertThat(t.caseId()).isEqualTo("eng-a:3"));
    }

    @Test
    void claimAssignsTheTask() {
        EngineTaskRef ref = gateway().createHumanTask(new HumanTaskRequest(
                "eng-a:4", "pi-4", "Claimable", null, List.of("reviewers"), null, Map.of()));

        gateway().claimTask(ref.engineTaskId(), "alice");

        assertThat(gateway().findTasks(new EngineTaskQuery("alice", null, "eng-a:4", 10)))
                .extracting(EngineTaskRef::assignee).containsExactly("alice");
    }

    @Test
    void completeRemovesTheTaskFromTheWorklist() {
        EngineTaskRef ref = gateway().createHumanTask(new HumanTaskRequest(
                "eng-a:5", "pi-5", "Completable", "alice", List.of(), null, Map.of()));

        gateway().completeTask(ref.engineTaskId(), Map.of("outcome", "approve"));

        assertThat(gateway().findTasks(new EngineTaskQuery(null, null, "eng-a:5", 10))).isEmpty();
    }

    @Test
    void completingAnUnknownTaskFailsWithEngineException() {
        assertThatThrownBy(() -> gateway().completeTask("no-such-task", Map.of()))
                .isInstanceOf(EngineException.class);
    }

    @Test
    void startsAProcessCorrelatedToTheCase() {
        String key = deployTestProcess();

        EngineProcessRef ref = gateway().startProcess(new StartProcessRequest(
                "eng-a:6", "pi-6", key, Map.of("reason", "test")));

        assertThat(ref.processInstanceId()).isNotBlank();
        assertThat(ref.processDefinitionKey()).isEqualTo(key);
    }

    @Test
    void cancelsARunningProcess() {
        String key = deployTestProcess();
        EngineProcessRef ref = gateway().startProcess(new StartProcessRequest(
                "eng-a:7", "pi-7", key, Map.of()));

        gateway().cancelProcess(ref.processInstanceId(), "no longer needed");

        // Cancelling twice must fail rather than silently succeed.
        assertThatThrownBy(() -> gateway().cancelProcess(ref.processInstanceId(), "again"))
                .isInstanceOf(EngineException.class);
    }
}
```

- [ ] **Step 3: Verify it compiles and is skipped without a subclass**

Run: `./mvnw -q -pl case-management-core test`
Expected: PASS — `EngineGatewayContract` is abstract, so JUnit does not run it directly. All earlier tests stay green.

- [ ] **Step 4: Publish the test-jar so gateway modules can subclass the contract**

Add to `case-management-core/pom.xml` inside `<build><plugins>`:

```xml
  <plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-jar-plugin</artifactId>
    <executions>
      <execution>
        <id>test-jar</id>
        <goals><goal>test-jar</goal></goals>
      </execution>
    </executions>
  </plugin>
```

And in both `case-management-engine-embedded/pom.xml` and `case-management-engine-remote/pom.xml`:

```xml
  <dependency>
    <groupId>org.casemgmt</groupId>
    <artifactId>case-management-core</artifactId>
    <version>${project.version}</version>
    <type>test-jar</type>
    <scope>test</scope>
  </dependency>
```

- [ ] **Step 5: Commit**

```bash
git add case-management-core/ case-management-engine-*/pom.xml
git commit -m "feat(core): EngineGateway interface and shared contract test"
```

---

### Task 11: Embedded gateway (in-process Operaton)

**Files:**
- Create: `case-management-engine-embedded/src/main/java/org/casemgmt/engine/embedded/EmbeddedEngineGateway.java`
- Create: `case-management-engine-embedded/src/test/java/org/casemgmt/engine/embedded/EmbeddedEngineGatewayIT.java`
- Create: `case-management-engine-embedded/src/test/resources/processes/test-process.bpmn`
- Create: `case-management-engine-embedded/src/test/resources/application-test.yaml`

**Interfaces:**
- Consumes: `EngineGateway`, DTOs, `EngineException` (Task 10); `EngineGatewayContract` (Task 10, test-jar)
- Produces: `EmbeddedEngineGateway implements EngineGateway`, constructed as `new EmbeddedEngineGateway(TaskService, RuntimeService)`

**Correlation rule:** the case id is stored on every engine task and process instance as the process variable `caseId`, and process instances additionally use it as the **business key**. Both directions of lookup then work without a join table.

- [ ] **Step 1: Write the test BPMN**

`case-management-engine-embedded/src/test/resources/processes/test-process.bpmn` — a minimal process with one user task, so `cancelProcess` has something to cancel:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                  xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                  id="defs-test" targetNamespace="http://casemgmt.org/test">
  <bpmn:process id="test-fragment" name="Test Fragment" isExecutable="true">
    <bpmn:startEvent id="start"/>
    <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="wait"/>
    <bpmn:userTask id="wait" name="Wait"/>
    <bpmn:sequenceFlow id="f2" sourceRef="wait" targetRef="end"/>
    <bpmn:endEvent id="end"/>
  </bpmn:process>
</bpmn:definitions>
```

- [ ] **Step 2: Write the failing test**

`case-management-engine-embedded/src/test/java/org/casemgmt/engine/embedded/EmbeddedEngineGatewayIT.java`:

```java
package org.casemgmt.engine.embedded;

import org.casemgmt.engine.EngineGateway;
import org.casemgmt.engine.EngineGatewayContract;
import org.operaton.bpm.engine.RepositoryService;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.TaskService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(classes = EmbeddedEngineGatewayIT.TestApp.class)
class EmbeddedEngineGatewayIT extends EngineGatewayContract {

    @SpringBootApplication
    static class TestApp {}

    @Autowired TaskService taskService;
    @Autowired RuntimeService runtimeService;
    @Autowired RepositoryService repositoryService;

    @Override
    protected EngineGateway gateway() {
        return new EmbeddedEngineGateway(taskService, runtimeService);
    }

    @Override
    protected String deployTestProcess() {
        repositoryService.createDeployment()
                .addClasspathResource("processes/test-process.bpmn")
                .enableDuplicateFiltering(true)
                .deploy();
        return "test-fragment";
    }
}
```

`case-management-engine-embedded/src/test/resources/application-test.yaml` — the engine runs on its own H2 here because this module tests *the engine binding only*; case persistence is not involved:

```yaml
spring:
  datasource:
    url: jdbc:h2:mem:engine;DB_CLOSE_DELAY=-1
    driver-class-name: org.h2.Driver
operaton:
  bpm:
    generic-properties:
      properties:
        history-level: full
```

Add `com.h2database:h2` and `org.springframework.boot:spring-boot-starter-test` as test-scoped dependencies of this module, and set `spring.profiles.active=test` via `src/test/resources/application.yaml`.

- [ ] **Step 3: Run it and watch it fail**

Run: `./mvnw -q -pl case-management-engine-embedded test`
Expected: FAIL — `cannot find symbol: class EmbeddedEngineGateway`.

- [ ] **Step 4: Write the gateway**

```java
package org.casemgmt.engine.embedded;

import org.casemgmt.engine.*;
import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.TaskService;
import org.operaton.bpm.engine.task.Task;
import org.operaton.bpm.engine.task.TaskQuery;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * In-process gateway. Runs inside the same transaction as the case mutation
 * (spec §3.5 embedded mode), so a rolled-back case change also rolls back the
 * engine task it created.
 */
public class EmbeddedEngineGateway implements EngineGateway {

    /** Process/task variable carrying the owning case. Also the process business key. */
    public static final String CASE_ID_VARIABLE = "caseId";
    private static final String PLAN_ITEM_VARIABLE = "planItemId";

    private final TaskService taskService;
    private final RuntimeService runtimeService;

    public EmbeddedEngineGateway(TaskService taskService, RuntimeService runtimeService) {
        this.taskService = taskService;
        this.runtimeService = runtimeService;
    }

    @Override
    public EngineTaskRef createHumanTask(HumanTaskRequest request) {
        Task task = taskService.newTask();
        task.setName(request.name());
        if (request.assignee() != null) {
            task.setAssignee(request.assignee());
        }
        taskService.saveTask(task);

        if (request.candidateGroups() != null) {
            for (String group : request.candidateGroups()) {
                taskService.addCandidateGroup(task.getId(), group);
            }
        }
        Map<String, Object> variables = new HashMap<>(
                request.variables() == null ? Map.of() : request.variables());
        variables.put(CASE_ID_VARIABLE, request.caseId());
        variables.put(PLAN_ITEM_VARIABLE, request.planItemId());
        taskService.setVariables(task.getId(), variables);

        return toRef(taskService.createTaskQuery().taskId(task.getId()).singleResult(),
                request.caseId());
    }

    @Override
    public void claimTask(String engineTaskId, String userId) {
        try {
            taskService.claim(engineTaskId, userId);
        } catch (ProcessEngineException e) {
            throw new EngineException("Could not claim task " + engineTaskId, e);
        }
    }

    @Override
    public void completeTask(String engineTaskId, Map<String, Object> variables) {
        try {
            taskService.complete(engineTaskId, variables == null ? Map.of() : variables);
        } catch (ProcessEngineException e) {
            throw new EngineException("Could not complete task " + engineTaskId, e);
        }
    }

    @Override
    public EngineProcessRef startProcess(StartProcessRequest request) {
        Map<String, Object> variables = new HashMap<>(
                request.variables() == null ? Map.of() : request.variables());
        variables.put(CASE_ID_VARIABLE, request.caseId());
        variables.put(PLAN_ITEM_VARIABLE, request.planItemId());
        try {
            var instance = runtimeService.startProcessInstanceByKey(
                    request.processDefinitionKey(), request.caseId(), variables);
            return new EngineProcessRef(instance.getId(), request.processDefinitionKey());
        } catch (ProcessEngineException e) {
            throw new EngineException(
                    "Could not start process " + request.processDefinitionKey(), e);
        }
    }

    @Override
    public void cancelProcess(String processInstanceId, String reason) {
        try {
            runtimeService.deleteProcessInstance(processInstanceId, reason);
        } catch (ProcessEngineException e) {
            throw new EngineException("Could not cancel process " + processInstanceId, e);
        }
    }

    @Override
    public List<EngineTaskRef> findTasks(EngineTaskQuery query) {
        TaskQuery q = taskService.createTaskQuery();
        if (query.assignee() != null) {
            q = q.taskAssignee(query.assignee());
        }
        if (query.candidateGroups() != null && !query.candidateGroups().isEmpty()) {
            q = q.taskCandidateGroupIn(query.candidateGroups()).includeAssignedTasks();
        }
        if (query.caseId() != null) {
            q = q.taskVariableValueEquals(CASE_ID_VARIABLE, query.caseId());
        }
        return q.list().stream()
                .limit(query.maxResults() <= 0 ? 50 : query.maxResults())
                .map(t -> toRef(t, caseIdOf(t)))
                .toList();
    }

    private String caseIdOf(Task task) {
        Object value = taskService.getVariable(task.getId(), CASE_ID_VARIABLE);
        return value == null ? null : value.toString();
    }

    private EngineTaskRef toRef(Task task, String caseId) {
        return new EngineTaskRef(task.getId(), task.getName(), task.getAssignee(), caseId,
                task.getCreateTime() == null ? null
                        : OffsetDateTime.ofInstant(task.getCreateTime().toInstant(), ZoneId.systemDefault()));
    }
}
```

- [ ] **Step 5: Run the contract to verify it passes**

Run: `./mvnw -q -pl case-management-engine-embedded test`
Expected: PASS — all eight contract tests.

If `taskVariableValueEquals` does not match tasks created via `newTask()` (standalone tasks store variables differently from process tasks), switch `findTasks` to query by task *local* variables (`taskService.createTaskQuery().processVariableValueEquals(...)` will not work for standalone tasks). Record whichever query form works in `FINDINGS.md` — it is exactly the kind of engine-behaviour detail this PoC exists to discover.

- [ ] **Step 6: Commit**

```bash
git add case-management-engine-embedded/
git commit -m "feat(engine): embedded Operaton gateway passing the contract suite"
```

---

### Task 12: Remote gateway (engine-rest over HTTP)

**Files:**
- Create: `case-management-engine-remote/src/main/java/org/casemgmt/engine/remote/RemoteEngineGateway.java`
- Create: `case-management-engine-remote/src/test/java/org/casemgmt/engine/remote/RemoteEngineGatewayIT.java`
- Create: `case-management-engine-remote/src/test/resources/processes/test-process.bpmn` (identical content to Task 11 Step 1)
- Create: `case-management-engine-remote/src/test/resources/application.yaml`

**Interfaces:**
- Consumes: `EngineGateway`, DTOs (Task 10)
- Produces: `RemoteEngineGateway implements EngineGateway`, constructed as `new RemoteEngineGateway(RestClient restClient)` where the client's base URL is the engine-rest root

**Test topology:** the test boots a Spring context that *is* an engine-only Operaton app with `engine-rest` exposed on a random port, then points the gateway at `http://localhost:{port}/engine-rest`. Same JVM, real HTTP, real serialization — which is what makes the contract meaningful.

- [ ] **Step 1: Write the failing test**

```java
package org.casemgmt.engine.remote;

import org.casemgmt.engine.EngineGateway;
import org.casemgmt.engine.EngineGatewayContract;
import org.operaton.bpm.engine.RepositoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.client.RestClient;

@SpringBootTest(classes = RemoteEngineGatewayIT.EngineOnlyApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RemoteEngineGatewayIT extends EngineGatewayContract {

    /** An Operaton app with NO case management on it — the "remote engine". */
    @SpringBootApplication
    static class EngineOnlyApp {}

    @LocalServerPort int port;
    @Autowired RepositoryService repositoryService;

    @Override
    protected EngineGateway gateway() {
        return new RemoteEngineGateway(RestClient.builder()
                .baseUrl("http://localhost:" + port + "/engine-rest")
                .build());
    }

    @Override
    protected String deployTestProcess() {
        repositoryService.createDeployment()
                .addClasspathResource("processes/test-process.bpmn")
                .enableDuplicateFiltering(true)
                .deploy();
        return "test-fragment";
    }
}
```

`case-management-engine-remote/src/test/resources/application.yaml`:

```yaml
spring:
  datasource:
    url: jdbc:h2:mem:remote-engine;DB_CLOSE_DELAY=-1
    driver-class-name: org.h2.Driver
casemgmt:
  enabled: false      # this app is the engine only
```

Add test-scoped `operaton-bpm-spring-boot-starter-rest`, `com.h2database:h2` and `spring-boot-starter-test` to this module.

- [ ] **Step 2: Run it and watch it fail**

Run: `./mvnw -q -pl case-management-engine-remote test`
Expected: FAIL — `cannot find symbol: class RemoteEngineGateway`.

- [ ] **Step 3: Write the gateway**

```java
package org.casemgmt.engine.remote;

import org.casemgmt.engine.*;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * Talks to a remote Operaton over engine-rest (spec §3.5 remote mode).
 *
 * Calls here are NOT in the case transaction. Callers must therefore reach this
 * class through the command outbox (Task 13), never directly from a request thread.
 */
public class RemoteEngineGateway implements EngineGateway {

    public static final String CASE_ID_VARIABLE = "caseId";
    private static final String PLAN_ITEM_VARIABLE = "planItemId";

    private final RestClient client;

    public RemoteEngineGateway(RestClient client) {
        this.client = client;
    }

    @Override
    public EngineTaskRef createHumanTask(HumanTaskRequest request) {
        String taskId = UUID.randomUUID().toString();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", taskId);
        body.put("name", request.name());
        if (request.assignee() != null) {
            body.put("assignee", request.assignee());
        }
        post("/task/create", body);

        if (request.candidateGroups() != null) {
            for (String group : request.candidateGroups()) {
                post("/task/" + taskId + "/identity-links",
                        Map.of("groupId", group, "type", "candidate"));
            }
        }
        Map<String, Object> variables = new LinkedHashMap<>(
                request.variables() == null ? Map.of() : request.variables());
        variables.put(CASE_ID_VARIABLE, request.caseId());
        variables.put(PLAN_ITEM_VARIABLE, request.planItemId());
        post("/task/" + taskId + "/variables", Map.of("modifications", typed(variables)));

        return new EngineTaskRef(taskId, request.name(), request.assignee(),
                request.caseId(), OffsetDateTime.now());
    }

    @Override
    public void claimTask(String engineTaskId, String userId) {
        post("/task/" + engineTaskId + "/claim", Map.of("userId", userId));
    }

    @Override
    public void completeTask(String engineTaskId, Map<String, Object> variables) {
        post("/task/" + engineTaskId + "/complete",
                Map.of("variables", typed(variables == null ? Map.of() : variables)));
    }

    @Override
    public EngineProcessRef startProcess(StartProcessRequest request) {
        Map<String, Object> variables = new LinkedHashMap<>(
                request.variables() == null ? Map.of() : request.variables());
        variables.put(CASE_ID_VARIABLE, request.caseId());
        variables.put(PLAN_ITEM_VARIABLE, request.planItemId());

        Map<String, Object> response = post(
                "/process-definition/key/" + request.processDefinitionKey() + "/start",
                Map.of("businessKey", request.caseId(), "variables", typed(variables)));

        return new EngineProcessRef(String.valueOf(response.get("id")), request.processDefinitionKey());
    }

    @Override
    public void cancelProcess(String processInstanceId, String reason) {
        try {
            client.delete()
                    .uri("/process-instance/{id}?skipCustomListeners=false", processInstanceId)
                    .retrieve().toBodilessEntity();
        } catch (RestClientResponseException e) {
            throw new EngineException("Could not cancel process " + processInstanceId
                    + ": " + e.getStatusCode(), e);
        }
    }

    @Override
    public List<EngineTaskRef> findTasks(EngineTaskQuery query) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (query.assignee() != null) {
            body.put("assignee", query.assignee());
        }
        if (query.candidateGroups() != null && !query.candidateGroups().isEmpty()) {
            body.put("candidateGroups", query.candidateGroups());
            body.put("includeAssignedTasks", true);
        }
        if (query.caseId() != null) {
            body.put("taskVariables", List.of(Map.of(
                    "name", CASE_ID_VARIABLE, "value", query.caseId(), "operator", "eq")));
        }
        try {
            List<Map<String, Object>> tasks = client.post()
                    .uri("/task?maxResults={max}", query.maxResults() <= 0 ? 50 : query.maxResults())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(List.class);

            return tasks == null ? List.of() : tasks.stream()
                    .map(t -> new EngineTaskRef(
                            String.valueOf(t.get("id")),
                            (String) t.get("name"),
                            (String) t.get("assignee"),
                            query.caseId(),
                            null))
                    .toList();
        } catch (RestClientResponseException e) {
            throw new EngineException("Task query failed: " + e.getStatusCode(), e);
        }
    }

    private Map<String, Object> post(String path, Object body) {
        try {
            return client.post().uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(Map.class);
        } catch (RestClientResponseException e) {
            throw new EngineException("Engine call " + path + " failed: "
                    + e.getStatusCode() + " " + e.getResponseBodyAsString(), e);
        }
    }

    /** engine-rest wants {"name": {"value": v, "type": "String"}} rather than plain values. */
    private Map<String, Object> typed(Map<String, Object> variables) {
        Map<String, Object> typed = new LinkedHashMap<>();
        variables.forEach((k, v) -> typed.put(k, Map.of(
                "value", v == null ? "" : v,
                "type", switch (v) {
                    case Integer i -> "Integer";
                    case Long l -> "Long";
                    case Boolean b -> "Boolean";
                    case Double d -> "Double";
                    case null, default -> "String";
                })));
        return typed;
    }
}
```

- [ ] **Step 4: Run the contract to verify it passes**

Run: `./mvnw -q -pl case-management-engine-remote test`
Expected: PASS — the same eight contract tests that pass for the embedded gateway.

Expect friction here; this is where R2 gets tested for real. Two likely divergences, both worth recording in `FINDINGS.md` rather than papering over:

1. `POST /task/create` may not accept a client-supplied `id`. If so, create without an id, read the location header or query back by name, and adjust `createHumanTask`.
2. The `caseId` task-variable query may behave differently over REST than in-process. If the contract's `findsTasksByCaseId` fails only in remote mode, that is a genuine finding about federation, not a test bug.

- [ ] **Step 5: Commit**

```bash
git add case-management-engine-remote/
git commit -m "feat(engine): remote engine-rest gateway passing the contract suite"
```

---

### Task 13: Engine command outbox for remote mode

**Files:**
- Create: `case-management-core/src/main/java/org/casemgmt/engine/EngineCommand.java`
- Create: `case-management-core/src/main/java/org/casemgmt/repo/EngineCommandRepository.java`
- Create: `case-management-core/src/main/java/org/casemgmt/engine/OutboxEngineGateway.java`
- Create: `case-management-core/src/main/java/org/casemgmt/engine/EngineCommandDispatcher.java`
- Create: `case-management-core/src/test/java/org/casemgmt/engine/EngineCommandDispatcherTest.java`

**Interfaces:**
- Consumes: `EngineGateway` + DTOs (Task 10); `CaseTaskRepository.markSync` (Task 6)
- Produces:
  - `EngineCommand(String id, String caseId, Type type, Map<String,Object> payload, String status, int attempts, OffsetDateTime nextAttemptAt, String lastError)` with `Type` = `CREATE_TASK | CLAIM_TASK | COMPLETE_TASK | START_PROCESS | CANCEL_PROCESS`
  - `EngineCommandRepository.enqueue(EngineCommand)`, `.claimDue(int limit) : List<EngineCommand>`, `.markDone(String id)`, `.markRetry(String id, String error, OffsetDateTime nextAttempt)`, `.markDead(String id, String error)`
  - `OutboxEngineGateway implements EngineGateway` — enqueues instead of calling; returns refs with locally-minted ids
  - `EngineCommandDispatcher.drainOnce() : int` — returns the number of commands processed

**Backoff:** attempts 1–5 at 1m, 5m, 25m, 2h, 10h, then `DEAD`. Identical policy to the webhook dispatcher (Task 20) — one constant shared by both.

- [ ] **Step 1: Write the failing test**

```java
package org.casemgmt.engine;

import org.casemgmt.OracleTestBase;
import org.casemgmt.domain.CaseTask;
import org.casemgmt.repo.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class EngineCommandDispatcherTest extends OracleTestBase {

    private EngineCommandRepository commands;

    @BeforeEach
    void setUp() {
        jdbc().sql("DELETE FROM CM_ENGINE_COMMAND").update();
        commands = new EngineCommandRepository(jdbc());
    }

    @Test
    void outboxGatewayEnqueuesInsteadOfCalling() {
        var outbox = new OutboxEngineGateway(commands, id -> {});

        EngineTaskRef ref = outbox.createHumanTask(new HumanTaskRequest(
                "eng-a:1", "pi-1", "Review", null, List.of("g"), null, Map.of()));

        // No engine id yet: the dispatcher supplies it after the engine confirms.
        assertThat(ref.engineTaskId()).isNull();
        assertThat(commands.claimDue(10)).hasSize(1)
                .allSatisfy(c -> assertThat(c.type()).isEqualTo(EngineCommand.Type.CREATE_TASK));
    }

    @Test
    void outboxGatewayNeverTouchesTheEngineOnTheRequestThread() {
        // ExplodingGateway fails the test if any engine call happens synchronously.
        var outbox = new OutboxEngineGateway(commands, id -> {});
        outbox.createHumanTask(new HumanTaskRequest("eng-a:9", "pi-9", "Review",
                null, List.of("g"), null, Map.of()));
        outbox.completeTask("engine-1", Map.of());
        outbox.cancelProcess("proc-1", "reason");

        // Nothing was delivered because no dispatcher ran.
        assertThat(new EngineCommandDispatcher(commands, new ExplodingGateway(), (t, s, e) -> {}))
                .isNotNull();
        assertThat(commands.claimDue(10)).hasSize(3);
    }

    static class ExplodingGateway extends RecordingGateway {
        @Override public EngineTaskRef createHumanTask(HumanTaskRequest r) {
            throw new AssertionError("engine must not be called from the request thread");
        }
        @Override public void completeTask(String id, Map<String, Object> v) {
            throw new AssertionError("engine must not be called from the request thread");
        }
        @Override public void cancelProcess(String id, String reason) {
            throw new AssertionError("engine must not be called from the request thread");
        }
    }

    @Test
    void dispatcherDeliversAndMarksTheTaskSynced() {
        var syncedTasks = new java.util.ArrayList<String>();
        var outbox = new OutboxEngineGateway(commands, syncedTasks::add);
        outbox.createHumanTask(new HumanTaskRequest("eng-a:2", "pi-2", "Review",
                null, List.of(), null, Map.of()));

        var delegate = new RecordingGateway();
        int processed = new EngineCommandDispatcher(commands, delegate, (taskId, sync, engineId) ->
                syncedTasks.add(taskId)).drainOnce();

        assertThat(processed).isEqualTo(1);
        assertThat(delegate.createdTasks).hasSize(1);
        assertThat(commands.claimDue(10)).isEmpty();
    }

    @Test
    void failedCommandsAreRetriedWithBackoffThenParkedAsDead() {
        var outbox = new OutboxEngineGateway(commands, id -> {});
        outbox.createHumanTask(new HumanTaskRequest("eng-a:3", "pi-3", "Fails",
                null, List.of(), null, Map.of()));

        var failing = new FailingGateway();
        var dispatcher = new EngineCommandDispatcher(commands, failing, (t, s, e) -> {});

        for (int attempt = 1; attempt <= 6; attempt++) {
            jdbc().sql("UPDATE CM_ENGINE_COMMAND SET NEXT_ATTEMPT_AT_ = SYSTIMESTAMP - INTERVAL '1' HOUR").update();
            dispatcher.drainOnce();
        }

        String status = jdbc().sql("SELECT STATUS_ FROM CM_ENGINE_COMMAND")
                .query(String.class).single();
        assertThat(status).isEqualTo("DEAD");
    }

    static class RecordingGateway implements EngineGateway {
        final java.util.List<HumanTaskRequest> createdTasks = new java.util.ArrayList<>();
        public EngineTaskRef createHumanTask(HumanTaskRequest r) {
            createdTasks.add(r);
            return new EngineTaskRef("engine-" + createdTasks.size(), r.name(), r.assignee(), r.caseId(), null);
        }
        public void claimTask(String id, String user) {}
        public void completeTask(String id, Map<String, Object> vars) {}
        public EngineProcessRef startProcess(StartProcessRequest r) {
            return new EngineProcessRef("proc-1", r.processDefinitionKey());
        }
        public void cancelProcess(String id, String reason) {}
        public List<EngineTaskRef> findTasks(EngineTaskQuery q) { return List.of(); }
    }

    static class FailingGateway extends RecordingGateway {
        @Override public EngineTaskRef createHumanTask(HumanTaskRequest r) {
            throw new EngineException("engine is down");
        }
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./mvnw -q -pl case-management-core test -Dtest=EngineCommandDispatcherTest`
Expected: FAIL — `cannot find symbol: class EngineCommand`.

- [ ] **Step 3: Write the command, repository and backoff policy**

```java
package org.casemgmt.engine;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public record EngineCommand(String id, String caseId, Type type, Map<String, Object> payload,
                            String status, int attempts, OffsetDateTime nextAttemptAt, String lastError) {

    public enum Type { CREATE_TASK, CLAIM_TASK, COMPLETE_TASK, START_PROCESS, CANCEL_PROCESS }

    /** Shared with the webhook dispatcher: 1m, 5m, 25m, 2h, 10h, then dead. */
    public static final List<Duration> BACKOFF = List.of(
            Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(25),
            Duration.ofHours(2), Duration.ofHours(10));

    public static boolean exhausted(int attempts) {
        return attempts >= BACKOFF.size();
    }

    public static OffsetDateTime nextAttempt(int attempts) {
        return OffsetDateTime.now().plus(BACKOFF.get(Math.min(attempts, BACKOFF.size() - 1)));
    }
}
```

```java
package org.casemgmt.repo;

import org.casemgmt.engine.EngineCommand;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.OffsetDateTime;
import java.util.List;

public class EngineCommandRepository {

    private final JdbcClient jdbc;

    public EngineCommandRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public void enqueue(EngineCommand c) {
        jdbc.sql("""
                INSERT INTO CM_ENGINE_COMMAND (ID_, CASE_ID_, TYPE_, PAYLOAD_JSON_, STATUS_,
                    ATTEMPTS_, NEXT_ATTEMPT_AT_)
                VALUES (:id, :caseId, :type, :payload, 'PENDING', 0, SYSTIMESTAMP)""")
            .param("id", c.id()).param("caseId", c.caseId()).param("type", c.type().name())
            .param("payload", JsonCodec.toJson(c.payload()))
            .update();
    }

    /** Claims due commands. SKIP LOCKED keeps multiple app instances from double-sending. */
    public List<EngineCommand> claimDue(int limit) {
        return jdbc.sql("""
                SELECT ID_, CASE_ID_, TYPE_, PAYLOAD_JSON_, STATUS_, ATTEMPTS_, NEXT_ATTEMPT_AT_, LAST_ERROR_
                FROM CM_ENGINE_COMMAND
                WHERE STATUS_ IN ('PENDING','RETRYING') AND NEXT_ATTEMPT_AT_ <= SYSTIMESTAMP
                ORDER BY CREATED_AT_
                FETCH FIRST :limit ROWS ONLY
                FOR UPDATE SKIP LOCKED""")
            .param("limit", limit)
            .query((rs, n) -> new EngineCommand(rs.getString("ID_"), rs.getString("CASE_ID_"),
                    EngineCommand.Type.valueOf(rs.getString("TYPE_")),
                    JsonCodec.toMap(rs.getString("PAYLOAD_JSON_")),
                    rs.getString("STATUS_"), rs.getInt("ATTEMPTS_"),
                    rs.getObject("NEXT_ATTEMPT_AT_", OffsetDateTime.class),
                    rs.getString("LAST_ERROR_")))
            .list();
    }

    public void markDone(String id) {
        jdbc.sql("UPDATE CM_ENGINE_COMMAND SET STATUS_ = 'DONE' WHERE ID_ = :id")
            .param("id", id).update();
    }

    public void markRetry(String id, String error, OffsetDateTime nextAttempt) {
        jdbc.sql("""
                UPDATE CM_ENGINE_COMMAND SET STATUS_ = 'RETRYING', ATTEMPTS_ = ATTEMPTS_ + 1,
                    LAST_ERROR_ = :error, NEXT_ATTEMPT_AT_ = :next
                WHERE ID_ = :id""")
            .param("error", truncate(error)).param("next", nextAttempt).param("id", id).update();
    }

    public void markDead(String id, String error) {
        jdbc.sql("""
                UPDATE CM_ENGINE_COMMAND SET STATUS_ = 'DEAD', ATTEMPTS_ = ATTEMPTS_ + 1,
                    LAST_ERROR_ = :error
                WHERE ID_ = :id""")
            .param("error", truncate(error)).param("id", id).update();
    }

    private static String truncate(String s) {
        return s == null ? null : s.length() > 1990 ? s.substring(0, 1990) : s;
    }
}
```

- [ ] **Step 4: Write the outbox gateway and dispatcher**

```java
package org.casemgmt.engine;

import org.casemgmt.domain.CaseIds;
import org.casemgmt.repo.EngineCommandRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Remote-mode gateway (spec §3.5). Writes a command row in the caller's transaction
 * instead of calling the engine, so a rolled-back case change never leaves an orphan
 * task on a remote engine. The returned ids are locally minted; the dispatcher
 * reconciles them once the engine confirms.
 */
public class OutboxEngineGateway implements EngineGateway {

    private final EngineCommandRepository commands;
    private final Consumer<String> onEnqueued;

    public OutboxEngineGateway(EngineCommandRepository commands, Consumer<String> onEnqueued) {
        this.commands = commands;
        this.onEnqueued = onEnqueued;
    }

    @Override
    public EngineTaskRef createHumanTask(HumanTaskRequest request) {
        String commandId = CaseIds.newId();
        commands.enqueue(new EngineCommand(commandId, request.caseId(),
                EngineCommand.Type.CREATE_TASK,
                Map.of("planItemId", request.planItemId(), "name", request.name(),
                        "assignee", request.assignee() == null ? "" : request.assignee(),
                        "candidateGroups", request.candidateGroups(),
                        "formKey", request.formKey() == null ? "" : request.formKey(),
                        "variables", request.variables()),
                "PENDING", 0, OffsetDateTime.now(), null));
        onEnqueued.accept(commandId);
        return new EngineTaskRef(null, request.name(), request.assignee(),
                request.caseId(), OffsetDateTime.now());
    }

    @Override
    public void claimTask(String engineTaskId, String userId) {
        enqueue(EngineCommand.Type.CLAIM_TASK, null,
                Map.of("engineTaskId", engineTaskId, "userId", userId));
    }

    @Override
    public void completeTask(String engineTaskId, Map<String, Object> variables) {
        enqueue(EngineCommand.Type.COMPLETE_TASK, null,
                Map.of("engineTaskId", engineTaskId, "variables", variables == null ? Map.of() : variables));
    }

    @Override
    public EngineProcessRef startProcess(StartProcessRequest request) {
        enqueue(EngineCommand.Type.START_PROCESS, request.caseId(),
                Map.of("planItemId", request.planItemId(),
                        "processDefinitionKey", request.processDefinitionKey(),
                        "variables", request.variables() == null ? Map.of() : request.variables()));
        return new EngineProcessRef(null, request.processDefinitionKey());
    }

    @Override
    public void cancelProcess(String processInstanceId, String reason) {
        enqueue(EngineCommand.Type.CANCEL_PROCESS, null,
                Map.of("processInstanceId", processInstanceId, "reason", reason == null ? "" : reason));
    }

    /**
     * Queries are NOT deferred: reading a remote engine synchronously is safe, and the
     * worklist is served from CM_TASK anyway.
     */
    @Override
    public List<EngineTaskRef> findTasks(EngineTaskQuery query) {
        return List.of();
    }

    private void enqueue(EngineCommand.Type type, String caseId, Map<String, Object> payload) {
        String id = CaseIds.newId();
        commands.enqueue(new EngineCommand(id, caseId == null ? "" : caseId, type, payload,
                "PENDING", 0, OffsetDateTime.now(), null));
        onEnqueued.accept(id);
    }
}
```

```java
package org.casemgmt.engine;

import org.casemgmt.domain.CaseTask;
import org.casemgmt.repo.EngineCommandRepository;

import java.util.List;
import java.util.Map;

/**
 * Drains the engine command outbox against the real (remote) gateway and reports the
 * resulting sync state back onto CM_TASK, so availableActions can withhold `claim`
 * until the engine actually has the task.
 */
public class EngineCommandDispatcher {

    /** Callback: (caseTaskId or planItemId, sync state, engine id). */
    public interface SyncReporter {
        void report(String taskOrPlanItemId, CaseTask.EngineSync sync, String engineId);
    }

    private final EngineCommandRepository commands;
    private final EngineGateway delegate;
    private final SyncReporter syncReporter;

    public EngineCommandDispatcher(EngineCommandRepository commands, EngineGateway delegate,
                                   SyncReporter syncReporter) {
        this.commands = commands;
        this.delegate = delegate;
        this.syncReporter = syncReporter;
    }

    public int drainOnce() {
        List<EngineCommand> due = commands.claimDue(50);
        for (EngineCommand command : due) {
            try {
                execute(command);
                commands.markDone(command.id());
            } catch (RuntimeException e) {
                if (EngineCommand.exhausted(command.attempts())) {
                    commands.markDead(command.id(), e.getMessage());
                    reportFailure(command);
                } else {
                    commands.markRetry(command.id(), e.getMessage(),
                            EngineCommand.nextAttempt(command.attempts()));
                }
            }
        }
        return due.size();
    }

    private void execute(EngineCommand command) {
        Map<String, Object> p = command.payload();
        switch (command.type()) {
            case CREATE_TASK -> {
                EngineTaskRef ref = delegate.createHumanTask(new HumanTaskRequest(
                        command.caseId(), str(p, "planItemId"), str(p, "name"),
                        blankToNull(str(p, "assignee")), strings(p.get("candidateGroups")),
                        blankToNull(str(p, "formKey")), map(p.get("variables"))));
                syncReporter.report(str(p, "planItemId"), CaseTask.EngineSync.SYNCED, ref.engineTaskId());
            }
            case CLAIM_TASK -> delegate.claimTask(str(p, "engineTaskId"), str(p, "userId"));
            case COMPLETE_TASK -> delegate.completeTask(str(p, "engineTaskId"), map(p.get("variables")));
            case START_PROCESS -> {
                EngineProcessRef ref = delegate.startProcess(new StartProcessRequest(
                        command.caseId(), str(p, "planItemId"),
                        str(p, "processDefinitionKey"), map(p.get("variables"))));
                syncReporter.report(str(p, "planItemId"), CaseTask.EngineSync.SYNCED,
                        ref.processInstanceId());
            }
            case CANCEL_PROCESS -> delegate.cancelProcess(str(p, "processInstanceId"), str(p, "reason"));
        }
    }

    private void reportFailure(EngineCommand command) {
        Object planItemId = command.payload().get("planItemId");
        if (planItemId != null) {
            syncReporter.report(planItemId.toString(), CaseTask.EngineSync.FAILED, null);
        }
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? null : v.toString();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<String> strings(Object o) {
        return o instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of();
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./mvnw -q -pl case-management-core test -Dtest=EngineCommandDispatcherTest`
Expected: PASS, all three tests.

- [ ] **Step 6: Commit**

```bash
git add case-management-core/src
git commit -m "feat(engine): command outbox and dispatcher for remote mode"
```

---

## Phase 4 — Events and services

### Task 14: Transactional outbox — events and audit

**Files:**
- Create: `case-management-core/src/main/java/org/casemgmt/event/CaseEvent.java`
- Create: `case-management-core/src/main/java/org/casemgmt/event/EventTypes.java`
- Create: `case-management-core/src/main/java/org/casemgmt/repo/EventRepository.java`
- Create: `case-management-core/src/main/java/org/casemgmt/repo/AuditRepository.java`
- Create: `case-management-core/src/main/java/org/casemgmt/event/EventPublisher.java`
- Create: `case-management-core/src/test/java/org/casemgmt/event/EventPublisherTest.java`

**Interfaces:**
- Consumes: `JsonCodec` (Task 4); `CaseIds` (Task 3)
- Produces:
  - `CaseEvent(String id, String source, String type, String subject, String tenantId, OffsetDateTime time, Map<String,Object> data)` plus `CaseEvent.toCloudEvent() : Map<String,Object>` (CloudEvents 1.0 structured JSON)
  - `EventTypes.CASE_CREATED = "case.created"` … (suffixes only; the configured prefix is prepended by `EventPublisher`)
  - `EventRepository.append(CaseEvent) : long` (returns `SEQ_`), `.after(long cursor, int limit) : List<StoredEvent>`, `.forCase(String caseId, long cursor, int limit) : List<StoredEvent>` where `StoredEvent(long seq, CaseEvent event)`
  - `AuditRepository.record(String caseId, String tenantId, String actor, String action, String resourceType, String resourceId, Object before, Object after)`
  - `EventPublisher.publish(CaseEvent) : long` — appends the event **and** fans out one `CM_WEBHOOK_DELIVERY` row per matching active subscription, in the caller's transaction
  - `EventPublisher.audit(...)` — delegates to `AuditRepository`

- [ ] **Step 1: Write the failing test**

```java
package org.casemgmt.event;

import org.casemgmt.OracleTestBase;
import org.casemgmt.repo.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EventPublisherTest extends OracleTestBase {

    private EventPublisher publisher;
    private EventRepository events;

    @BeforeEach
    void setUp() {
        jdbc().sql("DELETE FROM CM_WEBHOOK_DELIVERY").update();
        jdbc().sql("DELETE FROM CM_WEBHOOK_SUB").update();
        jdbc().sql("DELETE FROM CM_EVENT").update();
        jdbc().sql("DELETE FROM CM_AUDIT_LOG").update();
        events = new EventRepository(jdbc());
        publisher = new EventPublisher(events, new AuditRepository(jdbc()),
                new WebhookRepository(jdbc()), "org.example.cm", "eng-a");
    }

    private CaseEvent event(String type, String subject) {
        return new CaseEvent(org.casemgmt.domain.CaseIds.newId(), "eng-a", type, subject,
                "t1", OffsetDateTime.now(), Map.of("state", "ACTIVE"));
    }

    @Test
    void appendsEventsWithAMonotonicCursor() {
        long first = publisher.publish(event("case.created", "eng-a:1"));
        long second = publisher.publish(event("case.updated", "eng-a:1"));

        assertThat(second).isGreaterThan(first);
        assertThat(events.after(0, 10)).hasSize(2);
        assertThat(events.after(first, 10)).hasSize(1);
    }

    @Test
    void prependsTheConfiguredTypePrefix() {
        publisher.publish(event("case.created", "eng-a:1"));

        assertThat(events.after(0, 10)).singleElement()
                .satisfies(e -> assertThat(e.event().type()).isEqualTo("org.example.cm.case.created"));
    }

    @Test
    void filtersPerCaseEventLogsBySubject() {
        publisher.publish(event("case.created", "eng-a:1"));
        publisher.publish(event("case.created", "eng-a:2"));

        assertThat(events.forCase("eng-a:2", 0, 10)).hasSize(1);
    }

    @Test
    void fansOutOneDeliveryPerMatchingSubscription() {
        new WebhookRepository(jdbc()).insert("w-1", "t1", "http://localhost/hook",
                List.of("org.example.cm.case.created"), "hash", 8);
        new WebhookRepository(jdbc()).insert("w-2", "t1", "http://localhost/other",
                List.of("org.example.cm.case.closed"), "hash", 8);

        publisher.publish(event("case.created", "eng-a:1"));

        Integer deliveries = jdbc().sql("SELECT COUNT(*) FROM CM_WEBHOOK_DELIVERY")
                .query(Integer.class).single();
        assertThat(deliveries).isEqualTo(1);
    }

    @Test
    void wildcardSubscriptionsMatchEveryType() {
        new WebhookRepository(jdbc()).insert("w-3", "t1", "http://localhost/all",
                List.of("*"), "hash", 8);

        publisher.publish(event("case.created", "eng-a:1"));
        publisher.publish(event("case.closed", "eng-a:1"));

        Integer deliveries = jdbc().sql("SELECT COUNT(*) FROM CM_WEBHOOK_DELIVERY")
                .query(Integer.class).single();
        assertThat(deliveries).isEqualTo(2);
    }

    @Test
    void writesAuditRowsWithBeforeAndAfterImages() {
        publisher.audit("eng-a:1", "t1", "alice", "case.close", "Case", "eng-a:1",
                Map.of("state", "ACTIVE"), Map.of("state", "CLOSED"));

        String after = jdbc().sql("SELECT AFTER_JSON_ FROM CM_AUDIT_LOG").query(String.class).single();
        assertThat(after).contains("CLOSED");
    }

    @Test
    void cloudEventEnvelopeCarriesTheRequiredAttributes() {
        Map<String, Object> envelope = event("case.created", "eng-a:1").toCloudEvent();

        assertThat(envelope).containsKeys("specversion", "id", "source", "type", "subject", "time", "data");
        assertThat(envelope.get("specversion")).isEqualTo("1.0");
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./mvnw -q -pl case-management-core test -Dtest=EventPublisherTest`
Expected: FAIL — `cannot find symbol: class EventPublisher`.

- [ ] **Step 3: Write the event record and type constants**

```java
package org.casemgmt.event;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

public record CaseEvent(String id, String source, String type, String subject,
                        String tenantId, OffsetDateTime time, Map<String, Object> data) {

    /** CloudEvents 1.0, structured JSON mode (spec §6.2). */
    public Map<String, Object> toCloudEvent() {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("specversion", "1.0");
        envelope.put("id", id);
        envelope.put("source", source);
        envelope.put("type", type);
        envelope.put("subject", subject);
        envelope.put("time", time.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        envelope.put("datacontenttype", "application/json");
        if (tenantId != null) {
            envelope.put("tenantid", tenantId);
        }
        envelope.put("data", data);
        return envelope;
    }

    public CaseEvent withType(String fullType) {
        return new CaseEvent(id, source, fullType, subject, tenantId, time, data);
    }
}
```

```java
package org.casemgmt.event;

/** Type suffixes. EventPublisher prepends casemgmt.events.type-prefix. */
public final class EventTypes {

    public static final String CASE_CREATED = "case.created";
    public static final String CASE_UPDATED = "case.updated";
    public static final String CASE_CLOSED = "case.closed";
    public static final String CASE_CANCELLED = "case.cancelled";
    public static final String PLAN_ITEM_TRANSITIONED = "case.planitem.transitioned";
    public static final String TASK_CREATED = "case.task.created";
    public static final String TASK_CLAIMED = "case.task.claimed";
    public static final String TASK_COMPLETED = "case.task.completed";
    public static final String MILESTONE_ACHIEVED = "case.milestone.achieved";
    public static final String COMMENT_ADDED = "case.comment.added";
    public static final String PROCESS_STARTED = "case.process.started";
    public static final String SLA_WARNING = "case.sla.warning";
    public static final String SLA_BREACHED = "case.sla.breached";

    private EventTypes() {}
}
```

- [ ] **Step 4: Write the repositories**

```java
package org.casemgmt.repo;

import org.casemgmt.event.CaseEvent;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.OffsetDateTime;
import java.util.List;

public class EventRepository {

    public record StoredEvent(long seq, CaseEvent event) {}

    private final JdbcClient jdbc;

    public EventRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public long append(CaseEvent e) {
        long seq = jdbc.sql("SELECT CM_EVENT_SEQ.NEXTVAL FROM DUAL").query(Long.class).single();
        jdbc.sql("""
                INSERT INTO CM_EVENT (SEQ_, ID_, SOURCE_, TYPE_, SUBJECT_, TENANT_ID_, TIME_, DATA_JSON_)
                VALUES (:seq, :id, :source, :type, :subject, :tenant, :time, :data)""")
            .param("seq", seq).param("id", e.id()).param("source", e.source())
            .param("type", e.type()).param("subject", e.subject()).param("tenant", e.tenantId())
            .param("time", e.time()).param("data", JsonCodec.toJson(e.data()))
            .update();
        return seq;
    }

    public List<StoredEvent> after(long cursor, int limit) {
        return jdbc.sql("""
                SELECT SEQ_, ID_, SOURCE_, TYPE_, SUBJECT_, TENANT_ID_, TIME_, DATA_JSON_
                FROM CM_EVENT WHERE SEQ_ > :cursor ORDER BY SEQ_ FETCH FIRST :limit ROWS ONLY""")
            .param("cursor", cursor).param("limit", limit)
            .query(EventRepository::map).list();
    }

    public List<StoredEvent> forCase(String caseId, long cursor, int limit) {
        return jdbc.sql("""
                SELECT SEQ_, ID_, SOURCE_, TYPE_, SUBJECT_, TENANT_ID_, TIME_, DATA_JSON_
                FROM CM_EVENT WHERE SUBJECT_ = :caseId AND SEQ_ > :cursor
                ORDER BY SEQ_ FETCH FIRST :limit ROWS ONLY""")
            .param("caseId", caseId).param("cursor", cursor).param("limit", limit)
            .query(EventRepository::map).list();
    }

    private static StoredEvent map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new StoredEvent(rs.getLong("SEQ_"), new CaseEvent(
                rs.getString("ID_"), rs.getString("SOURCE_"), rs.getString("TYPE_"),
                rs.getString("SUBJECT_"), rs.getString("TENANT_ID_"),
                rs.getObject("TIME_", OffsetDateTime.class),
                JsonCodec.toMap(rs.getString("DATA_JSON_"))));
    }
}
```

```java
package org.casemgmt.repo;

import org.casemgmt.domain.CaseIds;
import org.springframework.jdbc.core.simple.JdbcClient;

public class AuditRepository {

    private final JdbcClient jdbc;

    public AuditRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** Append-only compliance record. Separate from CM_EVENT: different retention, different audience. */
    public void record(String caseId, String tenantId, String actor, String action,
                       String resourceType, String resourceId, Object before, Object after) {
        jdbc.sql("""
                INSERT INTO CM_AUDIT_LOG (ID_, CASE_ID_, TENANT_ID_, ACTOR_, ACTION_,
                    RESOURCE_TYPE_, RESOURCE_ID_, BEFORE_JSON_, AFTER_JSON_)
                VALUES (:id, :caseId, :tenant, :actor, :action, :type, :resourceId, :before, :after)""")
            .param("id", CaseIds.newId()).param("caseId", caseId).param("tenant", tenantId)
            .param("actor", actor).param("action", action).param("type", resourceType)
            .param("resourceId", resourceId)
            .param("before", JsonCodec.toJson(before)).param("after", JsonCodec.toJson(after))
            .update();
    }
}
```

```java
package org.casemgmt.repo;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;

public class WebhookRepository {

    public record Subscription(String id, String tenantId, String url, List<String> eventTypes,
                               String secretHash, int maxRetries, boolean active, long version) {}

    private final JdbcClient jdbc;

    public WebhookRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public void insert(String id, String tenantId, String url, List<String> eventTypes,
                       String secretHash, int maxRetries) {
        jdbc.sql("""
                INSERT INTO CM_WEBHOOK_SUB (ID_, TENANT_ID_, URL_, EVENT_TYPES_JSON_, ACTIVE_,
                    SECRET_HASH_, MAX_RETRIES_, VERSION_)
                VALUES (:id, :tenant, :url, :types, 1, :hash, :retries, 0)""")
            .param("id", id).param("tenant", tenantId).param("url", url)
            .param("types", JsonCodec.toJson(eventTypes)).param("hash", secretHash)
            .param("retries", maxRetries)
            .update();
    }

    public List<Subscription> active(String tenantId) {
        return jdbc.sql("""
                SELECT ID_, TENANT_ID_, URL_, EVENT_TYPES_JSON_, SECRET_HASH_, MAX_RETRIES_,
                       ACTIVE_, VERSION_
                FROM CM_WEBHOOK_SUB
                WHERE ACTIVE_ = 1 AND (TENANT_ID_ IS NULL OR TENANT_ID_ = :tenant)""")
            .param("tenant", tenantId)
            .query((rs, n) -> new Subscription(rs.getString("ID_"), rs.getString("TENANT_ID_"),
                    rs.getString("URL_"), JsonCodec.toList(rs.getString("EVENT_TYPES_JSON_")),
                    rs.getString("SECRET_HASH_"), rs.getInt("MAX_RETRIES_"),
                    rs.getInt("ACTIVE_") == 1, rs.getLong("VERSION_")))
            .list();
    }

    public List<Subscription> all() {
        return jdbc.sql("""
                SELECT ID_, TENANT_ID_, URL_, EVENT_TYPES_JSON_, SECRET_HASH_, MAX_RETRIES_,
                       ACTIVE_, VERSION_
                FROM CM_WEBHOOK_SUB ORDER BY CREATED_AT_""")
            .query((rs, n) -> new Subscription(rs.getString("ID_"), rs.getString("TENANT_ID_"),
                    rs.getString("URL_"), JsonCodec.toList(rs.getString("EVENT_TYPES_JSON_")),
                    rs.getString("SECRET_HASH_"), rs.getInt("MAX_RETRIES_"),
                    rs.getInt("ACTIVE_") == 1, rs.getLong("VERSION_")))
            .list();
    }

    public void enqueueDelivery(String id, String webhookId, long eventSeq) {
        jdbc.sql("""
                INSERT INTO CM_WEBHOOK_DELIVERY (ID_, WEBHOOK_ID_, EVENT_SEQ_, STATUS_, ATTEMPTS_,
                    NEXT_ATTEMPT_AT_)
                VALUES (:id, :webhookId, :seq, 'PENDING', 0, SYSTIMESTAMP)""")
            .param("id", id).param("webhookId", webhookId).param("seq", eventSeq)
            .update();
    }
}
```

- [ ] **Step 5: Write the publisher**

```java
package org.casemgmt.event;

import org.casemgmt.domain.CaseIds;
import org.casemgmt.repo.AuditRepository;
import org.casemgmt.repo.EventRepository;
import org.casemgmt.repo.WebhookRepository;

/**
 * The transactional outbox (spec §6.1). Everything here runs in the caller's
 * transaction: no HTTP, no thread hand-off. A rolled-back mutation emits nothing.
 */
public class EventPublisher {

    private final EventRepository events;
    private final AuditRepository audit;
    private final WebhookRepository webhooks;
    private final String typePrefix;
    private final String engineId;

    public EventPublisher(EventRepository events, AuditRepository audit, WebhookRepository webhooks,
                          String typePrefix, String engineId) {
        if (typePrefix == null || typePrefix.isBlank()) {
            throw new IllegalArgumentException(
                    "casemgmt.events.type-prefix must be set — there is no safe default");
        }
        this.events = events;
        this.audit = audit;
        this.webhooks = webhooks;
        this.typePrefix = typePrefix;
        this.engineId = engineId;
    }

    public long publish(CaseEvent event) {
        CaseEvent stamped = event.withType(typePrefix + "." + event.type());
        long seq = events.append(stamped);

        for (WebhookRepository.Subscription sub : webhooks.active(stamped.tenantId())) {
            if (matches(sub.eventTypes(), stamped.type())) {
                webhooks.enqueueDelivery(CaseIds.newId(), sub.id(), seq);
            }
        }
        return seq;
    }

    public void audit(String caseId, String tenantId, String actor, String action,
                      String resourceType, String resourceId, Object before, Object after) {
        audit.record(caseId, tenantId, actor, action, resourceType, resourceId, before, after);
    }

    public String engineId() {
        return engineId;
    }

    private boolean matches(java.util.List<String> patterns, String type) {
        return patterns.stream().anyMatch(p -> p.equals("*") || p.equals(type)
                || (p.endsWith(".*") && type.startsWith(p.substring(0, p.length() - 1))));
    }
}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `./mvnw -q -pl case-management-core test -Dtest=EventPublisherTest`
Expected: PASS, all seven tests.

- [ ] **Step 7: Commit**

```bash
git add case-management-core/src
git commit -m "feat(core): transactional event outbox with CloudEvents and audit log"
```

---

### Task 15: Case lifecycle service

**Files:**
- Create: `case-management-core/src/main/java/org/casemgmt/service/CaseService.java`
- Create: `case-management-core/src/main/java/org/casemgmt/service/TransitionApplier.java`
- Create: `case-management-core/src/main/java/org/casemgmt/service/Actor.java`
- Create: `case-management-core/src/main/java/org/casemgmt/error/CaseConflictException.java`
- Create: `case-management-core/src/test/java/org/casemgmt/service/CaseServiceTest.java`

**Interfaces:**
- Consumes: repositories (Tasks 4–6), evaluator (Tasks 8–9), `EventPublisher` (Task 14), `EngineGateway` (Task 10)
- Produces:
  - `Actor(String userId, List<String> groups)`
  - `CaseService.create(String caseDefKey, String tenantId, String businessKey, String title, CasePriority priority, Map<String,Object> variables, Actor actor) : CaseInstance`
  - `CaseService.get(String caseId) : CaseInstance`
  - `CaseService.update(String caseId, long expectedVersion, Map<String,Object> patch, Actor actor) : CaseInstance`
  - `CaseService.close(String caseId, long expectedVersion, String outcome, Actor actor) : CaseInstance`
  - `CaseService.cancel(String caseId, long expectedVersion, String reason, Actor actor) : CaseInstance`
  - `CaseService.snapshot(String caseId) : CaseSnapshot`
  - `TransitionApplier.apply(CaseSnapshot, List<Transition>, Actor) : void` — persists state changes, creates engine tasks for newly ACTIVE human tasks, achieves milestones, emits one event per transition
  - `CaseConflictException(String code, String message, List<String> availableActions)`

- [ ] **Step 1: Write the failing test**

```java
package org.casemgmt.service;

import org.casemgmt.OracleTestBase;
import org.casemgmt.domain.*;
import org.casemgmt.engine.*;
import org.casemgmt.error.CaseConflictException;
import org.casemgmt.event.EventPublisher;
import org.casemgmt.repo.*;
import org.casemgmt.rules.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class CaseServiceTest extends OracleTestBase {

    private CaseService cases;
    private PlanItemRepository planItems;
    private RecordingGateway gateway;
    private final Actor alice = new Actor("alice", List.of("handlers"));

    @BeforeEach
    void setUp() throws Exception {
        for (String t : List.of("CM_WEBHOOK_DELIVERY", "CM_EVENT", "CM_AUDIT_LOG", "CM_MILESTONE",
                "CM_TASK", "CM_PLAN_ITEM", "CM_PARTICIPANT", "CM_CASE", "CM_PLAN_ITEM_DEF", "CM_CASE_DEF")) {
            jdbc().sql("DELETE FROM " + t).update();
        }
        String json = new String(getClass().getResourceAsStream("/definitions/test-definition.json")
                .readAllBytes(), StandardCharsets.UTF_8);
        new CaseDefinitionService(new CaseDefinitionRepository(jdbc())).deploy(json, "system");

        gateway = new RecordingGateway();
        cases = TestServices.caseService(jdbc(), gateway);
        planItems = new PlanItemRepository(jdbc());
    }

    @Test
    void createStartsTheCaseAndInstantiatesThePlanModel() {
        CaseInstance created = cases.create("widget-review", "t1", "BK-1", "First",
                CasePriority.HIGH, Map.of("amount", 10), alice);

        assertThat(created.id()).startsWith("eng-test:");
        assertThat(created.state()).isEqualTo(CaseState.ACTIVE);
        assertThat(planItems.findByCase(created.id())).hasSize(3);
    }

    @Test
    void createActivatesUngatedItemsAndCreatesTheirEngineTasks() {
        CaseInstance created = cases.create("widget-review", "t1", null, "T",
                CasePriority.MEDIUM, Map.of(), alice);

        assertThat(planItems.findByCase(created.id()))
                .filteredOn(i -> i.name().equals("review"))
                .singleElement()
                .extracting(PlanItem::state).isEqualTo(PlanItemState.ACTIVE);

        assertThat(gateway.created).hasSize(1);
        assertThat(gateway.created.get(0).caseId()).isEqualTo(created.id());
    }

    @Test
    void createEmitsACaseCreatedEventAndAnAuditRow() {
        cases.create("widget-review", "t1", null, "T", CasePriority.MEDIUM, Map.of(), alice);

        List<String> types = jdbc().sql("SELECT TYPE_ FROM CM_EVENT ORDER BY SEQ_")
                .query(String.class).list();
        assertThat(types).first().asString().endsWith("case.created");
        assertThat(jdbc().sql("SELECT COUNT(*) FROM CM_AUDIT_LOG").query(Integer.class).single())
                .isGreaterThan(0);
    }

    @Test
    void closeIsRejectedWhileARequiredItemIsOpen() {
        CaseInstance created = cases.create("widget-review", "t1", null, "T",
                CasePriority.MEDIUM, Map.of(), alice);

        assertThatThrownBy(() -> cases.close(created.id(), created.version(), "done", alice))
                .isInstanceOf(CaseConflictException.class)
                .hasMessageContaining("review");
    }

    @Test
    void closeSucceedsOnceRequiredItemsHaveEnded() {
        CaseInstance created = cases.create("widget-review", "t1", null, "T",
                CasePriority.MEDIUM, Map.of(), alice);
        PlanItem review = planItems.findByCase(created.id()).stream()
                .filter(i -> i.name().equals("review")).findFirst().orElseThrow();
        planItems.updateState(review.withState(PlanItemState.COMPLETED), review.version());

        CaseInstance reloaded = cases.get(created.id());
        CaseInstance closed = cases.close(reloaded.id(), reloaded.version(), "approved", alice);

        assertThat(closed.state()).isEqualTo(CaseState.CLOSED);
        assertThat(closed.outcome()).isEqualTo("approved");
    }

    @Test
    void cancelFromAnyLiveStateIsAllowedAndTerminatesOpenPlanItems() {
        CaseInstance created = cases.create("widget-review", "t1", null, "T",
                CasePriority.MEDIUM, Map.of(), alice);

        CaseInstance cancelled = cases.cancel(created.id(), created.version(), "duplicate", alice);

        assertThat(cancelled.state()).isEqualTo(CaseState.CANCELLED);
        assertThat(planItems.findByCase(created.id()))
                .allMatch(i -> i.state().isEnded());
    }

    @Test
    void closingAnAlreadyClosedCaseConflicts() {
        CaseInstance created = cases.create("widget-review", "t1", null, "T",
                CasePriority.MEDIUM, Map.of(), alice);
        PlanItem review = planItems.findByCase(created.id()).stream()
                .filter(i -> i.name().equals("review")).findFirst().orElseThrow();
        planItems.updateState(review.withState(PlanItemState.COMPLETED), review.version());
        CaseInstance closed = cases.close(created.id(), cases.get(created.id()).version(), "x", alice);

        assertThatThrownBy(() -> cases.close(closed.id(), closed.version(), "again", alice))
                .isInstanceOf(CaseConflictException.class)
                .hasMessageContaining("CLOSED");
    }

    static class RecordingGateway implements EngineGateway {
        final List<HumanTaskRequest> created = new java.util.ArrayList<>();
        public EngineTaskRef createHumanTask(HumanTaskRequest r) {
            created.add(r);
            return new EngineTaskRef("engine-" + created.size(), r.name(), r.assignee(), r.caseId(), null);
        }
        public void claimTask(String id, String user) {}
        public void completeTask(String id, Map<String, Object> v) {}
        public EngineProcessRef startProcess(StartProcessRequest r) {
            return new EngineProcessRef("proc-1", r.processDefinitionKey());
        }
        public void cancelProcess(String id, String reason) {}
        public List<EngineTaskRef> findTasks(EngineTaskQuery q) { return List.of(); }
    }
}
```

Also create the small wiring helper used above:

```java
package org.casemgmt.service;

import org.casemgmt.engine.EngineGateway;
import org.casemgmt.event.EventPublisher;
import org.casemgmt.repo.*;
import org.casemgmt.rules.*;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Test-only wiring so each test does not repeat eight constructor calls. */
public final class TestServices {

    private TestServices() {}

    public static CaseService caseService(JdbcClient jdbc, EngineGateway gateway) {
        var publisher = new EventPublisher(new EventRepository(jdbc), new AuditRepository(jdbc),
                new WebhookRepository(jdbc), "org.example.cm", "eng-test");
        var evaluator = new PlanModelEvaluator(new JuelCriterionEvaluator());
        var applier = new TransitionApplier(new PlanItemRepository(jdbc), new CaseTaskRepository(jdbc),
                new MilestoneRepository(jdbc), gateway, publisher);
        return new CaseService(new CaseRepository(jdbc), new CaseDefinitionRepository(jdbc),
                new PlanItemRepository(jdbc), new MilestoneRepository(jdbc),
                new ParticipantRepository(jdbc), evaluator, new PlanModelInstantiator(),
                new StageCompletion(), applier, publisher, "eng-test");
    }
}
```

Place `TestServices` in `case-management-core/src/test/java/org/casemgmt/service/TestServices.java`.

- [ ] **Step 2: Run it and watch it fail**

Run: `./mvnw -q -pl case-management-core test -Dtest=CaseServiceTest`
Expected: FAIL — `cannot find symbol: class CaseService`.

- [ ] **Step 3: Write `Actor` and `CaseConflictException`**

```java
package org.casemgmt.service;

import java.util.List;

public record Actor(String userId, List<String> groups) {
    public static final Actor SYSTEM = new Actor("system", List.of());
}
```

```java
package org.casemgmt.error;

import java.util.List;

/**
 * A 409. The message names the current state and the actions that ARE available,
 * so a client that raced can self-correct without a second round trip (spec §6.5).
 */
public class CaseConflictException extends RuntimeException {

    private final String code;
    private final List<String> availableActions;

    public CaseConflictException(String code, String message, List<String> availableActions) {
        super(message);
        this.code = code;
        this.availableActions = availableActions == null ? List.of() : availableActions;
    }

    public String code() { return code; }

    public List<String> availableActions() { return availableActions; }
}
```

- [ ] **Step 4: Write `TransitionApplier`**

```java
package org.casemgmt.service;

import org.casemgmt.domain.*;
import org.casemgmt.engine.EngineGateway;
import org.casemgmt.engine.EngineTaskRef;
import org.casemgmt.engine.HumanTaskRequest;
import org.casemgmt.event.CaseEvent;
import org.casemgmt.event.EventPublisher;
import org.casemgmt.event.EventTypes;
import org.casemgmt.repo.CaseTaskRepository;
import org.casemgmt.repo.MilestoneRepository;
import org.casemgmt.repo.PlanItemRepository;
import org.casemgmt.rules.CaseSnapshot;
import org.casemgmt.rules.Transition;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Persists what the (pure) evaluator decided, and performs the side effects each
 * transition implies: engine tasks for activated human tasks, milestone rows for
 * achieved milestones, one event per transition. Runs in the caller's transaction.
 */
public class TransitionApplier {

    private final PlanItemRepository planItems;
    private final CaseTaskRepository tasks;
    private final MilestoneRepository milestones;
    private final EngineGateway engine;
    private final EventPublisher publisher;

    public TransitionApplier(PlanItemRepository planItems, CaseTaskRepository tasks,
                             MilestoneRepository milestones, EngineGateway engine,
                             EventPublisher publisher) {
        this.planItems = planItems;
        this.tasks = tasks;
        this.milestones = milestones;
        this.engine = engine;
        this.publisher = publisher;
    }

    public void apply(CaseSnapshot snapshot, List<Transition> transitions, Actor actor) {
        for (Transition t : transitions) {
            PlanItem item = planItems.require(t.planItemId());
            PlanItem updated = planItems.updateState(item.withState(t.to()), item.version());
            PlanItemDefinition def = snapshot.definitionOf(item);

            if (t.to() == PlanItemState.ACTIVE && def.type() == PlanItemType.HUMAN_TASK) {
                createHumanTask(snapshot, updated, def, actor);
            }
            if (t.to() == PlanItemState.COMPLETED && def.type() == PlanItemType.MILESTONE) {
                achieveMilestone(snapshot, updated, actor);
            }
            publisher.publish(event(snapshot, EventTypes.PLAN_ITEM_TRANSITIONED, Map.of(
                    "planItemId", updated.id(), "defKey", def.defKey(),
                    "from", t.from().name(), "to", t.to().name(), "reason", t.reason())));
        }
    }

    private void createHumanTask(CaseSnapshot snapshot, PlanItem item, PlanItemDefinition def, Actor actor) {
        String taskId = CaseIds.newId();
        EngineTaskRef ref = engine.createHumanTask(new HumanTaskRequest(
                snapshot.caseInstance().id(), item.id(), def.name(), null,
                def.candidateGroups(), def.formKey(), snapshot.caseInstance().variables()));

        CaseTask.EngineSync sync = ref.engineTaskId() == null
                ? CaseTask.EngineSync.PENDING      // remote mode: the dispatcher confirms later
                : CaseTask.EngineSync.SYNCED;

        OffsetDateTime now = OffsetDateTime.now();
        tasks.insert(new CaseTask(taskId, snapshot.caseInstance().id(), item.id(),
                ref.engineTaskId(), def.name(), null, TaskState.OPEN, null, null,
                def.candidateGroups(), def.formKey(), 50, null, null, sync, 0L, now, now, null));

        if (ref.engineTaskId() != null) {
            planItems.bindEngineTask(item.id(), ref.engineTaskId());
        }
        publisher.publish(event(snapshot, EventTypes.TASK_CREATED, Map.of(
                "taskId", taskId, "planItemId", item.id(), "name", def.name(),
                "engineSync", sync.name())));
    }

    private void achieveMilestone(CaseSnapshot snapshot, PlanItem item, Actor actor) {
        String milestoneId = milestones.findByPlanItem(item.id())
                .map(MilestoneRepository.MilestoneRow::id)
                .orElseGet(() -> {
                    String id = CaseIds.newId();
                    milestones.insert(id, snapshot.caseInstance().id(), item.id(), item.name());
                    return id;
                });
        milestones.achieve(milestoneId, actor.userId());
        publisher.publish(event(snapshot, EventTypes.MILESTONE_ACHIEVED, Map.of(
                "milestoneId", milestoneId, "planItemId", item.id(), "name", item.name())));
    }

    private CaseEvent event(CaseSnapshot snapshot, String type, Map<String, Object> data) {
        return new CaseEvent(CaseIds.newId(), publisher.engineId(), type,
                snapshot.caseInstance().id(), snapshot.caseInstance().tenantId(),
                OffsetDateTime.now(), data);
    }
}
```

- [ ] **Step 5: Write `CaseService`**

```java
package org.casemgmt.service;

import org.casemgmt.domain.*;
import org.casemgmt.error.CaseConflictException;
import org.casemgmt.error.NotFoundException;
import org.casemgmt.event.CaseEvent;
import org.casemgmt.event.EventPublisher;
import org.casemgmt.event.EventTypes;
import org.casemgmt.repo.*;
import org.casemgmt.rules.*;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public class CaseService {

    private final CaseRepository cases;
    private final CaseDefinitionRepository definitions;
    private final PlanItemRepository planItems;
    private final MilestoneRepository milestones;
    private final ParticipantRepository participants;
    private final PlanModelEvaluator evaluator;
    private final PlanModelInstantiator instantiator;
    private final StageCompletion stageCompletion;
    private final TransitionApplier applier;
    private final EventPublisher publisher;
    private final String engineId;

    public CaseService(CaseRepository cases, CaseDefinitionRepository definitions,
                       PlanItemRepository planItems, MilestoneRepository milestones,
                       ParticipantRepository participants, PlanModelEvaluator evaluator,
                       PlanModelInstantiator instantiator, StageCompletion stageCompletion,
                       TransitionApplier applier, EventPublisher publisher, String engineId) {
        this.cases = cases;
        this.definitions = definitions;
        this.planItems = planItems;
        this.milestones = milestones;
        this.participants = participants;
        this.evaluator = evaluator;
        this.instantiator = instantiator;
        this.stageCompletion = stageCompletion;
        this.applier = applier;
        this.publisher = publisher;
        this.engineId = engineId;
    }

    @Transactional
    public CaseInstance create(String caseDefKey, String tenantId, String businessKey, String title,
                               CasePriority priority, Map<String, Object> variables, Actor actor) {
        CaseDefinition def = definitions.findLatest(caseDefKey, tenantId)
                .orElseThrow(() -> new NotFoundException("CaseDefinition", caseDefKey));

        OffsetDateTime now = OffsetDateTime.now();
        CaseInstance created = new CaseInstance(CaseIds.newCaseId(engineId), engineId, tenantId,
                def.id(), def.key(), def.versionNo(), businessKey, title, CaseState.ACTIVE,
                priority == null ? CasePriority.MEDIUM : priority, null, null, actor.userId(),
                "NONE", null, null, variables == null ? Map.of() : variables, 0L, now, now, null);
        cases.insert(created);

        participants.insert(CaseIds.newId(), created.id(), actor.userId(), null, "owner");
        instantiator.initialItems(created.id(), def).forEach(planItems::insert);

        publisher.publish(event(created, EventTypes.CASE_CREATED, Map.of(
                "caseDefinitionKey", def.key(), "state", created.state().name(),
                "businessKey", businessKey == null ? "" : businessKey)));
        publisher.audit(created.id(), tenantId, actor.userId(), "case.create", "Case",
                created.id(), null, Map.of("state", created.state().name(), "title", title));

        reevaluate(created.id(), actor);
        return cases.require(created.id());
    }

    public CaseInstance get(String caseId) {
        return cases.require(caseId);
    }

    public CaseSnapshot snapshot(String caseId) {
        CaseInstance instance = cases.require(caseId);
        return new CaseSnapshot(instance, definitions.require(instance.caseDefId()),
                planItems.findByCase(caseId));
    }

    @Transactional
    public CaseInstance update(String caseId, long expectedVersion, Map<String, Object> patch, Actor actor) {
        CaseInstance current = cases.require(caseId);
        requireLive(current, "update");

        CaseInstance patched = current;
        if (patch.containsKey("title")) {
            patched = new CaseInstance(patched.id(), patched.engineId(), patched.tenantId(),
                    patched.caseDefId(), patched.caseDefKey(), patched.caseDefVersion(),
                    patched.businessKey(), String.valueOf(patch.get("title")), patched.state(),
                    patched.priority(), patched.assignee(), patched.queueId(), patched.initiator(),
                    patched.slaStatus(), patched.outcome(), patched.cancelReason(),
                    patched.variables(), patched.version(), patched.createdAt(),
                    patched.updatedAt(), patched.closedAt());
        }
        if (patch.containsKey("variables")) {
            patched = patched.withVariables((Map<String, Object>) patch.get("variables"));
        }

        CaseInstance saved = cases.update(patched, expectedVersion);
        publisher.publish(event(saved, EventTypes.CASE_UPDATED, Map.of("fields", patch.keySet())));
        publisher.audit(caseId, saved.tenantId(), actor.userId(), "case.update", "Case", caseId,
                Map.of("title", current.title(), "variables", current.variables()),
                Map.of("title", saved.title(), "variables", saved.variables()));

        reevaluate(caseId, actor);
        return cases.require(caseId);
    }

    @Transactional
    public CaseInstance close(String caseId, long expectedVersion, String outcome, Actor actor) {
        CaseInstance current = cases.require(caseId);
        requireLive(current, "close");

        CaseSnapshot snapshot = snapshot(caseId);
        List<PlanItem> blockers = stageCompletion.caseBlockers(snapshot);
        if (!blockers.isEmpty()) {
            throw new CaseConflictException("required-items-open",
                    "Case cannot close while required plan items are open: "
                            + blockers.stream().map(PlanItem::name).toList(),
                    List.of("cancel", "update"));
        }

        CaseInstance closed = new CaseInstance(current.id(), current.engineId(), current.tenantId(),
                current.caseDefId(), current.caseDefKey(), current.caseDefVersion(),
                current.businessKey(), current.title(), CaseState.CLOSED, current.priority(),
                current.assignee(), current.queueId(), current.initiator(), current.slaStatus(),
                outcome, current.cancelReason(), current.variables(), current.version(),
                current.createdAt(), current.updatedAt(), OffsetDateTime.now());

        CaseInstance saved = cases.update(closed, expectedVersion);
        publisher.publish(event(saved, EventTypes.CASE_CLOSED, Map.of("outcome", outcome == null ? "" : outcome)));
        publisher.audit(caseId, saved.tenantId(), actor.userId(), "case.close", "Case", caseId,
                Map.of("state", current.state().name()), Map.of("state", "CLOSED", "outcome", outcome));
        return saved;
    }

    @Transactional
    public CaseInstance cancel(String caseId, long expectedVersion, String reason, Actor actor) {
        CaseInstance current = cases.require(caseId);
        requireLive(current, "cancel");

        for (PlanItem item : planItems.findByCase(caseId)) {
            if (!item.state().isEnded()) {
                planItems.updateState(item.withState(PlanItemState.TERMINATED), item.version());
            }
        }

        CaseInstance cancelled = new CaseInstance(current.id(), current.engineId(), current.tenantId(),
                current.caseDefId(), current.caseDefKey(), current.caseDefVersion(),
                current.businessKey(), current.title(), CaseState.CANCELLED, current.priority(),
                current.assignee(), current.queueId(), current.initiator(), current.slaStatus(),
                current.outcome(), reason, current.variables(), current.version(),
                current.createdAt(), current.updatedAt(), OffsetDateTime.now());

        CaseInstance saved = cases.update(cancelled, expectedVersion);
        publisher.publish(event(saved, EventTypes.CASE_CANCELLED, Map.of("reason", reason == null ? "" : reason)));
        publisher.audit(caseId, saved.tenantId(), actor.userId(), "case.cancel", "Case", caseId,
                Map.of("state", current.state().name()), Map.of("state", "CANCELLED", "reason", reason));
        return saved;
    }

    /** Re-evaluates the plan model after every mutation (spec §4.3) and applies what it decides. */
    @Transactional
    public void reevaluate(String caseId, Actor actor) {
        CaseSnapshot snapshot = snapshot(caseId);
        List<Transition> transitions = evaluator.evaluate(snapshot);
        if (!transitions.isEmpty()) {
            applier.apply(snapshot, transitions, actor);
        }
        for (PlanItemDefinition repeatable : evaluator.repeatable(snapshot(caseId))) {
            PlanItem latest = snapshot(caseId).latest(repeatable.defKey());
            planItems.insert(instantiator.repeat(latest, repeatable));
        }
    }

    private void requireLive(CaseInstance c, String action) {
        if (c.state() == CaseState.CLOSED || c.state() == CaseState.CANCELLED) {
            throw new CaseConflictException("illegal-state",
                    "Cannot " + action + " a case in state " + c.state(),
                    c.state() == CaseState.CLOSED ? List.of("reactivate") : List.of());
        }
    }

    private CaseEvent event(CaseInstance c, String type, Map<String, Object> data) {
        return new CaseEvent(CaseIds.newId(), engineId, type, c.id(), c.tenantId(),
                OffsetDateTime.now(), data);
    }
}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `./mvnw -q -pl case-management-core test -Dtest=CaseServiceTest`
Expected: PASS, all seven tests.

Watch for infinite repetition: `reevaluate` re-instantiates repeatable items whose latest instance has ended. The test definition has no repeatable items, but Task 27's complaint model does — if `reevaluate` there produces endless instances, the guard belongs in `evaluator.repeatable`, which must also require that entry criteria *changed*, not merely hold. Note the fix in `FINDINGS.md`.

- [ ] **Step 7: Commit**

```bash
git add case-management-core/src
git commit -m "feat(core): case lifecycle service with plan model re-evaluation"
```

---

### Task 16: Plan item actions

**Files:**
- Create: `case-management-core/src/main/java/org/casemgmt/service/PlanItemService.java`
- Create: `case-management-core/src/test/java/org/casemgmt/service/PlanItemServiceTest.java`
- Modify: `case-management-core/src/test/java/org/casemgmt/service/TestServices.java` (add a `planItemService` factory)

**Interfaces:**
- Consumes: `CaseService.snapshot`, `TransitionApplier`, repositories, `EventPublisher`
- Produces:
  - `PlanItemService.enable(String caseId, String itemId, long expectedVersion, Actor) : PlanItem`
  - `PlanItemService.start(String caseId, String itemId, long expectedVersion, Actor) : PlanItem`
  - `PlanItemService.complete(String caseId, String itemId, long expectedVersion, Actor) : PlanItem`
  - `PlanItemService.terminate(String caseId, String itemId, long expectedVersion, String reason, Actor) : PlanItem`
  - Each rejects an illegal source state with `CaseConflictException` and re-evaluates the model afterwards.

**Legal manual transitions** (spec §3.2) — everything else is a `409`:

| Action | From | To |
|---|---|---|
| `enable` | AVAILABLE | ENABLED |
| `start` | ENABLED | ACTIVE |
| `complete` | ACTIVE | COMPLETED |
| `terminate` | AVAILABLE, ENABLED, ACTIVE | TERMINATED |

- [ ] **Step 1: Write the failing test**

```java
package org.casemgmt.service;

import org.casemgmt.OracleTestBase;
import org.casemgmt.domain.*;
import org.casemgmt.error.CaseConflictException;
import org.casemgmt.repo.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class PlanItemServiceTest extends OracleTestBase {

    private CaseService cases;
    private PlanItemService planItemService;
    private PlanItemRepository planItems;
    private CaseServiceTest.RecordingGateway gateway;
    private final Actor alice = new Actor("alice", List.of("handlers"));
    private String caseId;

    @BeforeEach
    void setUp() throws Exception {
        for (String t : List.of("CM_WEBHOOK_DELIVERY", "CM_EVENT", "CM_AUDIT_LOG", "CM_MILESTONE",
                "CM_TASK", "CM_PLAN_ITEM", "CM_PARTICIPANT", "CM_CASE", "CM_PLAN_ITEM_DEF", "CM_CASE_DEF")) {
            jdbc().sql("DELETE FROM " + t).update();
        }
        // A model with one manual-activation item, so enable/start are meaningful.
        String json = """
                {"key":"manual-model","name":"Manual","tenantId":"t1",
                 "planItems":[
                   {"defKey":"manual","type":"HUMAN_TASK","name":"Manual","manualActivation":true,"sortOrder":10},
                   {"defKey":"auto","type":"HUMAN_TASK","name":"Auto","sortOrder":20}]}""";
        new CaseDefinitionService(new CaseDefinitionRepository(jdbc())).deploy(json, "system");

        gateway = new CaseServiceTest.RecordingGateway();
        cases = TestServices.caseService(jdbc(), gateway);
        planItemService = TestServices.planItemService(jdbc(), gateway);
        planItems = new PlanItemRepository(jdbc());
        caseId = cases.create("manual-model", "t1", null, "T", CasePriority.MEDIUM, Map.of(), alice).id();
    }

    private PlanItem item(String defKey) {
        return planItems.findByCase(caseId).stream()
                .filter(i -> i.name().equals(defKey)).findFirst().orElseThrow();
    }

    @Test
    void manualItemsStartEnabledAndCanBeStarted() {
        PlanItem manual = item("manual");
        assertThat(manual.state()).isEqualTo(PlanItemState.ENABLED);

        PlanItem started = planItemService.start(caseId, manual.id(), manual.version(), alice);

        assertThat(started.state()).isEqualTo(PlanItemState.ACTIVE);
    }

    @Test
    void startingAnItemCreatesItsEngineTask() {
        PlanItem manual = item("manual");
        int before = gateway.created.size();

        planItemService.start(caseId, manual.id(), manual.version(), alice);

        assertThat(gateway.created).hasSize(before + 1);
    }

    @Test
    void startingAnAvailableItemConflicts() {
        // 'auto' is already ACTIVE — starting it again is illegal.
        PlanItem auto = item("auto");

        assertThatThrownBy(() -> planItemService.start(caseId, auto.id(), auto.version(), alice))
                .isInstanceOf(CaseConflictException.class)
                .hasMessageContaining("ACTIVE");
    }

    @Test
    void completingAnActiveItemEndsIt() {
        PlanItem auto = item("auto");

        PlanItem completed = planItemService.complete(caseId, auto.id(), auto.version(), alice);

        assertThat(completed.state()).isEqualTo(PlanItemState.COMPLETED);
        assertThat(completed.endedAt()).isNotNull();
    }

    @Test
    void terminateWorksFromAnyLiveStateAndRecordsTheReason() {
        PlanItem manual = item("manual");

        PlanItem terminated = planItemService.terminate(caseId, manual.id(), manual.version(),
                "not needed", alice);

        assertThat(terminated.state()).isEqualTo(PlanItemState.TERMINATED);
        assertThat(terminated.terminationReason()).isEqualTo("not needed");
    }

    @Test
    void staleVersionsAreRejected() {
        PlanItem auto = item("auto");
        planItemService.complete(caseId, auto.id(), auto.version(), alice);

        assertThatThrownBy(() -> planItemService.terminate(caseId, auto.id(), auto.version(), "x", alice))
                .isInstanceOf(CaseConflictException.class);
    }

    @Test
    void everyTransitionEmitsAnEvent() {
        PlanItem manual = item("manual");
        long before = jdbc().sql("SELECT COUNT(*) FROM CM_EVENT").query(Long.class).single();

        planItemService.start(caseId, manual.id(), manual.version(), alice);

        long after = jdbc().sql("SELECT COUNT(*) FROM CM_EVENT").query(Long.class).single();
        assertThat(after).isGreaterThan(before);
    }
}
```

Add to `TestServices`:

```java
    public static PlanItemService planItemService(JdbcClient jdbc, EngineGateway gateway) {
        var publisher = new EventPublisher(new EventRepository(jdbc), new AuditRepository(jdbc),
                new WebhookRepository(jdbc), "org.example.cm", "eng-test");
        var applier = new TransitionApplier(new PlanItemRepository(jdbc), new CaseTaskRepository(jdbc),
                new MilestoneRepository(jdbc), gateway, publisher);
        return new PlanItemService(new PlanItemRepository(jdbc), caseService(jdbc, gateway),
                applier, publisher);
    }
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./mvnw -q -pl case-management-core test -Dtest=PlanItemServiceTest`
Expected: FAIL — `cannot find symbol: class PlanItemService`.

- [ ] **Step 3: Write the service**

```java
package org.casemgmt.service;

import org.casemgmt.domain.CaseIds;
import org.casemgmt.domain.PlanItem;
import org.casemgmt.domain.PlanItemState;
import org.casemgmt.error.CaseConflictException;
import org.casemgmt.error.OptimisticLockException;
import org.casemgmt.event.CaseEvent;
import org.casemgmt.event.EventPublisher;
import org.casemgmt.event.EventTypes;
import org.casemgmt.repo.PlanItemRepository;
import org.casemgmt.rules.CaseSnapshot;
import org.casemgmt.rules.Transition;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class PlanItemService {

    private static final Set<PlanItemState> TERMINABLE =
            EnumSet.of(PlanItemState.AVAILABLE, PlanItemState.ENABLED, PlanItemState.ACTIVE);

    private final PlanItemRepository planItems;
    private final CaseService cases;
    private final TransitionApplier applier;
    private final EventPublisher publisher;

    public PlanItemService(PlanItemRepository planItems, CaseService cases,
                           TransitionApplier applier, EventPublisher publisher) {
        this.planItems = planItems;
        this.cases = cases;
        this.applier = applier;
        this.publisher = publisher;
    }

    @Transactional
    public PlanItem enable(String caseId, String itemId, long expectedVersion, Actor actor) {
        return transition(caseId, itemId, expectedVersion,
                Set.of(PlanItemState.AVAILABLE), PlanItemState.ENABLED, null, actor);
    }

    @Transactional
    public PlanItem start(String caseId, String itemId, long expectedVersion, Actor actor) {
        return transition(caseId, itemId, expectedVersion,
                Set.of(PlanItemState.ENABLED), PlanItemState.ACTIVE, null, actor);
    }

    @Transactional
    public PlanItem complete(String caseId, String itemId, long expectedVersion, Actor actor) {
        return transition(caseId, itemId, expectedVersion,
                Set.of(PlanItemState.ACTIVE), PlanItemState.COMPLETED, null, actor);
    }

    @Transactional
    public PlanItem terminate(String caseId, String itemId, long expectedVersion,
                              String reason, Actor actor) {
        return transition(caseId, itemId, expectedVersion, TERMINABLE,
                PlanItemState.TERMINATED, reason, actor);
    }

    private PlanItem transition(String caseId, String itemId, long expectedVersion,
                                Set<PlanItemState> legalFrom, PlanItemState to,
                                String reason, Actor actor) {
        PlanItem item = planItems.require(itemId);
        if (!item.caseId().equals(caseId)) {
            throw new CaseConflictException("wrong-case",
                    "Plan item " + itemId + " does not belong to case " + caseId, List.of());
        }
        if (!legalFrom.contains(item.state())) {
            throw new CaseConflictException("illegal-transition",
                    "Cannot move plan item from " + item.state() + " to " + to,
                    legalActionsFor(item.state()));
        }

        CaseSnapshot snapshot = cases.snapshot(caseId);
        PlanItem target = withReason(item.withState(to), reason);

        PlanItem updated;
        try {
            updated = planItems.updateState(target, expectedVersion);
        } catch (OptimisticLockException e) {
            throw new CaseConflictException("version-conflict", e.getMessage(), List.of());
        }

        // Side effects (engine task creation, milestone achievement) are the applier's job.
        applier.apply(snapshot, List.of(new Transition(itemId, item.state(), to, "manual action")),
                actor);

        publisher.audit(caseId, snapshot.caseInstance().tenantId(), actor.userId(),
                "planitem." + to.name().toLowerCase(), "PlanItem", itemId,
                Map.of("state", item.state().name()), Map.of("state", to.name()));

        cases.reevaluate(caseId, actor);
        return planItems.require(itemId);
    }

    private PlanItem withReason(PlanItem item, String reason) {
        if (reason == null) {
            return item;
        }
        return new PlanItem(item.id(), item.caseId(), item.planItemDefId(), item.type(), item.name(),
                item.state(), item.parentStageId(), item.adHoc(), item.repetitionNo(),
                item.engineTaskId(), item.processInstanceId(), reason, item.version(),
                item.createdAt(), item.updatedAt(), item.endedAt());
    }

    private List<String> legalActionsFor(PlanItemState state) {
        return switch (state) {
            case AVAILABLE -> List.of("enable", "terminate");
            case ENABLED -> List.of("start", "terminate");
            case ACTIVE -> List.of("complete", "terminate");
            case COMPLETED, TERMINATED -> List.of();
        };
    }
}
```

Note: `applier.apply` re-persists the state it was given. Since `transition` already wrote the row, pass the snapshot taken *before* the write so the applier's `planItems.updateState` sees the right expected version — if that double-write causes an `OptimisticLockException` in practice, split `TransitionApplier` into `persist()` and `sideEffects()` and call only `sideEffects()` here. Record which shape you needed.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./mvnw -q -pl case-management-core test -Dtest=PlanItemServiceTest`
Expected: PASS, all seven tests.

- [ ] **Step 5: Commit**

```bash
git add case-management-core/src
git commit -m "feat(core): plan item enable/start/complete/terminate actions"
```

---

### Task 17: Task service with JSON Schema validation

**Files:**
- Create: `case-management-core/src/main/java/org/casemgmt/service/FormValidator.java`
- Create: `case-management-core/src/main/java/org/casemgmt/error/FormValidationException.java`
- Create: `case-management-core/src/main/java/org/casemgmt/service/CaseTaskService.java`
- Create: `case-management-core/src/test/java/org/casemgmt/service/FormValidatorTest.java`
- Create: `case-management-core/src/test/java/org/casemgmt/service/CaseTaskServiceTest.java`

**Interfaces:**
- Consumes: `CaseDefinitionRepository.formSchema` (Task 5), `CaseTaskRepository` (Task 6), `EngineGateway` (Task 10), `PlanItemService.complete` (Task 16)
- Produces:
  - `FormValidator.validate(Map<String,Object> schema, Map<String,Object> payload)` — throws `FormValidationException(List<Violation>)` where `Violation(String pointer, String message)`
  - `CaseTaskService.worklist(Actor, int limit) : List<CaseTask>`
  - `CaseTaskService.claim(String taskId, long expectedVersion, Actor) : CaseTask`
  - `CaseTaskService.complete(String taskId, long expectedVersion, Map<String,Object> variables, Actor) : CaseTask`

**Completion order matters:** validate the payload → complete the engine task → mark `CM_TASK` completed → complete the backing plan item → re-evaluate. If the engine call fails, the whole transaction rolls back in embedded mode; in remote mode the command outbox retries it, and the task stays `CLAIMED` until it succeeds.

- [ ] **Step 1: Write the failing tests**

```java
package org.casemgmt.service;

import org.casemgmt.error.FormValidationException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class FormValidatorTest {

    private final FormValidator validator = new FormValidator();

    private Map<String, Object> schema() {
        return Map.of(
                "type", "object",
                "required", List.of("outcome"),
                "properties", Map.of(
                        "outcome", Map.of("type", "string", "enum", List.of("approve", "reject")),
                        "amount", Map.of("type", "integer", "minimum", 0)));
    }

    @Test
    void acceptsAConformingPayload() {
        assertThatNoException().isThrownBy(() ->
                validator.validate(schema(), Map.of("outcome", "approve", "amount", 10)));
    }

    @Test
    void rejectsAMissingRequiredField() {
        assertThatThrownBy(() -> validator.validate(schema(), Map.of("amount", 10)))
                .isInstanceOf(FormValidationException.class)
                .satisfies(e -> assertThat(((FormValidationException) e).violations())
                        .anySatisfy(v -> assertThat(v.message()).contains("outcome")));
    }

    @Test
    void rejectsAValueOutsideTheEnumAndReportsAPointer() {
        assertThatThrownBy(() -> validator.validate(schema(), Map.of("outcome", "maybe")))
                .isInstanceOf(FormValidationException.class)
                .satisfies(e -> assertThat(((FormValidationException) e).violations())
                        .anySatisfy(v -> assertThat(v.pointer()).contains("outcome")));
    }

    @Test
    void aNullSchemaMeansNoValidation() {
        assertThatNoException().isThrownBy(() -> validator.validate(null, Map.of("anything", 1)));
    }
}
```

```java
package org.casemgmt.service;

import org.casemgmt.OracleTestBase;
import org.casemgmt.domain.*;
import org.casemgmt.error.CaseConflictException;
import org.casemgmt.error.FormValidationException;
import org.casemgmt.repo.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class CaseTaskServiceTest extends OracleTestBase {

    private CaseService cases;
    private CaseTaskService taskService;
    private CaseTaskRepository tasks;
    private CaseServiceTest.RecordingGateway gateway;
    private final Actor alice = new Actor("alice", List.of("reviewers"));
    private String caseId;

    @BeforeEach
    void setUp() throws Exception {
        for (String t : List.of("CM_WEBHOOK_DELIVERY", "CM_EVENT", "CM_AUDIT_LOG", "CM_MILESTONE",
                "CM_TASK", "CM_PLAN_ITEM", "CM_PARTICIPANT", "CM_CASE", "CM_PLAN_ITEM_DEF", "CM_CASE_DEF")) {
            jdbc().sql("DELETE FROM " + t).update();
        }
        String json = new String(getClass().getResourceAsStream("/definitions/test-definition.json")
                .readAllBytes(), StandardCharsets.UTF_8);
        new CaseDefinitionService(new CaseDefinitionRepository(jdbc())).deploy(json, "system");

        gateway = new CaseServiceTest.RecordingGateway();
        cases = TestServices.caseService(jdbc(), gateway);
        taskService = TestServices.taskService(jdbc(), gateway);
        tasks = new CaseTaskRepository(jdbc());
        caseId = cases.create("widget-review", "t1", null, "T", CasePriority.MEDIUM, Map.of(), alice).id();
    }

    private CaseTask task() {
        return tasks.findByCase(caseId).get(0);
    }

    @Test
    void worklistReturnsCandidateGroupTasks() {
        assertThat(taskService.worklist(alice, 20)).extracting(CaseTask::name).contains("review");
    }

    @Test
    void claimAssignsTheTaskAndCallsTheEngine() {
        CaseTask t = task();

        CaseTask claimed = taskService.claim(t.id(), t.version(), alice);

        assertThat(claimed.state()).isEqualTo(TaskState.CLAIMED);
        assertThat(claimed.assignee()).isEqualTo("alice");
    }

    @Test
    void claimingAnAlreadyClaimedTaskConflicts() {
        CaseTask t = task();
        taskService.claim(t.id(), t.version(), alice);
        CaseTask claimed = tasks.require(t.id());

        assertThatThrownBy(() -> taskService.claim(claimed.id(), claimed.version(),
                new Actor("bob", List.of("reviewers"))))
                .isInstanceOf(CaseConflictException.class)
                .hasMessageContaining("alice");
    }

    @Test
    void completeValidatesAgainstTheFormSchema() {
        CaseTask t = task();
        CaseTask claimed = taskService.claim(t.id(), t.version(), alice);

        assertThatThrownBy(() -> taskService.complete(claimed.id(), claimed.version(),
                Map.of("outcome", "not-a-valid-option"), alice))
                .isInstanceOf(FormValidationException.class);
    }

    @Test
    void completeEndsTheTaskAndItsPlanItem() {
        CaseTask t = task();
        CaseTask claimed = taskService.claim(t.id(), t.version(), alice);

        CaseTask completed = taskService.complete(claimed.id(), claimed.version(),
                Map.of("outcome", "approve"), alice);

        assertThat(completed.state()).isEqualTo(TaskState.COMPLETED);
        assertThat(new PlanItemRepository(jdbc()).require(completed.planItemId()).state())
                .isEqualTo(PlanItemState.COMPLETED);
    }

    @Test
    void completingATaskAdvancesTheModelToTheNextItem() {
        CaseTask t = task();
        CaseTask claimed = taskService.claim(t.id(), t.version(), alice);
        taskService.complete(claimed.id(), claimed.version(), Map.of("outcome", "approve"), alice);

        // 'reviewed' milestone has entry criterion items.review.state == 'COMPLETED'
        assertThat(new MilestoneRepository(jdbc()).findByCase(caseId))
                .anySatisfy(m -> assertThat(m.achieved()).isTrue());
    }
}
```

Add to `TestServices`:

```java
    public static CaseTaskService taskService(JdbcClient jdbc, EngineGateway gateway) {
        var publisher = new EventPublisher(new EventRepository(jdbc), new AuditRepository(jdbc),
                new WebhookRepository(jdbc), "org.example.cm", "eng-test");
        return new CaseTaskService(new CaseTaskRepository(jdbc), new CaseRepository(jdbc),
                new CaseDefinitionRepository(jdbc), gateway, new FormValidator(),
                planItemService(jdbc, gateway), publisher);
    }
```

- [ ] **Step 2: Run them and watch them fail**

Run: `./mvnw -q -pl case-management-core test -Dtest='FormValidatorTest,CaseTaskServiceTest'`
Expected: FAIL — `cannot find symbol: class FormValidator`.

- [ ] **Step 3: Write the validator and its exception**

```java
package org.casemgmt.error;

import java.util.List;

public class FormValidationException extends RuntimeException {

    public record Violation(String pointer, String message) {}

    private final List<Violation> violations;

    public FormValidationException(List<Violation> violations) {
        super("Payload does not satisfy the form schema: " + violations);
        this.violations = violations;
    }

    public List<Violation> violations() {
        return violations;
    }
}
```

```java
package org.casemgmt.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.casemgmt.error.FormValidationException;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One schema, two jobs (spec §4.6): the same document the frontend renders is the
 * document the service validates against, so client and server cannot disagree
 * about what a valid submission is.
 *
 * Note: networknt brings Jackson 2 (com.fasterxml), which coexists with the Jackson 3
 * that Spring Boot 4 uses elsewhere. The imports here are deliberately Jackson 2.
 */
public class FormValidator {

    private final com.fasterxml.jackson.databind.ObjectMapper mapper =
            new com.fasterxml.jackson.databind.ObjectMapper();
    private final JsonSchemaFactory factory =
            JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

    public void validate(Map<String, Object> schema, Map<String, Object> payload) {
        if (schema == null || schema.isEmpty()) {
            return;   // no schema declared for this form key: nothing to enforce
        }
        JsonNode schemaNode = mapper.valueToTree(schema);
        JsonNode payloadNode = mapper.valueToTree(payload == null ? Map.of() : payload);

        JsonSchema compiled = factory.getSchema(schemaNode);
        Set<ValidationMessage> messages = compiled.validate(payloadNode);

        if (!messages.isEmpty()) {
            List<FormValidationException.Violation> violations = messages.stream()
                    .map(m -> new FormValidationException.Violation(
                            m.getInstanceLocation() == null ? "/" : m.getInstanceLocation().toString(),
                            m.getMessage()))
                    .toList();
            throw new FormValidationException(violations);
        }
    }
}
```

- [ ] **Step 4: Write the task service**

```java
package org.casemgmt.service;

import org.casemgmt.domain.*;
import org.casemgmt.engine.EngineGateway;
import org.casemgmt.error.CaseConflictException;
import org.casemgmt.error.OptimisticLockException;
import org.casemgmt.event.CaseEvent;
import org.casemgmt.event.EventPublisher;
import org.casemgmt.event.EventTypes;
import org.casemgmt.repo.CaseDefinitionRepository;
import org.casemgmt.repo.CaseRepository;
import org.casemgmt.repo.CaseTaskRepository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public class CaseTaskService {

    private final CaseTaskRepository tasks;
    private final CaseRepository cases;
    private final CaseDefinitionRepository definitions;
    private final EngineGateway engine;
    private final FormValidator formValidator;
    private final PlanItemService planItems;
    private final EventPublisher publisher;

    public CaseTaskService(CaseTaskRepository tasks, CaseRepository cases,
                           CaseDefinitionRepository definitions, EngineGateway engine,
                           FormValidator formValidator, PlanItemService planItems,
                           EventPublisher publisher) {
        this.tasks = tasks;
        this.cases = cases;
        this.definitions = definitions;
        this.engine = engine;
        this.formValidator = formValidator;
        this.planItems = planItems;
        this.publisher = publisher;
    }

    public List<CaseTask> worklist(Actor actor, int limit) {
        return tasks.worklist(null, actor.groups(), limit);
    }

    public List<CaseTask> forCase(String caseId) {
        return tasks.findByCase(caseId);
    }

    @Transactional
    public CaseTask claim(String taskId, long expectedVersion, Actor actor) {
        CaseTask task = tasks.require(taskId);
        if (task.state() != TaskState.OPEN) {
            throw new CaseConflictException("task-not-open",
                    "Task is " + task.state() + (task.assignee() == null ? "" : " (assignee " + task.assignee() + ")"),
                    task.state() == TaskState.CLAIMED ? List.of("complete") : List.of());
        }
        if (task.engineSync() != CaseTask.EngineSync.SYNCED) {
            throw new CaseConflictException("engine-sync-pending",
                    "Task is not yet created on the engine (sync state " + task.engineSync() + ")",
                    List.of());
        }

        engine.claimTask(task.engineTaskId(), actor.userId());

        CaseTask claimed = withState(task, TaskState.CLAIMED, actor.userId(), task.outcome());
        CaseTask saved = save(claimed, expectedVersion);

        CaseInstance c = cases.require(task.caseId());
        publisher.publish(event(c, EventTypes.TASK_CLAIMED,
                Map.of("taskId", taskId, "assignee", actor.userId())));
        publisher.audit(task.caseId(), c.tenantId(), actor.userId(), "task.claim", "Task", taskId,
                Map.of("state", task.state().name()), Map.of("state", "CLAIMED", "assignee", actor.userId()));
        return saved;
    }

    @Transactional
    public CaseTask complete(String taskId, long expectedVersion,
                             Map<String, Object> variables, Actor actor) {
        CaseTask task = tasks.require(taskId);
        if (task.state() == TaskState.COMPLETED || task.state() == TaskState.TERMINATED) {
            throw new CaseConflictException("task-ended", "Task is already " + task.state(), List.of());
        }

        CaseInstance c = cases.require(task.caseId());
        if (task.formKey() != null) {
            definitions.formSchema(c.caseDefKey(), task.formKey())
                    .ifPresent(schema -> formValidator.validate(schema, variables));
        }

        engine.completeTask(task.engineTaskId(), variables);

        CaseTask completed = withState(task, TaskState.COMPLETED, task.assignee(),
                variables == null ? null : String.valueOf(variables.get("outcome")));
        CaseTask saved = save(completed, expectedVersion);

        publisher.publish(event(c, EventTypes.TASK_COMPLETED,
                Map.of("taskId", taskId, "outcome", saved.outcome() == null ? "" : saved.outcome())));
        publisher.audit(task.caseId(), c.tenantId(), actor.userId(), "task.complete", "Task", taskId,
                Map.of("state", task.state().name()), Map.of("state", "COMPLETED"));

        // Completing the task completes the plan item behind it, which re-evaluates the model.
        var item = new org.casemgmt.repo.PlanItemRepository(null) {};   // placeholder guard, see note
        planItems.complete(task.caseId(), task.planItemId(),
                currentPlanItemVersion(task.planItemId()), actor);

        return tasks.require(taskId);
    }

    private long currentPlanItemVersion(String planItemId) {
        return planItemVersionLookup.apply(planItemId);
    }

    /** Injected so this service does not need its own PlanItemRepository. */
    private java.util.function.Function<String, Long> planItemVersionLookup = id -> 0L;

    public void setPlanItemVersionLookup(java.util.function.Function<String, Long> lookup) {
        this.planItemVersionLookup = lookup;
    }

    private CaseTask save(CaseTask task, long expectedVersion) {
        try {
            return tasks.update(task, expectedVersion);
        } catch (OptimisticLockException e) {
            throw new CaseConflictException("version-conflict", e.getMessage(), List.of());
        }
    }

    private CaseTask withState(CaseTask t, TaskState state, String assignee, String outcome) {
        return new CaseTask(t.id(), t.caseId(), t.planItemId(), t.engineTaskId(), t.name(),
                t.description(), state, assignee, t.delegatedBy(), t.candidateGroups(),
                t.formKey(), t.priority(), t.dueAt(), outcome, t.engineSync(), t.version(),
                t.createdAt(), t.updatedAt(), t.completedAt());
    }

    private CaseEvent event(CaseInstance c, String type, Map<String, Object> data) {
        return new CaseEvent(CaseIds.newId(), publisher.engineId(), type, c.id(), c.tenantId(),
                OffsetDateTime.now(), data);
    }
}
```

**Fix before running:** the `complete` method above contains a deliberate wart — the anonymous `PlanItemRepository` line and the `planItemVersionLookup` indirection are worse than just injecting the repository. Replace both with a constructor-injected `PlanItemRepository planItemRepo` field, delete `currentPlanItemVersion`, `planItemVersionLookup` and `setPlanItemVersionLookup`, and call:

```java
        var planItem = planItemRepo.require(task.planItemId());
        planItems.complete(task.caseId(), planItem.id(), planItem.version(), actor);
```

Update `TestServices.taskService` to pass `new PlanItemRepository(jdbc)` as the extra argument.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./mvnw -q -pl case-management-core test -Dtest='FormValidatorTest,CaseTaskServiceTest'`
Expected: PASS, all ten tests.

- [ ] **Step 6: Commit**

```bash
git add case-management-core/src
git commit -m "feat(core): task claim/complete with JSON Schema validation"
```

---

### Task 18: Processes, comments and milestones

**Files:**
- Create: `case-management-core/src/main/java/org/casemgmt/service/LinkedProcessService.java`
- Create: `case-management-core/src/main/java/org/casemgmt/service/CommentService.java`
- Create: `case-management-core/src/main/java/org/casemgmt/service/MilestoneService.java`
- Create: `case-management-core/src/test/java/org/casemgmt/service/CollaborationServicesTest.java`

**Interfaces:**
- Consumes: `LinkedProcessRepository`, `CommentRepository`, `MilestoneRepository` (Task 6); `EngineGateway`; `EventPublisher`
- Produces:
  - `LinkedProcessService.start(String caseId, String planItemId, String processDefinitionKey, Map<String,Object> variables, Actor) : LinkedProcessRow`
  - `LinkedProcessService.forCase(String caseId) : List<LinkedProcessRow>`
  - `CommentService.add(String caseId, String text, String visibility, Actor) : CommentRow`
  - `CommentService.forCase(String caseId, String visibility) : List<CommentRow>`
  - `MilestoneService.achieve(String caseId, String milestoneId, Actor) : MilestoneRow`
  - `MilestoneService.forCase(String caseId) : List<MilestoneRow>`

- [ ] **Step 1: Write the failing test**

```java
package org.casemgmt.service;

import org.casemgmt.OracleTestBase;
import org.casemgmt.domain.CasePriority;
import org.casemgmt.error.CaseConflictException;
import org.casemgmt.repo.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class CollaborationServicesTest extends OracleTestBase {

    private CaseService cases;
    private CommentService comments;
    private MilestoneService milestones;
    private LinkedProcessService processes;
    private CaseServiceTest.RecordingGateway gateway;
    private final Actor alice = new Actor("alice", List.of("reviewers"));
    private String caseId;

    @BeforeEach
    void setUp() throws Exception {
        for (String t : List.of("CM_WEBHOOK_DELIVERY", "CM_EVENT", "CM_AUDIT_LOG", "CM_COMMENT",
                "CM_LINKED_PROCESS", "CM_MILESTONE", "CM_TASK", "CM_PLAN_ITEM", "CM_PARTICIPANT",
                "CM_CASE", "CM_PLAN_ITEM_DEF", "CM_CASE_DEF")) {
            jdbc().sql("DELETE FROM " + t).update();
        }
        String json = new String(getClass().getResourceAsStream("/definitions/test-definition.json")
                .readAllBytes(), StandardCharsets.UTF_8);
        new CaseDefinitionService(new CaseDefinitionRepository(jdbc())).deploy(json, "system");

        gateway = new CaseServiceTest.RecordingGateway();
        cases = TestServices.caseService(jdbc(), gateway);
        comments = TestServices.commentService(jdbc());
        milestones = TestServices.milestoneService(jdbc());
        processes = TestServices.processService(jdbc(), gateway);
        caseId = cases.create("widget-review", "t1", null, "T", CasePriority.MEDIUM, Map.of(), alice).id();
    }

    @Test
    void internalAndExternalCommentsAreSeparable() {
        comments.add(caseId, "worker note", "internal", alice);
        comments.add(caseId, "letter to customer", "external", alice);

        assertThat(comments.forCase(caseId, "external")).hasSize(1);
        assertThat(comments.forCase(caseId, null)).hasSize(2);
    }

    @Test
    void anInvalidVisibilityIsRejected() {
        assertThatThrownBy(() -> comments.add(caseId, "x", "secret", alice))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void commentsEmitEvents() {
        comments.add(caseId, "note", "internal", alice);

        List<String> types = jdbc().sql("SELECT TYPE_ FROM CM_EVENT ORDER BY SEQ_")
                .query(String.class).list();
        assertThat(types).anySatisfy(t -> assertThat(t).endsWith("case.comment.added"));
    }

    @Test
    void startingAProcessRecordsTheCorrelation() {
        var row = processes.start(caseId, null, "decision-letter", Map.of("x", 1), alice);

        assertThat(row.processInstanceId()).isNotBlank();
        assertThat(processes.forCase(caseId)).hasSize(1);
        assertThat(gateway.startedProcesses).containsExactly("decision-letter");
    }

    @Test
    void milestonesCanBeAchievedManuallyAndOnlyOnce() {
        String milestoneId = org.casemgmt.domain.CaseIds.newId();
        new MilestoneRepository(jdbc()).insert(milestoneId, caseId, null, "Acknowledged");

        var achieved = milestones.achieve(caseId, milestoneId, alice);
        assertThat(achieved.achieved()).isTrue();

        assertThatThrownBy(() -> milestones.achieve(caseId, milestoneId, alice))
                .isInstanceOf(CaseConflictException.class)
                .hasMessageContaining("already achieved");
    }
}
```

Extend `CaseServiceTest.RecordingGateway` with a `final List<String> startedProcesses = new ArrayList<>();` and record `r.processDefinitionKey()` in `startProcess`. Add the three factory methods to `TestServices` following the pattern already established there.

- [ ] **Step 2: Run it and watch it fail**

Run: `./mvnw -q -pl case-management-core test -Dtest=CollaborationServicesTest`
Expected: FAIL — `cannot find symbol: class CommentService`.

- [ ] **Step 3: Write the three services**

```java
package org.casemgmt.service;

import org.casemgmt.domain.CaseIds;
import org.casemgmt.domain.CaseInstance;
import org.casemgmt.event.CaseEvent;
import org.casemgmt.event.EventPublisher;
import org.casemgmt.event.EventTypes;
import org.casemgmt.repo.CaseRepository;
import org.casemgmt.repo.CommentRepository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public class CommentService {

    private final CommentRepository comments;
    private final CaseRepository cases;
    private final EventPublisher publisher;

    public CommentService(CommentRepository comments, CaseRepository cases, EventPublisher publisher) {
        this.comments = comments;
        this.cases = cases;
        this.publisher = publisher;
    }

    @Transactional
    public CommentRepository.CommentRow add(String caseId, String text, String visibility, Actor actor) {
        CaseInstance c = cases.require(caseId);
        String id = CaseIds.newId();
        comments.insert(id, caseId, actor.userId(), text, visibility);

        publisher.publish(new CaseEvent(CaseIds.newId(), publisher.engineId(),
                EventTypes.COMMENT_ADDED, caseId, c.tenantId(), OffsetDateTime.now(),
                Map.of("commentId", id, "visibility", visibility, "author", actor.userId())));
        publisher.audit(caseId, c.tenantId(), actor.userId(), "comment.add", "Comment", id,
                null, Map.of("visibility", visibility));

        return comments.findByCase(caseId, null).stream()
                .filter(row -> row.id().equals(id)).findFirst().orElseThrow();
    }

    public List<CommentRepository.CommentRow> forCase(String caseId, String visibility) {
        return comments.findByCase(caseId, visibility);
    }
}
```

```java
package org.casemgmt.service;

import org.casemgmt.domain.CaseIds;
import org.casemgmt.domain.CaseInstance;
import org.casemgmt.engine.EngineGateway;
import org.casemgmt.engine.EngineProcessRef;
import org.casemgmt.engine.StartProcessRequest;
import org.casemgmt.event.CaseEvent;
import org.casemgmt.event.EventPublisher;
import org.casemgmt.event.EventTypes;
import org.casemgmt.repo.CaseRepository;
import org.casemgmt.repo.LinkedProcessRepository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public class LinkedProcessService {

    private final LinkedProcessRepository processes;
    private final CaseRepository cases;
    private final EngineGateway engine;
    private final EventPublisher publisher;

    public LinkedProcessService(LinkedProcessRepository processes, CaseRepository cases,
                                EngineGateway engine, EventPublisher publisher) {
        this.processes = processes;
        this.cases = cases;
        this.engine = engine;
        this.publisher = publisher;
    }

    @Transactional
    public LinkedProcessRepository.LinkedProcessRow start(String caseId, String planItemId,
                                                          String processDefinitionKey,
                                                          Map<String, Object> variables, Actor actor) {
        CaseInstance c = cases.require(caseId);
        EngineProcessRef ref = engine.startProcess(
                new StartProcessRequest(caseId, planItemId, processDefinitionKey, variables));

        String id = CaseIds.newId();
        // In remote mode the instance id arrives later; use the command id as a placeholder.
        String instanceId = ref.processInstanceId() == null ? id : ref.processInstanceId();
        processes.insert(id, caseId, planItemId, instanceId, processDefinitionKey);

        publisher.publish(new CaseEvent(CaseIds.newId(), publisher.engineId(),
                EventTypes.PROCESS_STARTED, caseId, c.tenantId(), OffsetDateTime.now(),
                Map.of("processInstanceId", instanceId, "processDefinitionKey", processDefinitionKey)));

        return processes.findByCase(caseId).stream()
                .filter(row -> row.id().equals(id)).findFirst().orElseThrow();
    }

    public List<LinkedProcessRepository.LinkedProcessRow> forCase(String caseId) {
        return processes.findByCase(caseId);
    }
}
```

```java
package org.casemgmt.service;

import org.casemgmt.domain.CaseIds;
import org.casemgmt.domain.CaseInstance;
import org.casemgmt.error.CaseConflictException;
import org.casemgmt.error.NotFoundException;
import org.casemgmt.event.CaseEvent;
import org.casemgmt.event.EventPublisher;
import org.casemgmt.event.EventTypes;
import org.casemgmt.repo.CaseRepository;
import org.casemgmt.repo.MilestoneRepository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public class MilestoneService {

    private final MilestoneRepository milestones;
    private final CaseRepository cases;
    private final EventPublisher publisher;

    public MilestoneService(MilestoneRepository milestones, CaseRepository cases,
                            EventPublisher publisher) {
        this.milestones = milestones;
        this.cases = cases;
        this.publisher = publisher;
    }

    @Transactional
    public MilestoneRepository.MilestoneRow achieve(String caseId, String milestoneId, Actor actor) {
        CaseInstance c = cases.require(caseId);
        MilestoneRepository.MilestoneRow row = milestones.findByCase(caseId).stream()
                .filter(m -> m.id().equals(milestoneId)).findFirst()
                .orElseThrow(() -> new NotFoundException("Milestone", milestoneId));

        if (row.achieved()) {
            throw new CaseConflictException("milestone-achieved",
                    "Milestone " + row.name() + " is already achieved", List.of());
        }
        milestones.achieve(milestoneId, actor.userId());

        publisher.publish(new CaseEvent(CaseIds.newId(), publisher.engineId(),
                EventTypes.MILESTONE_ACHIEVED, caseId, c.tenantId(), OffsetDateTime.now(),
                Map.of("milestoneId", milestoneId, "name", row.name())));
        publisher.audit(caseId, c.tenantId(), actor.userId(), "milestone.achieve", "Milestone",
                milestoneId, Map.of("achieved", false), Map.of("achieved", true));

        return milestones.findByCase(caseId).stream()
                .filter(m -> m.id().equals(milestoneId)).findFirst().orElseThrow();
    }

    public List<MilestoneRepository.MilestoneRow> forCase(String caseId) {
        return milestones.findByCase(caseId);
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./mvnw -q -pl case-management-core test -Dtest=CollaborationServicesTest`
Expected: PASS, all five tests.

- [ ] **Step 5: Commit**

```bash
git add case-management-core/src
git commit -m "feat(core): linked process, comment and milestone services"
```

---

### Task 19: Webhook dispatcher with HMAC, retries and DLQ

**Files:**
- Create: `case-management-core/src/main/java/org/casemgmt/event/WebhookDispatcher.java`
- Create: `case-management-core/src/main/java/org/casemgmt/event/HmacSigner.java`
- Create: `case-management-core/src/main/java/org/casemgmt/service/WebhookService.java`
- Modify: `case-management-core/src/main/java/org/casemgmt/repo/WebhookRepository.java` (add delivery queries)
- Create: `case-management-core/src/test/java/org/casemgmt/event/WebhookDispatcherTest.java`

**Interfaces:**
- Consumes: `WebhookRepository`, `EventRepository` (Task 14)
- Produces:
  - `HmacSigner.sign(String secret, String body) : String` — returns `sha256=<hex>`
  - `WebhookService.subscribe(String tenantId, String url, List<String> eventTypes, Actor) : Subscription` — returns the **plaintext secret once**, stores only its hash
  - `WebhookService.list() : List<Subscription>`
  - `WebhookDispatcher.drainOnce() : int`
  - `WebhookRepository.claimDueDeliveries(int limit) : List<Delivery>` where `Delivery(String id, String webhookId, long eventSeq, int attempts)`; plus `markDelivered`, `markRetry`, `markDead`, `deadLetters(String webhookId)`

- [ ] **Step 1: Write the failing test**

```java
package org.casemgmt.event;

import com.sun.net.httpserver.HttpServer;
import org.casemgmt.OracleTestBase;
import org.casemgmt.domain.CaseIds;
import org.casemgmt.repo.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookDispatcherTest extends OracleTestBase {

    record Received(String body, String signature) {}

    private HttpServer server;
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private volatile int responseCode = 200;
    private EventPublisher publisher;
    private WebhookRepository webhooks;

    @BeforeEach
    void setUp() throws Exception {
        for (String t : List.of("CM_WEBHOOK_DELIVERY", "CM_WEBHOOK_SUB", "CM_EVENT")) {
            jdbc().sql("DELETE FROM " + t).update();
        }
        received.clear();
        responseCode = 200;

        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/hook", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes());
            received.add(new Received(body, exchange.getRequestHeaders().getFirst("X-Case-Signature")));
            exchange.sendResponseHeaders(responseCode, 0);
            try (OutputStream os = exchange.getResponseBody()) { os.write(new byte[0]); }
        });
        server.start();

        webhooks = new WebhookRepository(jdbc());
        publisher = new EventPublisher(new EventRepository(jdbc()), new AuditRepository(jdbc()),
                webhooks, "org.example.cm", "eng-a");
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private String hookUrl() {
        return "http://localhost:" + server.getAddress().getPort() + "/hook";
    }

    private void subscribe(String secretHash) {
        webhooks.insert("w-1", "t1", hookUrl(), List.of("*"), secretHash, 5);
    }

    private void publishOne() {
        publisher.publish(new CaseEvent(CaseIds.newId(), "eng-a", "case.created", "eng-a:1",
                "t1", OffsetDateTime.now(), Map.of("state", "ACTIVE")));
    }

    @Test
    void deliversTheCloudEventEnvelopeAndMarksTheRowDelivered() {
        subscribe(HmacSigner.hash("s3cret"));
        publishOne();

        int processed = dispatcher("s3cret").drainOnce();

        assertThat(processed).isEqualTo(1);
        assertThat(received).hasSize(1);
        assertThat(received.get(0).body()).contains("\"specversion\":\"1.0\"");
        assertThat(jdbc().sql("SELECT STATUS_ FROM CM_WEBHOOK_DELIVERY").query(String.class).single())
                .isEqualTo("DELIVERED");
    }

    @Test
    void signsThePayloadWithTheSubscriptionSecret() {
        subscribe(HmacSigner.hash("s3cret"));
        publishOne();

        dispatcher("s3cret").drainOnce();

        String signature = received.get(0).signature();
        assertThat(signature).startsWith("sha256=");
        assertThat(HmacSigner.sign("s3cret", received.get(0).body())).isEqualTo(signature);
    }

    @Test
    void failedDeliveriesRetryThenLandInTheDeadLetterQueue() {
        subscribe(HmacSigner.hash("s3cret"));
        publishOne();
        responseCode = 500;

        var dispatcher = dispatcher("s3cret");
        for (int i = 0; i < 6; i++) {
            jdbc().sql("UPDATE CM_WEBHOOK_DELIVERY SET NEXT_ATTEMPT_AT_ = SYSTIMESTAMP - INTERVAL '1' HOUR")
                    .update();
            dispatcher.drainOnce();
        }

        assertThat(jdbc().sql("SELECT STATUS_ FROM CM_WEBHOOK_DELIVERY").query(String.class).single())
                .isEqualTo("DEAD");
        assertThat(webhooks.deadLetters("w-1")).hasSize(1);
    }

    @Test
    void subscriptionsOnlyReceiveTheirSubscribedTypes() {
        webhooks.insert("w-2", "t1", hookUrl(), List.of("org.example.cm.case.closed"),
                HmacSigner.hash("s3cret"), 5);
        publishOne();   // case.created

        dispatcher("s3cret").drainOnce();

        assertThat(received).isEmpty();
    }

    private WebhookDispatcher dispatcher(String plaintextSecret) {
        return new WebhookDispatcher(webhooks, new EventRepository(jdbc()),
                id -> plaintextSecret);
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./mvnw -q -pl case-management-core test -Dtest=WebhookDispatcherTest`
Expected: FAIL — `cannot find symbol: class HmacSigner`.

- [ ] **Step 3: Write the signer**

```java
package org.casemgmt.event;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

public final class HmacSigner {

    private HmacSigner() {}

    /** Header value for X-Case-Signature (spec §6.1). */
    public static String sign(String secret, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Could not sign webhook payload", e);
        }
    }

    /** Subscriptions store only this; the plaintext is shown once at creation. */
    public static String hash(String secret) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(secret.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Could not hash webhook secret", e);
        }
    }
}
```

- [ ] **Step 4: Add delivery queries to `WebhookRepository`**

```java
    public record Delivery(String id, String webhookId, long eventSeq, int attempts) {}

    public List<Delivery> claimDueDeliveries(int limit) {
        return jdbc.sql("""
                SELECT ID_, WEBHOOK_ID_, EVENT_SEQ_, ATTEMPTS_
                FROM CM_WEBHOOK_DELIVERY
                WHERE STATUS_ IN ('PENDING','RETRYING') AND NEXT_ATTEMPT_AT_ <= SYSTIMESTAMP
                ORDER BY EVENT_SEQ_
                FETCH FIRST :limit ROWS ONLY
                FOR UPDATE SKIP LOCKED""")
            .param("limit", limit)
            .query((rs, n) -> new Delivery(rs.getString("ID_"), rs.getString("WEBHOOK_ID_"),
                    rs.getLong("EVENT_SEQ_"), rs.getInt("ATTEMPTS_")))
            .list();
    }

    public void markDelivered(String deliveryId, int statusCode) {
        jdbc.sql("""
                UPDATE CM_WEBHOOK_DELIVERY SET STATUS_ = 'DELIVERED', ATTEMPTS_ = ATTEMPTS_ + 1,
                    LAST_STATUS_CODE_ = :code, DELIVERED_AT_ = SYSTIMESTAMP
                WHERE ID_ = :id""")
            .param("code", statusCode).param("id", deliveryId).update();
    }

    public void markRetry(String deliveryId, Integer statusCode, String error,
                          java.time.OffsetDateTime nextAttempt) {
        jdbc.sql("""
                UPDATE CM_WEBHOOK_DELIVERY SET STATUS_ = 'RETRYING', ATTEMPTS_ = ATTEMPTS_ + 1,
                    LAST_STATUS_CODE_ = :code, LAST_ERROR_ = :error, NEXT_ATTEMPT_AT_ = :next
                WHERE ID_ = :id""")
            .param("code", statusCode).param("error", error).param("next", nextAttempt)
            .param("id", deliveryId).update();
    }

    public void markDead(String deliveryId, Integer statusCode, String error) {
        jdbc.sql("""
                UPDATE CM_WEBHOOK_DELIVERY SET STATUS_ = 'DEAD', ATTEMPTS_ = ATTEMPTS_ + 1,
                    LAST_STATUS_CODE_ = :code, LAST_ERROR_ = :error
                WHERE ID_ = :id""")
            .param("code", statusCode).param("error", error).param("id", deliveryId).update();
    }

    /** Rows in DEAD state ARE the dead-letter queue (db-design.md §3.6). */
    public List<Delivery> deadLetters(String webhookId) {
        return jdbc.sql("""
                SELECT ID_, WEBHOOK_ID_, EVENT_SEQ_, ATTEMPTS_ FROM CM_WEBHOOK_DELIVERY
                WHERE WEBHOOK_ID_ = :id AND STATUS_ = 'DEAD' ORDER BY EVENT_SEQ_""")
            .param("id", webhookId)
            .query((rs, n) -> new Delivery(rs.getString("ID_"), rs.getString("WEBHOOK_ID_"),
                    rs.getLong("EVENT_SEQ_"), rs.getInt("ATTEMPTS_")))
            .list();
    }

    public Subscription require(String id) {
        return all().stream().filter(s -> s.id().equals(id)).findFirst()
                .orElseThrow(() -> new org.casemgmt.error.NotFoundException("Webhook", id));
    }
```

- [ ] **Step 5: Write the dispatcher**

```java
package org.casemgmt.event;

import org.casemgmt.engine.EngineCommand;
import org.casemgmt.repo.EventRepository;
import org.casemgmt.repo.JsonCodec;
import org.casemgmt.repo.WebhookRepository;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.function.Function;

/**
 * Reads the outbox and pushes. Never called from a request thread: webhooks are
 * delivered after commit, at-least-once, with the same backoff as engine commands.
 */
public class WebhookDispatcher {

    private final WebhookRepository webhooks;
    private final EventRepository events;
    private final Function<String, String> secretResolver;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    public WebhookDispatcher(WebhookRepository webhooks, EventRepository events,
                             Function<String, String> secretResolver) {
        this.webhooks = webhooks;
        this.events = events;
        this.secretResolver = secretResolver;
    }

    public int drainOnce() {
        List<WebhookRepository.Delivery> due = webhooks.claimDueDeliveries(50);
        for (WebhookRepository.Delivery delivery : due) {
            deliver(delivery);
        }
        return due.size();
    }

    private void deliver(WebhookRepository.Delivery delivery) {
        WebhookRepository.Subscription sub = webhooks.require(delivery.webhookId());
        EventRepository.StoredEvent stored = events.after(delivery.eventSeq() - 1, 1).stream()
                .filter(e -> e.seq() == delivery.eventSeq()).findFirst().orElse(null);
        if (stored == null) {
            webhooks.markDead(delivery.id(), null, "event " + delivery.eventSeq() + " not found");
            return;
        }

        String body = JsonCodec.toJson(stored.event().toCloudEvent());
        String signature = HmacSigner.sign(secretResolver.apply(sub.id()), body);

        try {
            HttpResponse<Void> response = http.send(HttpRequest.newBuilder()
                            .uri(URI.create(sub.url()))
                            .timeout(Duration.ofSeconds(10))
                            .header("Content-Type", "application/cloudevents+json")
                            .header("X-Case-Signature", signature)
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build(),
                    HttpResponse.BodyHandlers.discarding());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                webhooks.markDelivered(delivery.id(), response.statusCode());
            } else {
                fail(delivery, response.statusCode(), "HTTP " + response.statusCode());
            }
        } catch (Exception e) {
            fail(delivery, null, e.getMessage());
        }
    }

    private void fail(WebhookRepository.Delivery delivery, Integer statusCode, String error) {
        if (EngineCommand.exhausted(delivery.attempts())) {
            webhooks.markDead(delivery.id(), statusCode, error);
        } else {
            webhooks.markRetry(delivery.id(), statusCode, error,
                    EngineCommand.nextAttempt(delivery.attempts()));
        }
    }
}
```

- [ ] **Step 6: Write the subscription service**

```java
package org.casemgmt.service;

import org.casemgmt.domain.CaseIds;
import org.casemgmt.event.HmacSigner;
import org.casemgmt.repo.WebhookRepository;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;

public class WebhookService {

    /** The plaintext secret is returned exactly once, at creation. */
    public record CreatedSubscription(String id, String url, List<String> eventTypes, String secret) {}

    private final WebhookRepository webhooks;
    private final SecureRandom random = new SecureRandom();

    public WebhookService(WebhookRepository webhooks) {
        this.webhooks = webhooks;
    }

    @Transactional
    public CreatedSubscription subscribe(String tenantId, String url, List<String> eventTypes, Actor actor) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String secret = HexFormat.of().formatHex(bytes);

        String id = CaseIds.newId();
        webhooks.insert(id, tenantId, url, eventTypes, HmacSigner.hash(secret), 5);
        return new CreatedSubscription(id, url, eventTypes, secret);
    }

    public List<WebhookRepository.Subscription> list() {
        return webhooks.all();
    }

    public List<WebhookRepository.Delivery> deadLetters(String webhookId) {
        return webhooks.deadLetters(webhookId);
    }
}
```

**Secret storage note:** `SECRET_HASH_` holds a SHA-256 of the plaintext, but the dispatcher needs the *plaintext* to sign. For the PoC, the dispatcher receives a `secretResolver` function; wire it in Task 26 to an in-memory map populated at subscription time, and record in `FINDINGS.md` that a production build needs reversible encryption (or per-subscription signing keys held in a secret store) instead. Do not silently store plaintext in the database.

- [ ] **Step 7: Run the test to verify it passes**

Run: `./mvnw -q -pl case-management-core test -Dtest=WebhookDispatcherTest`
Expected: PASS, all four tests.

- [ ] **Step 8: Commit**

```bash
git add case-management-core/src
git commit -m "feat(core): webhook dispatcher with HMAC signing, retries and DLQ"
```

---

## Phase 5 — SLA

### Task 20: Business calendar

**Files:**
- Create: `case-management-core/src/main/java/org/casemgmt/sla/BusinessCalendar.java`
- Create: `case-management-core/src/test/java/org/casemgmt/sla/BusinessCalendarTest.java`

**Interfaces:**
- Consumes: nothing beyond the JDK
- Produces:
  - `BusinessCalendar.fromJson(Map<String,Object> definition) : BusinessCalendar`
  - `BusinessCalendar.addDuration(OffsetDateTime from, Duration duration) : OffsetDateTime` — advances only through working intervals
  - `BusinessCalendar.isWorking(OffsetDateTime at) : boolean`

**Calendar document shape** (stored in `CM_BUSINESS_CALENDAR.DEFINITION_JSON_`):

```json
{
  "timezone": "Europe/Amsterdam",
  "workingHours": {
    "MONDAY":    [{"from": "09:00", "to": "17:00"}],
    "TUESDAY":   [{"from": "09:00", "to": "17:00"}],
    "WEDNESDAY": [{"from": "09:00", "to": "17:00"}],
    "THURSDAY":  [{"from": "09:00", "to": "17:00"}],
    "FRIDAY":    [{"from": "09:00", "to": "17:00"}]
  },
  "holidays": ["2026-12-25", "2026-12-26"]
}
```

This is the fiddliest piece of logic in the SLA slice, so it is isolated and unit-tested on its own.

- [ ] **Step 1: Write the failing test**

```java
package org.casemgmt.sla;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessCalendarTest {

    private static final ZoneId AMS = ZoneId.of("Europe/Amsterdam");

    private BusinessCalendar calendar() {
        Map<String, Object> day = Map.of("from", "09:00", "to", "17:00");
        return BusinessCalendar.fromJson(Map.of(
                "timezone", "Europe/Amsterdam",
                "workingHours", Map.of(
                        "MONDAY", List.of(day), "TUESDAY", List.of(day), "WEDNESDAY", List.of(day),
                        "THURSDAY", List.of(day), "FRIDAY", List.of(day)),
                "holidays", List.of("2026-12-25")));
    }

    private OffsetDateTime at(int year, int month, int day, int hour, int minute) {
        return ZonedDateTime.of(year, month, day, hour, minute, 0, 0, AMS).toOffsetDateTime();
    }

    @Test
    void addsHoursInsideOneWorkingDay() {
        // Thursday 2026-08-06, 10:00 + 4h = 14:00 same day
        assertThat(calendar().addDuration(at(2026, 8, 6, 10, 0), Duration.ofHours(4)))
                .isEqualTo(at(2026, 8, 6, 14, 0));
    }

    @Test
    void rollsOverIntoTheNextWorkingDay() {
        // Thursday 15:00 + 4h: 2h left today, 2h into Friday from 09:00 = Friday 11:00
        assertThat(calendar().addDuration(at(2026, 8, 6, 15, 0), Duration.ofHours(4)))
                .isEqualTo(at(2026, 8, 7, 11, 0));
    }

    @Test
    void skipsTheWeekend() {
        // Friday 16:00 + 2h: 1h left Friday, 1h into Monday from 09:00 = Monday 10:00
        assertThat(calendar().addDuration(at(2026, 8, 7, 16, 0), Duration.ofHours(2)))
                .isEqualTo(at(2026, 8, 10, 10, 0));
    }

    @Test
    void skipsHolidays() {
        // 2026-12-25 is a Friday and a holiday: Thursday 16:00 + 2h lands Monday 2026-12-28 10:00
        assertThat(calendar().addDuration(at(2026, 12, 24, 16, 0), Duration.ofHours(2)))
                .isEqualTo(at(2026, 12, 28, 10, 0));
    }

    @Test
    void startingOutsideWorkingHoursJumpsToTheNextOpening() {
        // Thursday 06:00 + 1h = Thursday 10:00 (clock starts at 09:00)
        assertThat(calendar().addDuration(at(2026, 8, 6, 6, 0), Duration.ofHours(1)))
                .isEqualTo(at(2026, 8, 6, 10, 0));
    }

    @Test
    void survivesTheDstTransition() {
        // Dutch DST ends 2026-10-25 (Sunday). Friday 2026-10-23 16:00 + 2h -> Monday 2026-10-26 10:00
        assertThat(calendar().addDuration(at(2026, 10, 23, 16, 0), Duration.ofHours(2)))
                .isEqualTo(at(2026, 10, 26, 10, 0));
    }

    @Test
    void reportsWorkingAndNonWorkingInstants() {
        assertThat(calendar().isWorking(at(2026, 8, 6, 10, 0))).isTrue();
        assertThat(calendar().isWorking(at(2026, 8, 6, 20, 0))).isFalse();
        assertThat(calendar().isWorking(at(2026, 8, 8, 10, 0))).isFalse();   // Saturday
        assertThat(calendar().isWorking(at(2026, 12, 25, 10, 0))).isFalse(); // holiday
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./mvnw -q -pl case-management-core test -Dtest=BusinessCalendarTest`
Expected: FAIL — `cannot find symbol: class BusinessCalendar`.

- [ ] **Step 3: Write the calendar**

```java
package org.casemgmt.sla;

import java.time.*;
import java.util.*;

/**
 * Walks an ISO duration across working intervals, skipping evenings, weekends and
 * holidays. All arithmetic is done in the calendar's own zone so DST transitions
 * move wall-clock openings rather than shifting them by an hour.
 */
public class BusinessCalendar {

    public record Interval(LocalTime from, LocalTime to) {}

    private final ZoneId zone;
    private final Map<DayOfWeek, List<Interval>> workingHours;
    private final Set<LocalDate> holidays;

    private BusinessCalendar(ZoneId zone, Map<DayOfWeek, List<Interval>> workingHours,
                             Set<LocalDate> holidays) {
        this.zone = zone;
        this.workingHours = workingHours;
        this.holidays = holidays;
    }

    @SuppressWarnings("unchecked")
    public static BusinessCalendar fromJson(Map<String, Object> definition) {
        ZoneId zone = ZoneId.of((String) definition.getOrDefault("timezone", "UTC"));

        Map<DayOfWeek, List<Interval>> hours = new EnumMap<>(DayOfWeek.class);
        Map<String, Object> raw = (Map<String, Object>) definition.getOrDefault("workingHours", Map.of());
        raw.forEach((day, intervals) -> hours.put(DayOfWeek.valueOf(day),
                ((List<Map<String, String>>) intervals).stream()
                        .map(i -> new Interval(LocalTime.parse(i.get("from")), LocalTime.parse(i.get("to"))))
                        .sorted(Comparator.comparing(Interval::from))
                        .toList()));

        Set<LocalDate> holidays = new HashSet<>();
        ((List<String>) definition.getOrDefault("holidays", List.of()))
                .forEach(d -> holidays.add(LocalDate.parse(d)));

        return new BusinessCalendar(zone, hours, holidays);
    }

    public boolean isWorking(OffsetDateTime at) {
        ZonedDateTime local = at.atZoneSameInstant(zone);
        return intervalsOn(local.toLocalDate()).stream()
                .anyMatch(i -> !local.toLocalTime().isBefore(i.from()) && local.toLocalTime().isBefore(i.to()));
    }

    public OffsetDateTime addDuration(OffsetDateTime from, Duration duration) {
        ZonedDateTime cursor = from.atZoneSameInstant(zone);
        Duration remaining = duration;

        // Guard: 5 working years is far past any sane SLA and stops a malformed
        // calendar (no working hours at all) from looping forever.
        for (int day = 0; day < 1825; day++) {
            LocalDate date = cursor.toLocalDate();
            for (Interval interval : intervalsOn(date)) {
                ZonedDateTime open = ZonedDateTime.of(date, interval.from(), zone);
                ZonedDateTime close = ZonedDateTime.of(date, interval.to(), zone);

                ZonedDateTime start = cursor.isAfter(open) ? cursor : open;
                if (!start.isBefore(close)) {
                    continue;
                }
                Duration available = Duration.between(start, close);
                if (available.compareTo(remaining) >= 0) {
                    return start.plus(remaining).toOffsetDateTime();
                }
                remaining = remaining.minus(available);
            }
            cursor = ZonedDateTime.of(date.plusDays(1), LocalTime.MIN, zone);
        }
        throw new IllegalStateException(
                "Could not consume " + duration + " within 5 years — check the calendar definition");
    }

    private List<Interval> intervalsOn(LocalDate date) {
        if (holidays.contains(date)) {
            return List.of();
        }
        return workingHours.getOrDefault(date.getDayOfWeek(), List.of());
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./mvnw -q -pl case-management-core test -Dtest=BusinessCalendarTest`
Expected: PASS, all seven tests.

- [ ] **Step 5: Commit**

```bash
git add case-management-core/src
git commit -m "feat(sla): business calendar with weekend, holiday and DST handling"
```

---

### Task 21: SLA clocks with pause and resume

**Files:**
- Create: `case-management-core/src/main/java/org/casemgmt/repo/SlaRepository.java`
- Create: `case-management-core/src/main/java/org/casemgmt/sla/SlaService.java`
- Create: `case-management-core/src/main/java/org/casemgmt/sla/SlaSweeper.java`
- Create: `case-management-core/src/test/java/org/casemgmt/sla/SlaServiceTest.java`

**Interfaces:**
- Consumes: `BusinessCalendar` (Task 20), `EventPublisher` (Task 14), `CaseRepository` (Task 4)
- Produces:
  - `SlaRepository.insertPolicy/insertTarget/insertCalendar` (test + PoC seeding), `.targetsFor(String policyId) : List<TargetRow>`, `.insertRecord(SlaRecord)`, `.findByCase(String caseId) : List<SlaRecord>`, `.require(String id) : SlaRecord`, `.update(SlaRecord, long expectedVersion) : SlaRecord`, `.dueRecords(OffsetDateTime now) : List<SlaRecord>`
  - `SlaRecord(String id, String caseId, String targetId, String status, OffsetDateTime startedAt, OffsetDateTime dueAt, OffsetDateTime warnAt, OffsetDateTime pausedAt, String pausedReason, long pausedTotalSeconds, long version)`
  - `SlaService.startClocks(String caseId, String policyId, Actor)` — one record per target
  - `SlaService.pause(String caseId, String slaId, long expectedVersion, String reason, Actor) : SlaRecord`
  - `SlaService.resume(String caseId, String slaId, long expectedVersion, Actor) : SlaRecord` — shifts `dueAt`/`warnAt` by the pause length
  - `SlaSweeper.sweep() : int` — emits `sla.warning` / `sla.breached`, updates `CM_CASE.SLA_STATUS_`

- [ ] **Step 1: Write the failing test**

```java
package org.casemgmt.sla;

import org.casemgmt.OracleTestBase;
import org.casemgmt.domain.CasePriority;
import org.casemgmt.error.CaseConflictException;
import org.casemgmt.repo.*;
import org.casemgmt.service.Actor;
import org.casemgmt.service.CaseService;
import org.casemgmt.service.CaseServiceTest;
import org.casemgmt.service.TestServices;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class SlaServiceTest extends OracleTestBase {

    private SlaService sla;
    private SlaRepository slaRepo;
    private CaseService cases;
    private final Actor alice = new Actor("alice", List.of("handlers"));
    private String caseId;

    @BeforeEach
    void setUp() throws Exception {
        for (String t : List.of("CM_SLA_RECORD", "CM_SLA_TARGET", "CM_SLA_POLICY",
                "CM_BUSINESS_CALENDAR", "CM_WEBHOOK_DELIVERY", "CM_EVENT", "CM_AUDIT_LOG",
                "CM_MILESTONE", "CM_TASK", "CM_PLAN_ITEM", "CM_PARTICIPANT", "CM_CASE",
                "CM_PLAN_ITEM_DEF", "CM_CASE_DEF")) {
            jdbc().sql("DELETE FROM " + t).update();
        }
        String json = new String(getClass().getResourceAsStream("/definitions/test-definition.json")
                .readAllBytes(), StandardCharsets.UTF_8);
        new CaseDefinitionService(new CaseDefinitionRepository(jdbc())).deploy(json, "system");

        slaRepo = new SlaRepository(jdbc());
        slaRepo.insertCalendar("cal-nl", Map.of(
                "timezone", "Europe/Amsterdam",
                "workingHours", Map.of(
                        "MONDAY", List.of(Map.of("from", "00:00", "to", "23:59")),
                        "TUESDAY", List.of(Map.of("from", "00:00", "to", "23:59")),
                        "WEDNESDAY", List.of(Map.of("from", "00:00", "to", "23:59")),
                        "THURSDAY", List.of(Map.of("from", "00:00", "to", "23:59")),
                        "FRIDAY", List.of(Map.of("from", "00:00", "to", "23:59")),
                        "SATURDAY", List.of(Map.of("from", "00:00", "to", "23:59")),
                        "SUNDAY", List.of(Map.of("from", "00:00", "to", "23:59"))),
                "holidays", List.of()));
        slaRepo.insertPolicy("pol-1", "Standard", null, "cal-nl");
        slaRepo.insertTarget("tgt-first", "pol-1", "firstResponse", "First response",
                "PT4H", "PT3H", List.of("WAITING_ON_CUSTOMER"), List.of("EMIT_EVENT"));

        cases = TestServices.caseService(jdbc(), new CaseServiceTest.RecordingGateway());
        sla = TestServices.slaService(jdbc());
        caseId = cases.create("widget-review", "t1", null, "T", CasePriority.MEDIUM, Map.of(), alice).id();
    }

    @Test
    void startingClocksCreatesOneRecordPerTarget() {
        sla.startClocks(caseId, "pol-1", alice);

        assertThat(slaRepo.findByCase(caseId)).hasSize(1)
                .allSatisfy(r -> {
                    assertThat(r.status()).isEqualTo("RUNNING");
                    assertThat(r.dueAt()).isAfter(OffsetDateTime.now());
                    assertThat(r.warnAt()).isBefore(r.dueAt());
                });
    }

    @Test
    void pauseRecordsWhenTheClockStopped() {
        sla.startClocks(caseId, "pol-1", alice);
        SlaRecord record = slaRepo.findByCase(caseId).get(0);

        SlaRecord paused = sla.pause(caseId, record.id(), record.version(), "WAITING_ON_CUSTOMER", alice);

        assertThat(paused.status()).isEqualTo("PAUSED");
        assertThat(paused.pausedAt()).isNotNull();
        assertThat(paused.pausedReason()).isEqualTo("WAITING_ON_CUSTOMER");
    }

    @Test
    void resumeShiftsTheDeadlineByThePauseLength() {
        sla.startClocks(caseId, "pol-1", alice);
        SlaRecord record = slaRepo.findByCase(caseId).get(0);
        OffsetDateTime originalDue = record.dueAt();

        SlaRecord paused = sla.pause(caseId, record.id(), record.version(), "WAITING_ON_CUSTOMER", alice);
        // Simulate two hours of paused time.
        jdbc().sql("UPDATE CM_SLA_RECORD SET PAUSED_AT_ = PAUSED_AT_ - INTERVAL '2' HOUR WHERE ID_ = :id")
                .param("id", record.id()).update();
        SlaRecord reloaded = slaRepo.require(record.id());

        SlaRecord resumed = sla.resume(caseId, reloaded.id(), reloaded.version(), alice);

        assertThat(resumed.status()).isEqualTo("RUNNING");
        assertThat(resumed.pausedTotalSeconds()).isBetween(7000L, 7400L);
        assertThat(Duration.between(originalDue, resumed.dueAt()).toMinutes()).isBetween(110L, 130L);
    }

    @Test
    void pausingAnAlreadyPausedClockConflicts() {
        sla.startClocks(caseId, "pol-1", alice);
        SlaRecord record = slaRepo.findByCase(caseId).get(0);
        SlaRecord paused = sla.pause(caseId, record.id(), record.version(), "reason", alice);

        assertThatThrownBy(() -> sla.pause(caseId, paused.id(), paused.version(), "again", alice))
                .isInstanceOf(CaseConflictException.class);
    }

    @Test
    void sweeperEmitsWarningThenBreach() {
        sla.startClocks(caseId, "pol-1", alice);
        jdbc().sql("UPDATE CM_SLA_RECORD SET WARN_AT_ = SYSTIMESTAMP - INTERVAL '1' MINUTE").update();

        TestServices.slaSweeper(jdbc()).sweep();

        assertThat(eventTypes()).anySatisfy(t -> assertThat(t).endsWith("case.sla.warning"));
        assertThat(jdbc().sql("SELECT SLA_STATUS_ FROM CM_CASE WHERE ID_ = :id")
                .param("id", caseId).query(String.class).single()).isEqualTo("WARNING");

        jdbc().sql("UPDATE CM_SLA_RECORD SET DUE_AT_ = SYSTIMESTAMP - INTERVAL '1' MINUTE").update();
        TestServices.slaSweeper(jdbc()).sweep();

        assertThat(eventTypes()).anySatisfy(t -> assertThat(t).endsWith("case.sla.breached"));
        assertThat(slaRepo.findByCase(caseId).get(0).status()).isEqualTo("BREACHED");
    }

    @Test
    void pausedClocksAreNeverSweptIntoBreach() {
        sla.startClocks(caseId, "pol-1", alice);
        SlaRecord record = slaRepo.findByCase(caseId).get(0);
        sla.pause(caseId, record.id(), record.version(), "WAITING_ON_CUSTOMER", alice);
        jdbc().sql("UPDATE CM_SLA_RECORD SET DUE_AT_ = SYSTIMESTAMP - INTERVAL '1' HOUR").update();

        TestServices.slaSweeper(jdbc()).sweep();

        assertThat(slaRepo.findByCase(caseId).get(0).status()).isEqualTo("PAUSED");
        assertThat(eventTypes()).noneSatisfy(t -> assertThat(t).endsWith("case.sla.breached"));
    }

    private List<String> eventTypes() {
        return jdbc().sql("SELECT TYPE_ FROM CM_EVENT ORDER BY SEQ_").query(String.class).list();
    }
}
```

Add `slaService(JdbcClient)` and `slaSweeper(JdbcClient)` factories to `TestServices`, following the existing pattern.

- [ ] **Step 2: Run it and watch it fail**

Run: `./mvnw -q -pl case-management-core test -Dtest=SlaServiceTest`
Expected: FAIL — `cannot find symbol: class SlaRepository`.

- [ ] **Step 3: Write the record and repository**

```java
package org.casemgmt.sla;

import java.time.OffsetDateTime;

public record SlaRecord(String id, String caseId, String targetId, String status,
                        OffsetDateTime startedAt, OffsetDateTime dueAt, OffsetDateTime warnAt,
                        OffsetDateTime pausedAt, String pausedReason, long pausedTotalSeconds,
                        long version) {}
```

```java
package org.casemgmt.repo;

import org.casemgmt.error.NotFoundException;
import org.casemgmt.error.OptimisticLockException;
import org.casemgmt.sla.SlaRecord;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public class SlaRepository {

    public record TargetRow(String id, String policyId, String targetKey, String name,
                            String durationIso, String warningIso, List<String> pausedStates,
                            List<String> breachActions) {}

    private static final String RECORD_COLUMNS = """
            ID_, CASE_ID_, TARGET_ID_, STATUS_, STARTED_AT_, DUE_AT_, WARN_AT_, PAUSED_AT_,
            PAUSED_REASON_, PAUSED_TOTAL_SECS_, VERSION_""";

    private final JdbcClient jdbc;

    public SlaRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public void insertCalendar(String id, Map<String, Object> definition) {
        jdbc.sql("INSERT INTO CM_BUSINESS_CALENDAR (ID_, NAME_, DEFINITION_JSON_) VALUES (:id, :id, :def)")
            .param("id", id).param("def", JsonCodec.toJson(definition)).update();
    }

    public void insertPolicy(String id, String name, String selector, String calendarId) {
        jdbc.sql("""
                INSERT INTO CM_SLA_POLICY (ID_, NAME_, SELECTOR_, CALENDAR_ID_)
                VALUES (:id, :name, :selector, :calendarId)""")
            .param("id", id).param("name", name).param("selector", selector)
            .param("calendarId", calendarId).update();
    }

    public void insertTarget(String id, String policyId, String targetKey, String name,
                             String durationIso, String warningIso,
                             List<String> pausedStates, List<String> breachActions) {
        jdbc.sql("""
                INSERT INTO CM_SLA_TARGET (ID_, POLICY_ID_, TARGET_KEY_, NAME_, DURATION_ISO_,
                    WARNING_ISO_, PAUSED_STATES_JSON_, BREACH_ACTIONS_JSON_)
                VALUES (:id, :policyId, :key, :name, :duration, :warning, :paused, :actions)""")
            .param("id", id).param("policyId", policyId).param("key", targetKey).param("name", name)
            .param("duration", durationIso).param("warning", warningIso)
            .param("paused", JsonCodec.toJson(pausedStates))
            .param("actions", JsonCodec.toJson(breachActions))
            .update();
    }

    public String calendarIdOf(String policyId) {
        return jdbc.sql("SELECT CALENDAR_ID_ FROM CM_SLA_POLICY WHERE ID_ = :id")
                .param("id", policyId).query(String.class).optional().orElse(null);
    }

    public Map<String, Object> calendarDefinition(String calendarId) {
        return jdbc.sql("SELECT DEFINITION_JSON_ FROM CM_BUSINESS_CALENDAR WHERE ID_ = :id")
                .param("id", calendarId).query(String.class).optional()
                .map(JsonCodec::toMap).orElse(Map.of());
    }

    public List<TargetRow> targetsFor(String policyId) {
        return jdbc.sql("""
                SELECT ID_, POLICY_ID_, TARGET_KEY_, NAME_, DURATION_ISO_, WARNING_ISO_,
                       PAUSED_STATES_JSON_, BREACH_ACTIONS_JSON_
                FROM CM_SLA_TARGET WHERE POLICY_ID_ = :id""")
            .param("id", policyId)
            .query((rs, n) -> new TargetRow(rs.getString("ID_"), rs.getString("POLICY_ID_"),
                    rs.getString("TARGET_KEY_"), rs.getString("NAME_"), rs.getString("DURATION_ISO_"),
                    rs.getString("WARNING_ISO_"),
                    JsonCodec.toList(rs.getString("PAUSED_STATES_JSON_")),
                    JsonCodec.toList(rs.getString("BREACH_ACTIONS_JSON_"))))
            .list();
    }

    public void insertRecord(SlaRecord r) {
        jdbc.sql("""
                INSERT INTO CM_SLA_RECORD (ID_, CASE_ID_, TARGET_ID_, STATUS_, STARTED_AT_, DUE_AT_,
                    WARN_AT_, PAUSED_TOTAL_SECS_, VERSION_)
                VALUES (:id, :caseId, :targetId, :status, :startedAt, :dueAt, :warnAt, 0, 0)""")
            .param("id", r.id()).param("caseId", r.caseId()).param("targetId", r.targetId())
            .param("status", r.status()).param("startedAt", r.startedAt())
            .param("dueAt", r.dueAt()).param("warnAt", r.warnAt())
            .update();
    }

    public List<SlaRecord> findByCase(String caseId) {
        return jdbc.sql("SELECT " + RECORD_COLUMNS + " FROM CM_SLA_RECORD WHERE CASE_ID_ = :caseId")
                .param("caseId", caseId).query(SlaRepository::mapRecord).list();
    }

    public SlaRecord require(String id) {
        return jdbc.sql("SELECT " + RECORD_COLUMNS + " FROM CM_SLA_RECORD WHERE ID_ = :id")
                .param("id", id).query(SlaRepository::mapRecord).optional()
                .orElseThrow(() -> new NotFoundException("SlaRecord", id));
    }

    public SlaRecord update(SlaRecord r, long expectedVersion) {
        int rows = jdbc.sql("""
                UPDATE CM_SLA_RECORD SET STATUS_ = :status, DUE_AT_ = :dueAt, WARN_AT_ = :warnAt,
                    PAUSED_AT_ = :pausedAt, PAUSED_REASON_ = :reason,
                    PAUSED_TOTAL_SECS_ = :pausedTotal,
                    MET_AT_ = CASE WHEN :status = 'MET' THEN SYSTIMESTAMP ELSE MET_AT_ END,
                    BREACHED_AT_ = CASE WHEN :status = 'BREACHED' THEN SYSTIMESTAMP ELSE BREACHED_AT_ END,
                    VERSION_ = VERSION_ + 1
                WHERE ID_ = :id AND VERSION_ = :expected""")
            .param("status", r.status()).param("dueAt", r.dueAt()).param("warnAt", r.warnAt())
            .param("pausedAt", r.pausedAt()).param("reason", r.pausedReason())
            .param("pausedTotal", r.pausedTotalSeconds())
            .param("id", r.id()).param("expected", expectedVersion)
            .update();
        if (rows == 0) throw new OptimisticLockException("SlaRecord", r.id(), expectedVersion);
        return require(r.id());
    }

    /** Running clocks past their warning or breach threshold — the sweeper's work list. */
    public List<SlaRecord> dueRecords(OffsetDateTime now) {
        return jdbc.sql("""
                SELECT """ + RECORD_COLUMNS + """
                 FROM CM_SLA_RECORD
                WHERE STATUS_ = 'RUNNING' AND (DUE_AT_ <= :now OR WARN_AT_ <= :now)""")
            .param("now", now).query(SlaRepository::mapRecord).list();
    }

    private static SlaRecord mapRecord(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new SlaRecord(rs.getString("ID_"), rs.getString("CASE_ID_"), rs.getString("TARGET_ID_"),
                rs.getString("STATUS_"),
                rs.getObject("STARTED_AT_", OffsetDateTime.class),
                rs.getObject("DUE_AT_", OffsetDateTime.class),
                rs.getObject("WARN_AT_", OffsetDateTime.class),
                rs.getObject("PAUSED_AT_", OffsetDateTime.class),
                rs.getString("PAUSED_REASON_"), rs.getLong("PAUSED_TOTAL_SECS_"),
                rs.getLong("VERSION_"));
    }
}
```

- [ ] **Step 4: Write the service and sweeper**

```java
package org.casemgmt.sla;

import org.casemgmt.domain.CaseIds;
import org.casemgmt.domain.CaseInstance;
import org.casemgmt.error.CaseConflictException;
import org.casemgmt.error.OptimisticLockException;
import org.casemgmt.repo.CaseRepository;
import org.casemgmt.repo.SlaRepository;
import org.casemgmt.service.Actor;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public class SlaService {

    private final SlaRepository sla;
    private final CaseRepository cases;

    public SlaService(SlaRepository sla, CaseRepository cases) {
        this.sla = sla;
        this.cases = cases;
    }

    @Transactional
    public void startClocks(String caseId, String policyId, Actor actor) {
        CaseInstance c = cases.require(caseId);
        BusinessCalendar calendar = calendarFor(policyId);
        OffsetDateTime now = OffsetDateTime.now();

        for (SlaRepository.TargetRow target : sla.targetsFor(policyId)) {
            OffsetDateTime dueAt = calendar.addDuration(now, Duration.parse(target.durationIso()));
            OffsetDateTime warnAt = target.warningIso() == null ? null
                    : calendar.addDuration(now, Duration.parse(target.warningIso()));
            sla.insertRecord(new SlaRecord(CaseIds.newId(), caseId, target.id(), "RUNNING",
                    now, dueAt, warnAt, null, null, 0L, 0L));
        }
    }

    @Transactional
    public SlaRecord pause(String caseId, String slaId, long expectedVersion, String reason, Actor actor) {
        SlaRecord record = sla.require(slaId);
        if (!"RUNNING".equals(record.status())) {
            throw new CaseConflictException("sla-not-running",
                    "SLA clock is " + record.status(), List.of("resume"));
        }
        return save(new SlaRecord(record.id(), record.caseId(), record.targetId(), "PAUSED",
                record.startedAt(), record.dueAt(), record.warnAt(), OffsetDateTime.now(), reason,
                record.pausedTotalSeconds(), record.version()), expectedVersion);
    }

    @Transactional
    public SlaRecord resume(String caseId, String slaId, long expectedVersion, Actor actor) {
        SlaRecord record = sla.require(slaId);
        if (!"PAUSED".equals(record.status())) {
            throw new CaseConflictException("sla-not-paused",
                    "SLA clock is " + record.status(), List.of("pause"));
        }
        long pausedSeconds = Duration.between(record.pausedAt(), OffsetDateTime.now()).toSeconds();

        // Resuming shifts both thresholds by exactly the time spent paused.
        return save(new SlaRecord(record.id(), record.caseId(), record.targetId(), "RUNNING",
                record.startedAt(),
                record.dueAt().plusSeconds(pausedSeconds),
                record.warnAt() == null ? null : record.warnAt().plusSeconds(pausedSeconds),
                null, null, record.pausedTotalSeconds() + pausedSeconds, record.version()),
                expectedVersion);
    }

    public List<SlaRecord> forCase(String caseId) {
        return sla.findByCase(caseId);
    }

    private BusinessCalendar calendarFor(String policyId) {
        String calendarId = sla.calendarIdOf(policyId);
        Map<String, Object> definition = calendarId == null ? Map.of() : sla.calendarDefinition(calendarId);
        return BusinessCalendar.fromJson(definition.isEmpty()
                ? Map.of("timezone", "UTC", "workingHours", Map.of(
                        "MONDAY", List.of(Map.of("from", "00:00", "to", "23:59")),
                        "TUESDAY", List.of(Map.of("from", "00:00", "to", "23:59")),
                        "WEDNESDAY", List.of(Map.of("from", "00:00", "to", "23:59")),
                        "THURSDAY", List.of(Map.of("from", "00:00", "to", "23:59")),
                        "FRIDAY", List.of(Map.of("from", "00:00", "to", "23:59")),
                        "SATURDAY", List.of(Map.of("from", "00:00", "to", "23:59")),
                        "SUNDAY", List.of(Map.of("from", "00:00", "to", "23:59"))))
                : definition);
    }

    private SlaRecord save(SlaRecord record, long expectedVersion) {
        try {
            return sla.update(record, expectedVersion);
        } catch (OptimisticLockException e) {
            throw new CaseConflictException("version-conflict", e.getMessage(), List.of());
        }
    }
}
```

```java
package org.casemgmt.sla;

import org.casemgmt.domain.CaseIds;
import org.casemgmt.domain.CaseInstance;
import org.casemgmt.event.CaseEvent;
import org.casemgmt.event.EventPublisher;
import org.casemgmt.event.EventTypes;
import org.casemgmt.repo.CaseRepository;
import org.casemgmt.repo.SlaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Polls (STATUS_, WARN_AT_) and (STATUS_, DUE_AT_) for running clocks past threshold.
 * Paused clocks are excluded by the query, which is the whole point of pause/resume.
 */
public class SlaSweeper {

    private final SlaRepository sla;
    private final CaseRepository cases;
    private final EventPublisher publisher;

    public SlaSweeper(SlaRepository sla, CaseRepository cases, EventPublisher publisher) {
        this.sla = sla;
        this.cases = cases;
        this.publisher = publisher;
    }

    @Transactional
    public int sweep() {
        OffsetDateTime now = OffsetDateTime.now();
        int handled = 0;

        for (SlaRecord record : sla.dueRecords(now)) {
            CaseInstance c = cases.require(record.caseId());

            if (record.dueAt() != null && !record.dueAt().isAfter(now)) {
                sla.update(breached(record), record.version());
                emit(c, EventTypes.SLA_BREACHED, record);
                updateCaseStatus(c, "BREACHED");
            } else if (record.warnAt() != null && !record.warnAt().isAfter(now)) {
                emit(c, EventTypes.SLA_WARNING, record);
                updateCaseStatus(c, "WARNING");
                // Clear WARN_AT_ so the warning fires once, not on every sweep.
                sla.update(warned(record), record.version());
            }
            handled++;
        }
        return handled;
    }

    private SlaRecord breached(SlaRecord r) {
        return new SlaRecord(r.id(), r.caseId(), r.targetId(), "BREACHED", r.startedAt(),
                r.dueAt(), r.warnAt(), r.pausedAt(), r.pausedReason(), r.pausedTotalSeconds(), r.version());
    }

    private SlaRecord warned(SlaRecord r) {
        return new SlaRecord(r.id(), r.caseId(), r.targetId(), r.status(), r.startedAt(),
                r.dueAt(), null, r.pausedAt(), r.pausedReason(), r.pausedTotalSeconds(), r.version());
    }

    private void updateCaseStatus(CaseInstance c, String status) {
        CaseInstance updated = new CaseInstance(c.id(), c.engineId(), c.tenantId(), c.caseDefId(),
                c.caseDefKey(), c.caseDefVersion(), c.businessKey(), c.title(), c.state(),
                c.priority(), c.assignee(), c.queueId(), c.initiator(), status, c.outcome(),
                c.cancelReason(), c.variables(), c.version(), c.createdAt(), c.updatedAt(), c.closedAt());
        cases.update(updated, c.version());
    }

    private void emit(CaseInstance c, String type, SlaRecord record) {
        publisher.publish(new CaseEvent(CaseIds.newId(), publisher.engineId(), type, c.id(),
                c.tenantId(), OffsetDateTime.now(),
                Map.of("slaId", record.id(), "targetId", record.targetId(),
                        "dueAt", String.valueOf(record.dueAt()))));
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./mvnw -q -pl case-management-core test -Dtest=SlaServiceTest`
Expected: PASS, all six tests.

- [ ] **Step 6: Commit**

```bash
git add case-management-core/src
git commit -m "feat(sla): SLA clocks with pause/resume and a warning/breach sweeper"
```

---

## Phase 6 — REST API

### Task 22: REST foundation — problem+json, ETag, idempotency

**Files:**
- Create: `case-management-rest/src/main/java/org/casemgmt/rest/error/ProblemDetailHandler.java`
- Create: `case-management-rest/src/main/java/org/casemgmt/rest/filter/ETagSupport.java`
- Create: `case-management-rest/src/main/java/org/casemgmt/rest/filter/IdempotencySupport.java`
- Create: `case-management-core/src/main/java/org/casemgmt/repo/IdempotencyRepository.java`
- Create: `case-management-core/src/main/java/org/casemgmt/error/PreconditionRequiredException.java`
- Create: `case-management-core/src/main/java/org/casemgmt/error/IdempotencyConflictException.java`
- Create: `case-management-rest/src/test/java/org/casemgmt/rest/ErrorMappingTest.java`
- Create: `case-management-core/src/test/java/org/casemgmt/repo/IdempotencyRepositoryTest.java`

**Interfaces:**
- Consumes: all core exceptions (Tasks 4, 15, 17)
- Produces:
  - `ETagSupport.parse(String ifMatchHeader) : long` — throws `PreconditionRequiredException` when absent, `IllegalArgumentException` when malformed
  - `ETagSupport.format(long version) : String` → `"17"` (strong tag, quoted)
  - `IdempotencyRepository.begin(String key, String scope, String requestHash) : Optional<StoredResponse>` — inserts `IN_PROGRESS`, or returns the stored response on replay; throws `IdempotencyConflictException` on hash mismatch or an in-flight duplicate
  - `IdempotencyRepository.complete(String key, String scope, int status, String responseJson)`
  - `ProblemDetailHandler` — `@RestControllerAdvice` mapping exceptions to RFC 9457 bodies

**Status mapping** (spec §6.5):

| Exception | Status | `code` |
|---|---|---|
| `NotFoundException` | 404 | `not-found` |
| `CaseConflictException` | 409 | the exception's own `code`, plus `availableActions` in the body |
| `OptimisticLockException` | 412 | `version-conflict` |
| `PreconditionRequiredException` | 428 | `if-match-required` |
| `FormValidationException` | 422 | `form-invalid`, plus `violations[]` with JSON Pointers |
| `IdempotencyConflictException` | 409 | `idempotency-conflict` |
| `CriterionEvaluationException`, `PlanModelLoopException` | 500 | `model-error` |

- [ ] **Step 1: Write the failing tests**

```java
package org.casemgmt.repo;

import org.casemgmt.OracleTestBase;
import org.casemgmt.error.IdempotencyConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class IdempotencyRepositoryTest extends OracleTestBase {

    private IdempotencyRepository repo;

    @BeforeEach
    void setUp() {
        jdbc().sql("DELETE FROM CM_IDEMPOTENCY_KEY").update();
        repo = new IdempotencyRepository(jdbc());
    }

    @Test
    void firstCallProceeds() {
        assertThat(repo.begin("k1", "POST /cases", "hash-a")).isEmpty();
    }

    @Test
    void replayWithTheSameHashReturnsTheStoredResponse() {
        repo.begin("k1", "POST /cases", "hash-a");
        repo.complete("k1", "POST /cases", 201, "{\"id\":\"eng-a:1\"}");

        var replay = repo.begin("k1", "POST /cases", "hash-a");

        assertThat(replay).isPresent();
        assertThat(replay.get().status()).isEqualTo(201);
        assertThat(replay.get().body()).contains("eng-a:1");
    }

    @Test
    void sameKeyWithADifferentPayloadConflicts() {
        repo.begin("k1", "POST /cases", "hash-a");
        repo.complete("k1", "POST /cases", 201, "{}");

        assertThatThrownBy(() -> repo.begin("k1", "POST /cases", "hash-b"))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void anInFlightDuplicateConflicts() {
        repo.begin("k1", "POST /cases", "hash-a");   // never completed

        assertThatThrownBy(() -> repo.begin("k1", "POST /cases", "hash-a"))
                .isInstanceOf(IdempotencyConflictException.class)
                .hasMessageContaining("in progress");
    }

    @Test
    void keysAreScopedPerOperation() {
        repo.begin("k1", "POST /cases", "hash-a");
        assertThat(repo.begin("k1", "POST /cases/bulk", "hash-a")).isEmpty();
    }
}
```

```java
package org.casemgmt.rest;

import org.casemgmt.error.*;
import org.casemgmt.rest.error.ProblemDetailHandler;
import org.casemgmt.rest.filter.ETagSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class ErrorMappingTest {

    private final ProblemDetailHandler handler = new ProblemDetailHandler();

    @Test
    void notFoundMapsTo404() {
        ProblemDetail problem = handler.onNotFound(new NotFoundException("Case", "eng-a:1"));
        assertThat(problem.getStatus()).isEqualTo(404);
        assertThat(problem.getProperties()).containsEntry("code", "not-found");
    }

    @Test
    void conflictCarriesTheAvailableActions() {
        ProblemDetail problem = handler.onConflict(new CaseConflictException(
                "required-items-open", "blocked", List.of("cancel", "update")));

        assertThat(problem.getStatus()).isEqualTo(409);
        assertThat(problem.getProperties()).containsEntry("code", "required-items-open");
        assertThat((List<?>) problem.getProperties().get("availableActions"))
                .containsExactly("cancel", "update");
    }

    @Test
    void staleVersionMapsTo412() {
        ProblemDetail problem = handler.onOptimisticLock(
                new OptimisticLockException("Case", "eng-a:1", 3));
        assertThat(problem.getStatus()).isEqualTo(412);
    }

    @Test
    void missingIfMatchMapsTo428() {
        ProblemDetail problem = handler.onPreconditionRequired(new PreconditionRequiredException());
        assertThat(problem.getStatus()).isEqualTo(428);
    }

    @Test
    void formViolationsMapTo422WithPointers() {
        ProblemDetail problem = handler.onFormInvalid(new FormValidationException(
                List.of(new FormValidationException.Violation("/outcome", "must be one of [approve, reject]"))));

        assertThat(problem.getStatus()).isEqualTo(422);
        assertThat(problem.getProperties()).containsKey("violations");
    }

    @Test
    void etagsRoundTrip() {
        assertThat(ETagSupport.format(17)).isEqualTo("\"17\"");
        assertThat(ETagSupport.parse("\"17\"")).isEqualTo(17L);
        assertThat(ETagSupport.parse("W/\"17\"")).isEqualTo(17L);
    }

    @Test
    void aMissingIfMatchHeaderIsRejectedRatherThanAssumed() {
        assertThatThrownBy(() -> ETagSupport.parse(null))
                .isInstanceOf(PreconditionRequiredException.class);
    }
}
```

- [ ] **Step 2: Run them and watch them fail**

Run: `./mvnw -q -pl case-management-core test -Dtest=IdempotencyRepositoryTest`
Expected: FAIL — `cannot find symbol: class IdempotencyRepository`.

- [ ] **Step 3: Write the two exceptions and the idempotency repository**

```java
package org.casemgmt.error;

public class PreconditionRequiredException extends RuntimeException {
    public PreconditionRequiredException() {
        super("This mutation requires an If-Match header carrying the resource's current ETag");
    }
}
```

```java
package org.casemgmt.error;

public class IdempotencyConflictException extends RuntimeException {
    public IdempotencyConflictException(String message) {
        super(message);
    }
}
```

```java
package org.casemgmt.repo;

import org.casemgmt.error.IdempotencyConflictException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.Optional;

/**
 * Idempotency-Key handling (spec §6.4). The row is inserted BEFORE the work happens,
 * so a concurrent duplicate collides on the primary key rather than doing the work twice.
 */
public class IdempotencyRepository {

    public record StoredResponse(int status, String body) {}

    private static final String IN_PROGRESS = "__IN_PROGRESS__";

    private final JdbcClient jdbc;

    public IdempotencyRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public Optional<StoredResponse> begin(String key, String scope, String requestHash) {
        try {
            jdbc.sql("""
                    INSERT INTO CM_IDEMPOTENCY_KEY (KEY_, SCOPE_, REQUEST_HASH_, RESPONSE_STATUS_,
                        RESPONSE_JSON_)
                    VALUES (:key, :scope, :hash, 0, :inProgress)""")
                .param("key", key).param("scope", scope).param("hash", requestHash)
                .param("inProgress", IN_PROGRESS)
                .update();
            return Optional.empty();
        } catch (DuplicateKeyException e) {
            return Optional.of(replay(key, scope, requestHash));
        }
    }

    private StoredResponse replay(String key, String scope, String requestHash) {
        var row = jdbc.sql("""
                SELECT REQUEST_HASH_, RESPONSE_STATUS_, RESPONSE_JSON_ FROM CM_IDEMPOTENCY_KEY
                WHERE KEY_ = :key AND SCOPE_ = :scope""")
            .param("key", key).param("scope", scope)
            .query((rs, n) -> new String[]{rs.getString("REQUEST_HASH_"),
                    String.valueOf(rs.getInt("RESPONSE_STATUS_")), rs.getString("RESPONSE_JSON_")})
            .single();

        if (!row[0].equals(requestHash)) {
            throw new IdempotencyConflictException(
                    "Idempotency key " + key + " was already used with a different payload");
        }
        if (IN_PROGRESS.equals(row[2])) {
            throw new IdempotencyConflictException(
                    "A request with idempotency key " + key + " is still in progress — retry shortly");
        }
        return new StoredResponse(Integer.parseInt(row[1]), row[2]);
    }

    public void complete(String key, String scope, int status, String responseJson) {
        jdbc.sql("""
                UPDATE CM_IDEMPOTENCY_KEY SET RESPONSE_STATUS_ = :status, RESPONSE_JSON_ = :body
                WHERE KEY_ = :key AND SCOPE_ = :scope""")
            .param("status", status).param("body", responseJson)
            .param("key", key).param("scope", scope)
            .update();
    }

    /** Retention: 48h, per spec §6.4. Call from a scheduled job. */
    public int purgeOlderThanHours(int hours) {
        return jdbc.sql("""
                DELETE FROM CM_IDEMPOTENCY_KEY
                WHERE CREATED_AT_ < SYSTIMESTAMP - NUMTODSINTERVAL(:hours, 'HOUR')""")
            .param("hours", hours).update();
    }
}
```

- [ ] **Step 4: Write the ETag and idempotency support classes**

```java
package org.casemgmt.rest.filter;

import org.casemgmt.error.PreconditionRequiredException;

public final class ETagSupport {

    private ETagSupport() {}

    /** ETag is the row's VERSION_ rendered as a strong tag (spec §6.3). */
    public static String format(long version) {
        return "\"" + version + "\"";
    }

    public static long parse(String ifMatchHeader) {
        if (ifMatchHeader == null || ifMatchHeader.isBlank()) {
            throw new PreconditionRequiredException();
        }
        String value = ifMatchHeader.trim();
        if (value.startsWith("W/")) {
            value = value.substring(2);
        }
        value = value.replace("\"", "").trim();
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Malformed If-Match header: " + ifMatchHeader, e);
        }
    }
}
```

```java
package org.casemgmt.rest.filter;

import org.casemgmt.repo.IdempotencyRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import java.util.function.Supplier;

public class IdempotencySupport {

    public record Result<T>(T value, boolean replayed) {}

    private final IdempotencyRepository repo;

    public IdempotencySupport(IdempotencyRepository repo) {
        this.repo = repo;
    }

    /**
     * Runs the operation once per (key, scope). A retry with the same payload replays
     * the original response; the same key with a different payload is a client bug (409).
     */
    public <T> Result<T> execute(String key, String scope, String rawBody,
                                 Supplier<T> operation,
                                 java.util.function.Function<String, T> deserializer,
                                 java.util.function.Function<T, String> serializer,
                                 int successStatus) {
        if (key == null || key.isBlank()) {
            return new Result<>(operation.get(), false);
        }
        String hash = sha256(rawBody);
        Optional<IdempotencyRepository.StoredResponse> stored = repo.begin(key, scope, hash);
        if (stored.isPresent()) {
            return new Result<>(deserializer.apply(stored.get().body()), true);
        }
        T value = operation.get();
        repo.complete(key, scope, successStatus, serializer.apply(value));
        return new Result<>(value, false);
    }

    private static String sha256(String body) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest((body == null ? "" : body).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Could not hash request body", e);
        }
    }
}
```

- [ ] **Step 5: Write the problem+json handler**

```java
package org.casemgmt.rest.error;

import org.casemgmt.error.*;
import org.casemgmt.rules.CriterionEvaluationException;
import org.casemgmt.rules.PlanModelLoopException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.util.List;
import java.util.Map;

/** RFC 9457 responses with a stable `code` field frontends can switch on (spec §6.5). */
@RestControllerAdvice
public class ProblemDetailHandler {

    private static final String TYPE_BASE = "https://casemgmt.org/problems/";

    @ExceptionHandler(NotFoundException.class)
    public ProblemDetail onNotFound(NotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "not-found", e.getMessage(), Map.of());
    }

    @ExceptionHandler(CaseConflictException.class)
    public ProblemDetail onConflict(CaseConflictException e) {
        return problem(HttpStatus.CONFLICT, e.code(), e.getMessage(),
                Map.of("availableActions", e.availableActions()));
    }

    @ExceptionHandler(OptimisticLockException.class)
    public ProblemDetail onOptimisticLock(OptimisticLockException e) {
        return problem(HttpStatus.PRECONDITION_FAILED, "version-conflict", e.getMessage(), Map.of());
    }

    @ExceptionHandler(PreconditionRequiredException.class)
    public ProblemDetail onPreconditionRequired(PreconditionRequiredException e) {
        return problem(HttpStatus.PRECONDITION_REQUIRED, "if-match-required", e.getMessage(), Map.of());
    }

    @ExceptionHandler(FormValidationException.class)
    public ProblemDetail onFormInvalid(FormValidationException e) {
        List<Map<String, String>> violations = e.violations().stream()
                .map(v -> Map.of("pointer", v.pointer(), "message", v.message()))
                .toList();
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "form-invalid",
                "Payload does not satisfy the form schema", Map.of("violations", violations));
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ProblemDetail onIdempotencyConflict(IdempotencyConflictException e) {
        return problem(HttpStatus.CONFLICT, "idempotency-conflict", e.getMessage(), Map.of());
    }

    @ExceptionHandler({CriterionEvaluationException.class, PlanModelLoopException.class})
    public ProblemDetail onModelError(RuntimeException e) {
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "model-error", e.getMessage(), Map.of());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail onBadRequest(IllegalArgumentException e) {
        return problem(HttpStatus.BAD_REQUEST, "invalid-request", e.getMessage(), Map.of());
    }

    private ProblemDetail problem(HttpStatus status, String code, String detail,
                                  Map<String, Object> extras) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(TYPE_BASE + code));
        problem.setProperty("code", code);
        extras.forEach(problem::setProperty);
        return problem;
    }
}
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./mvnw -q -pl case-management-core,case-management-rest test -Dtest='IdempotencyRepositoryTest,ErrorMappingTest'`
Expected: PASS, all twelve tests.

- [ ] **Step 7: Commit**

```bash
git add case-management-core/src case-management-rest/src
git commit -m "feat(rest): problem+json errors, ETag parsing and idempotency support"
```

---

### Task 23: ActionPolicy and availableActions

**Files:**
- Create: `case-management-rest/src/main/java/org/casemgmt/rest/policy/AvailableAction.java`
- Create: `case-management-rest/src/main/java/org/casemgmt/rest/policy/ActionPolicy.java`
- Create: `case-management-rest/src/test/java/org/casemgmt/rest/policy/ActionPolicyTest.java`

**Interfaces:**
- Consumes: `CaseSnapshot`, `StageCompletion` (Tasks 8–9); `CaseTask`, `PlanItem` (Task 3); `ParticipantRepository.rolesOf` (Task 6)
- Produces:
  - `AvailableAction(String action, String href, String method, String formKey)`
  - `ActionPolicy.listForCase(CaseSnapshot, Set<String> callerRoles) : List<AvailableAction>`
  - `ActionPolicy.listForPlanItem(CaseSnapshot, PlanItem, Set<String> callerRoles) : List<AvailableAction>`
  - `ActionPolicy.listForTask(CaseTask, Actor, Set<String> callerRoles) : List<AvailableAction>`
  - `ActionPolicy.assertAllowed(CaseSnapshot, Set<String> callerRoles, String action)` — throws `CaseConflictException` listing what *is* allowed

**The rule that matters:** `list` and `assertAllowed` read the same rule table. Every mutation endpoint calls `assertAllowed`; every read populates `availableActions[]` from `list`. That is what stops the projection and the enforcement from drifting (spec §4.5).

- [ ] **Step 1: Write the failing test**

```java
package org.casemgmt.rest.policy;

import org.casemgmt.domain.*;
import org.casemgmt.error.CaseConflictException;
import org.casemgmt.rules.CaseSnapshot;
import org.casemgmt.rules.PlanModelFixtures;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.casemgmt.rules.PlanModelFixtures.*;

class ActionPolicyTest {

    private final ActionPolicy policy = new ActionPolicy();

    private CaseSnapshot activeCaseWithOpenRequiredItem() {
        CaseDefinition def = definition(
                def("task", PlanItemType.HUMAN_TASK, null, false, true, false, List.of(), List.of(), 10));
        return snapshot(def, List.of(item("pi-1", "task", PlanItemType.HUMAN_TASK,
                PlanItemState.ACTIVE)), Map.of());
    }

    private CaseSnapshot activeCaseFullyDone() {
        CaseDefinition def = definition(
                def("task", PlanItemType.HUMAN_TASK, null, false, true, false, List.of(), List.of(), 10));
        return snapshot(def, List.of(item("pi-1", "task", PlanItemType.HUMAN_TASK,
                PlanItemState.COMPLETED)), Map.of());
    }

    @Test
    void ownerOfAnActiveCaseMaySeeCloseOnlyWhenNothingBlocks() {
        assertThat(policy.listForCase(activeCaseWithOpenRequiredItem(), Set.of("owner")))
                .extracting(AvailableAction::action).doesNotContain("close");

        assertThat(policy.listForCase(activeCaseFullyDone(), Set.of("owner")))
                .extracting(AvailableAction::action).contains("close");
    }

    @Test
    void watchersGetNoMutatingActions() {
        assertThat(policy.listForCase(activeCaseFullyDone(), Set.of("watcher")))
                .extracting(AvailableAction::action)
                .doesNotContain("close", "cancel", "update");
    }

    @Test
    void actionsCarryEnoughToInvokeThemWithoutASecondCall() {
        assertThat(policy.listForCase(activeCaseFullyDone(), Set.of("owner")))
                .allSatisfy(a -> {
                    assertThat(a.href()).isNotBlank();
                    assertThat(a.method()).isIn("GET", "POST", "PATCH", "DELETE");
                });
    }

    @Test
    void assertAllowedRejectsAndNamesTheAlternatives() {
        assertThatThrownBy(() ->
                policy.assertAllowed(activeCaseWithOpenRequiredItem(), Set.of("owner"), "close"))
                .isInstanceOf(CaseConflictException.class)
                .hasMessageContaining("close");
    }

    @Test
    void assertAllowedAndListAgree() {
        CaseSnapshot snapshot = activeCaseFullyDone();
        Set<String> roles = Set.of("owner");

        for (AvailableAction action : policy.listForCase(snapshot, roles)) {
            assertThatNoException().isThrownBy(() ->
                    policy.assertAllowed(snapshot, roles, action.action()));
        }
    }

    @Test
    void planItemActionsFollowTheStateMachine() {
        CaseSnapshot snapshot = activeCaseWithOpenRequiredItem();
        PlanItem active = snapshot.planItems().get(0);

        assertThat(policy.listForPlanItem(snapshot, active, Set.of("handler")))
                .extracting(AvailableAction::action)
                .containsExactlyInAnyOrder("complete", "terminate");
    }

    @Test
    void unsyncedTasksDoNotOfferClaim() {
        CaseTask pending = new CaseTask("t-1", "eng-a:1", "pi-1", null, "T", null,
                TaskState.OPEN, null, null, List.of("g"), null, 50, null, null,
                CaseTask.EngineSync.PENDING, 0L, OffsetDateTime.now(), OffsetDateTime.now(), null);

        assertThat(policy.listForTask(pending, "alice", Set.of("handler")))
                .extracting(AvailableAction::action).doesNotContain("claim");
    }

    @Test
    void claimedTasksOfferCompleteToTheirAssigneeOnly() {
        CaseTask claimed = new CaseTask("t-1", "eng-a:1", "pi-1", "engine-1", "T", null,
                TaskState.CLAIMED, "alice", null, List.of("g"), "reviewForm", 50, null, null,
                CaseTask.EngineSync.SYNCED, 0L, OffsetDateTime.now(), OffsetDateTime.now(), null);

        assertThat(policy.listForTask(claimed, "alice", Set.of("handler")))
                .extracting(AvailableAction::action).contains("complete");
        assertThat(policy.listForTask(claimed, "bob", Set.of("handler")))
                .extracting(AvailableAction::action).doesNotContain("complete");
    }

    @Test
    void formKeyRidesAlongSoARendererKnowsWhichSchemaToFetch() {
        CaseTask claimed = new CaseTask("t-1", "eng-a:1", "pi-1", "engine-1", "T", null,
                TaskState.CLAIMED, "alice", null, List.of("g"), "reviewForm", 50, null, null,
                CaseTask.EngineSync.SYNCED, 0L, OffsetDateTime.now(), OffsetDateTime.now(), null);

        assertThat(policy.listForTask(claimed, "alice", Set.of("handler")))
                .filteredOn(a -> a.action().equals("complete"))
                .singleElement()
                .extracting(AvailableAction::formKey).isEqualTo("reviewForm");
    }
}
```

The test module needs `PlanModelFixtures`, so add the core test-jar dependency to `case-management-rest/pom.xml` exactly as in Task 10 Step 4.

- [ ] **Step 2: Run it and watch it fail**

Run: `./mvnw -q -pl case-management-rest test -Dtest=ActionPolicyTest`
Expected: FAIL — `cannot find symbol: class ActionPolicy`.

- [ ] **Step 3: Write the action record and policy**

```java
package org.casemgmt.rest.policy;

/**
 * Everything a renderer needs to invoke the action, on the first read: no second
 * call to discover how (spec §8 obligation 2).
 */
public record AvailableAction(String action, String href, String method, String formKey) {

    public static AvailableAction post(String action, String href) {
        return new AvailableAction(action, href, "POST", null);
    }

    public static AvailableAction post(String action, String href, String formKey) {
        return new AvailableAction(action, href, "POST", formKey);
    }

    public static AvailableAction patch(String action, String href) {
        return new AvailableAction(action, href, "PATCH", null);
    }
}
```

```java
package org.casemgmt.rest.policy;

import org.casemgmt.domain.*;
import org.casemgmt.error.CaseConflictException;
import org.casemgmt.rules.CaseSnapshot;
import org.casemgmt.rules.StageCompletion;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The single rule table behind both `availableActions[]` (projection) and
 * `assertAllowed` (enforcement). Two entry points, one set of rules — which is
 * what keeps the UI and the server from disagreeing (spec §4.5).
 */
public class ActionPolicy {

    private static final Set<String> MUTATING_ROLES = Set.of("owner", "handler");

    private final StageCompletion stageCompletion = new StageCompletion();

    public List<AvailableAction> listForCase(CaseSnapshot snapshot, Set<String> callerRoles) {
        List<AvailableAction> actions = new ArrayList<>();
        String base = "/cases/" + snapshot.caseInstance().id();
        CaseState state = snapshot.caseInstance().state();

        if (!mayMutate(callerRoles) || state == CaseState.CANCELLED) {
            return actions;
        }
        if (state == CaseState.ACTIVE) {
            actions.add(AvailableAction.patch("update", base));
            actions.add(AvailableAction.post("cancel", base + "/cancel"));
            if (stageCompletion.caseCanClose(snapshot)) {
                actions.add(AvailableAction.post("close", base + "/close"));
            }
        }
        return actions;
    }

    public List<AvailableAction> listForPlanItem(CaseSnapshot snapshot, PlanItem item,
                                                 Set<String> callerRoles) {
        List<AvailableAction> actions = new ArrayList<>();
        if (!mayMutate(callerRoles) || item.state().isEnded()) {
            return actions;
        }
        String base = "/cases/" + item.caseId() + "/plan-items/" + item.id();
        switch (item.state()) {
            case AVAILABLE -> {
                actions.add(AvailableAction.post("enable", base + "/enable"));
                actions.add(AvailableAction.post("terminate", base + "/terminate"));
            }
            case ENABLED -> {
                actions.add(AvailableAction.post("start", base + "/start"));
                actions.add(AvailableAction.post("terminate", base + "/terminate"));
            }
            case ACTIVE -> {
                actions.add(AvailableAction.post("complete", base + "/complete"));
                actions.add(AvailableAction.post("terminate", base + "/terminate"));
            }
            default -> { }
        }
        return actions;
    }

    public List<AvailableAction> listForTask(CaseTask task, String callerUserId, Set<String> callerRoles) {
        List<AvailableAction> actions = new ArrayList<>();
        String base = "/tasks/" + task.id();

        // A task the engine has not created yet cannot be claimed: the claim would fail.
        if (task.engineSync() != CaseTask.EngineSync.SYNCED) {
            return actions;
        }
        // Roles gate tasks exactly as they gate cases and plan items. Omitting this check
        // does NOT break the list/assertAllowed agreement property — assertAllowedOnTask
        // derives from this method, so the two stay consistent while both being wrong.
        // Consistency is not correctness; a watcher could claim any open task.
        //
        // Candidate-group membership is included because that is how work reaches someone
        // who is not yet a participant on the case at all.
        if (!mayMutate(callerRoles)
                && callerRoles.stream().noneMatch(task.candidateGroups()::contains)) {
            return actions;
        }
        if (task.state() == TaskState.OPEN) {
            actions.add(AvailableAction.post("claim", base + "/claim"));
        }
        if (task.state() == TaskState.CLAIMED && callerUserId.equals(task.assignee())) {
            actions.add(AvailableAction.post("complete", base + "/complete", task.formKey()));
        }
        return actions;
    }

    public void assertAllowed(CaseSnapshot snapshot, Set<String> callerRoles, String action) {
        List<AvailableAction> allowed = listForCase(snapshot, callerRoles);
        if (allowed.stream().noneMatch(a -> a.action().equals(action))) {
            throw new CaseConflictException("action-not-available",
                    "Action '" + action + "' is not available on case "
                            + snapshot.caseInstance().id() + " in state "
                            + snapshot.caseInstance().state(),
                    allowed.stream().map(AvailableAction::action).toList());
        }
    }

    public void assertAllowedOnTask(CaseTask task, String callerUserId, Set<String> callerRoles,
                                    String action) {
        List<AvailableAction> allowed = listForTask(task, callerUserId, callerRoles);
        if (allowed.stream().noneMatch(a -> a.action().equals(action))) {
            throw new CaseConflictException("action-not-available",
                    "Action '" + action + "' is not available on task " + task.id(),
                    allowed.stream().map(AvailableAction::action).toList());
        }
    }

    private boolean mayMutate(Set<String> callerRoles) {
        return callerRoles.stream().anyMatch(MUTATING_ROLES::contains);
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./mvnw -q -pl case-management-rest test -Dtest=ActionPolicyTest`
Expected: PASS, all nine tests.

- [ ] **Step 5: Commit**

```bash
git add case-management-rest/src case-management-rest/pom.xml
git commit -m "feat(rest): ActionPolicy driving availableActions and enforcement"
```

---

### Task 24: REST controllers

**Files:**
- Create: `case-management-rest/src/main/java/org/casemgmt/rest/dto/Dtos.java`
- Create: `case-management-rest/src/main/java/org/casemgmt/rest/CallerResolver.java`
- Create: `case-management-rest/src/main/java/org/casemgmt/rest/controller/CaseDefinitionController.java`
- Create: `case-management-rest/src/main/java/org/casemgmt/rest/controller/CaseController.java`
- Create: `case-management-rest/src/main/java/org/casemgmt/rest/controller/PlanItemController.java`
- Create: `case-management-rest/src/main/java/org/casemgmt/rest/controller/TaskController.java`
- Create: `case-management-rest/src/main/java/org/casemgmt/rest/controller/CollaborationController.java`
- Create: `case-management-rest/src/main/java/org/casemgmt/rest/controller/EventController.java`
- Create: `case-management-rest/src/main/java/org/casemgmt/rest/controller/SlaController.java`
- Create: `case-management-poc-app/src/test/java/org/casemgmt/poc/CaseApiIT.java`

**Interfaces:**
- Consumes: every service from Phases 4–5; `ActionPolicy` (Task 23); `ETagSupport`, `IdempotencySupport` (Task 22)
- Produces: the 24 endpoints of spec §2.1, all under the base path `/case-api/v2` (matching `openapi-specs.md`'s declared server URL)
  - `CallerResolver.actor(Principal, Authentication) : Actor` — maps the authenticated user and its Operaton groups onto `Actor`
  - `CallerResolver.roles(String caseId, Actor) : Set<String>` — participant roles for `ActionPolicy`

**Cross-cutting rules every mutating endpoint follows:** read `If-Match` via `ETagSupport.parse` (428 when absent), call `policy.assertAllowed`, perform the service call, return the resource with a fresh `ETag` header and a populated `availableActions[]`.

- [ ] **Step 1: Write the failing integration test**

`case-management-poc-app/src/test/java/org/casemgmt/poc/CaseApiIT.java`:

```java
package org.casemgmt.poc;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CaseApiIT extends OracleBackedPocTest {

    @LocalServerPort int port;

    private RestClient client() {
        return RestClient.builder()
                .baseUrl("http://localhost:" + port + "/case-api/v2")
                .defaultHeaders(h -> h.setBasicAuth("alice", "alice"))
                .build();
    }

    private Map<String, Object> createCase() {
        return client().post().uri("/cases")
                .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("caseDefinitionKey", "complaint", "tenantId", "t1",
                        "title", "Broken widget", "priority", "HIGH",
                        "variables", Map.of("channel", "web")))
                .retrieve().body(Map.class);
    }

    @Test
    void createsACaseAndReturnsAnETagAndAvailableActions() {
        ResponseEntity<Map> response = client().post().uri("/cases")
                .header("Idempotency-Key", "key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("caseDefinitionKey", "complaint", "tenantId", "t1", "title", "T"))
                .retrieve().toEntity(Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getHeaders().getETag()).isNotBlank();
        assertThat((List<?>) response.getBody().get("availableActions")).isNotEmpty();
    }

    @Test
    void replaysTheSameIdempotencyKeyInsteadOfCreatingASecondCase() {
        var first = client().post().uri("/cases").header("Idempotency-Key", "key-2")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("caseDefinitionKey", "complaint", "tenantId", "t1", "title", "T"))
                .retrieve().body(Map.class);

        var second = client().post().uri("/cases").header("Idempotency-Key", "key-2")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("caseDefinitionKey", "complaint", "tenantId", "t1", "title", "T"))
                .retrieve().body(Map.class);

        assertThat(second.get("id")).isEqualTo(first.get("id"));
    }

    @Test
    void rejectsAMutationWithoutIfMatchWith428() {
        Map<String, Object> created = createCase();

        assertThatThrownBy(() -> client().patch().uri("/cases/{id}", created.get("id"))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("title", "New title"))
                .retrieve().body(Map.class))
                .hasMessageContaining("428");
    }

    @Test
    void rejectsAStaleIfMatchWith412() {
        Map<String, Object> created = createCase();
        String id = (String) created.get("id");

        client().patch().uri("/cases/{id}", id).header("If-Match", "\"0\"")
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("title", "One"))
                .retrieve().body(Map.class);

        assertThatThrownBy(() -> client().patch().uri("/cases/{id}", id).header("If-Match", "\"0\"")
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("title", "Two"))
                .retrieve().body(Map.class))
                .hasMessageContaining("412");
    }

    @Test
    void servesFormSchemasForTheDeployedDefinition() {
        Map<String, Object> schema = client().get()
                .uri("/case-definitions/{key}/forms/{formKey}", "complaint", "registerForm")
                .retrieve().body(Map.class);

        assertThat(schema).containsKey("properties");
    }

    @Test
    void exposesThePerCaseEventLogWithACursor() {
        Map<String, Object> created = createCase();

        List<Map<String, Object>> events = client().get()
                .uri("/cases/{id}/events?after=0&limit=50", created.get("id"))
                .retrieve().body(List.class);

        assertThat(events).isNotEmpty();
        assertThat(events.get(0)).containsKeys("specversion", "type", "subject");
    }
}
```

Also create the shared PoC test base (used by later tasks too):

`case-management-poc-app/src/test/java/org/casemgmt/poc/OracleBackedPocTest.java`:

```java
package org.casemgmt.poc;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.oracle.OracleContainer;

public abstract class OracleBackedPocTest {

    static final OracleContainer ORACLE =
            new OracleContainer("gvenzl/oracle-free:23-slim-faststart")
                    .withUsername("cm").withPassword("cm").withReuse(true);

    static {
        ORACLE.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", ORACLE::getJdbcUrl);
        registry.add("spring.datasource.username", ORACLE::getUsername);
        registry.add("spring.datasource.password", ORACLE::getPassword);
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./mvnw -q -pl case-management-poc-app test -Dtest=CaseApiIT`
Expected: FAIL — no application class yet (Task 26 creates it). That is the correct failure; this task builds the controllers it will need.

- [ ] **Step 3: Write the DTOs and caller resolution**

```java
package org.casemgmt.rest.dto;

import org.casemgmt.domain.*;
import org.casemgmt.rest.policy.AvailableAction;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public final class Dtos {

    public record CreateCaseRequest(String caseDefinitionKey, String tenantId, String businessKey,
                                    String title, String priority, Map<String, Object> variables) {}

    public record PatchCaseRequest(String title, Map<String, Object> variables) {}

    public record CloseRequest(String outcome) {}

    public record CancelRequest(String reason) {}

    public record TerminateRequest(String reason) {}

    public record CompleteTaskRequest(Map<String, Object> variables) {}

    public record CommentRequest(String text, String visibility) {}

    public record StartProcessRequest(String processDefinitionKey, Map<String, Object> variables) {}

    public record PauseSlaRequest(String reason) {}

    public record WebhookRequest(String url, List<String> eventTypes, String tenantId) {}

    public record CaseResponse(String id, String engineId, String tenantId, String caseDefinitionKey,
                               int caseDefinitionVersion, String businessKey, String title,
                               String state, String priority, String assignee, String slaStatus,
                               String outcome, Map<String, Object> variables,
                               OffsetDateTime createdAt, OffsetDateTime closedAt,
                               List<AvailableAction> availableActions) {

        public static CaseResponse of(CaseInstance c, List<AvailableAction> actions) {
            return new CaseResponse(c.id(), c.engineId(), c.tenantId(), c.caseDefKey(),
                    c.caseDefVersion(), c.businessKey(), c.title(), c.state().name(),
                    c.priority().name(), c.assignee(), c.slaStatus(), c.outcome(), c.variables(),
                    c.createdAt(), c.closedAt(), actions);
        }
    }

    public record PlanItemResponse(String id, String caseId, String type, String name, String state,
                                   String parentStageId, int repetitionNo, long version,
                                   List<AvailableAction> availableActions) {

        public static PlanItemResponse of(PlanItem i, List<AvailableAction> actions) {
            return new PlanItemResponse(i.id(), i.caseId(), i.type().name(), i.name(),
                    i.state().name(), i.parentStageId(), i.repetitionNo(), i.version(), actions);
        }
    }

    public record TaskResponse(String id, String caseId, String planItemId, String name, String state,
                               String assignee, List<String> candidateGroups, String formKey,
                               String engineSync, long version, List<AvailableAction> availableActions) {

        public static TaskResponse of(CaseTask t, List<AvailableAction> actions) {
            return new TaskResponse(t.id(), t.caseId(), t.planItemId(), t.name(), t.state().name(),
                    t.assignee(), t.candidateGroups(), t.formKey(), t.engineSync().name(),
                    t.version(), actions);
        }
    }

    private Dtos() {}
}
```

```java
package org.casemgmt.rest;

import org.casemgmt.repo.ParticipantRepository;
import org.casemgmt.service.Actor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

import java.util.List;
import java.util.Set;

/**
 * Maps the authenticated principal onto the Actor the services expect. Identity comes
 * from Operaton's own user/group tables via basic auth (spec §7); swapping in OAuth2
 * changes only this class and the security configuration.
 */
public class CallerResolver {

    private final ParticipantRepository participants;

    public CallerResolver(ParticipantRepository participants) {
        this.participants = participants;
    }

    public Actor actor(Authentication authentication) {
        List<String> groups = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .map(a -> a.startsWith("ROLE_") ? a.substring(5) : a)
                .toList();
        return new Actor(authentication.getName(), groups);
    }

    public Set<String> roles(String caseId, Actor actor) {
        return participants.rolesOf(caseId, actor.userId(), actor.groups());
    }
}
```

- [ ] **Step 4: Write the case controller**

```java
package org.casemgmt.rest.controller;

import org.casemgmt.domain.*;
import org.casemgmt.repo.IdempotencyRepository;
import org.casemgmt.repo.JsonCodec;
import org.casemgmt.rest.CallerResolver;
import org.casemgmt.rest.dto.Dtos.*;
import org.casemgmt.rest.filter.ETagSupport;
import org.casemgmt.rest.filter.IdempotencySupport;
import org.casemgmt.rest.policy.ActionPolicy;
import org.casemgmt.rest.policy.AvailableAction;
import org.casemgmt.repo.CaseQuery;
import org.casemgmt.repo.CaseRepository;
import org.casemgmt.service.Actor;
import org.casemgmt.service.CaseService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/case-api/v2/cases")
public class CaseController {

    private final CaseService cases;
    private final CaseRepository caseRepo;
    private final ActionPolicy policy;
    private final CallerResolver callers;
    private final IdempotencySupport idempotency;

    public CaseController(CaseService cases, CaseRepository caseRepo, ActionPolicy policy,
                          CallerResolver callers, IdempotencyRepository idempotencyRepo) {
        this.cases = cases;
        this.caseRepo = caseRepo;
        this.policy = policy;
        this.callers = callers;
        this.idempotency = new IdempotencySupport(idempotencyRepo);
    }

    @PostMapping
    public ResponseEntity<CaseResponse> create(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody CreateCaseRequest request,
            Authentication authentication) {

        Actor actor = callers.actor(authentication);
        var result = idempotency.execute(idempotencyKey, "POST /cases", JsonCodec.toJson(request),
                () -> cases.create(request.caseDefinitionKey(), request.tenantId(),
                        request.businessKey(), request.title(),
                        request.priority() == null ? CasePriority.MEDIUM
                                : CasePriority.valueOf(request.priority()),
                        request.variables(), actor),
                body -> caseRepo.require(JsonCodec.toMap(body).get("id").toString()),
                created -> JsonCodec.toJson(Map.of("id", created.id())),
                201);

        // A replay returns the ORIGINAL 201 (spec §6.4) — same status either way; the
        // Idempotency-Replayed header is what tells the client which it got.
        CaseInstance created = result.value();
        return ResponseEntity.status(HttpStatus.CREATED)
                .eTag(ETagSupport.format(created.version()))
                .header("Idempotency-Replayed", String.valueOf(result.replayed()))
                .body(response(created, actor));
    }

    @GetMapping
    public List<CaseResponse> query(@RequestParam(required = false) String tenantId,
                                    @RequestParam(required = false) CaseState state,
                                    @RequestParam(required = false) String assignee,
                                    @RequestParam(required = false) String caseDefinitionKey,
                                    @RequestParam(required = false) String businessKey,
                                    @RequestParam(defaultValue = "0") int offset,
                                    @RequestParam(defaultValue = "50") int limit,
                                    Authentication authentication) {
        Actor actor = callers.actor(authentication);
        return caseRepo.query(new CaseQuery(tenantId, state, assignee, caseDefinitionKey,
                        businessKey, offset, limit)).stream()
                .map(c -> response(c, actor))
                .toList();
    }

    @GetMapping("/{caseId}")
    public ResponseEntity<CaseResponse> get(@PathVariable String caseId, Authentication authentication) {
        Actor actor = callers.actor(authentication);
        CaseInstance c = cases.get(caseId);
        return ResponseEntity.ok().eTag(ETagSupport.format(c.version())).body(response(c, actor));
    }

    @PatchMapping("/{caseId}")
    public ResponseEntity<CaseResponse> patch(@PathVariable String caseId,
                                              @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                              @RequestBody PatchCaseRequest request,
                                              Authentication authentication) {
        Actor actor = callers.actor(authentication);
        long version = ETagSupport.parse(ifMatch);
        policy.assertAllowed(cases.snapshot(caseId), callers.roles(caseId, actor), "update");

        java.util.Map<String, Object> patch = new java.util.LinkedHashMap<>();
        if (request.title() != null) patch.put("title", request.title());
        if (request.variables() != null) patch.put("variables", request.variables());

        CaseInstance updated = cases.update(caseId, version, patch, actor);
        return ResponseEntity.ok().eTag(ETagSupport.format(updated.version()))
                .body(response(updated, actor));
    }

    @PostMapping("/{caseId}/close")
    public ResponseEntity<CaseResponse> close(@PathVariable String caseId,
                                              @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                              @RequestBody(required = false) CloseRequest request,
                                              Authentication authentication) {
        Actor actor = callers.actor(authentication);
        long version = ETagSupport.parse(ifMatch);
        policy.assertAllowed(cases.snapshot(caseId), callers.roles(caseId, actor), "close");

        CaseInstance closed = cases.close(caseId, version,
                request == null ? null : request.outcome(), actor);
        return ResponseEntity.ok().eTag(ETagSupport.format(closed.version()))
                .body(response(closed, actor));
    }

    @PostMapping("/{caseId}/cancel")
    public ResponseEntity<CaseResponse> cancel(@PathVariable String caseId,
                                               @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                               @RequestBody(required = false) CancelRequest request,
                                               Authentication authentication) {
        Actor actor = callers.actor(authentication);
        long version = ETagSupport.parse(ifMatch);
        policy.assertAllowed(cases.snapshot(caseId), callers.roles(caseId, actor), "cancel");

        CaseInstance cancelled = cases.cancel(caseId, version,
                request == null ? null : request.reason(), actor);
        return ResponseEntity.ok().eTag(ETagSupport.format(cancelled.version()))
                .body(response(cancelled, actor));
    }

    private CaseResponse response(CaseInstance c, Actor actor) {
        Set<String> roles = callers.roles(c.id(), actor);
        List<AvailableAction> actions = policy.listForCase(cases.snapshot(c.id()), roles);
        return CaseResponse.of(c, actions);
    }
}
```

- [ ] **Step 5: Write the remaining controllers**

```java
package org.casemgmt.rest.controller;

import org.casemgmt.domain.PlanItem;
import org.casemgmt.repo.PlanItemRepository;
import org.casemgmt.rest.CallerResolver;
import org.casemgmt.rest.dto.Dtos.*;
import org.casemgmt.rest.filter.ETagSupport;
import org.casemgmt.rest.policy.ActionPolicy;
import org.casemgmt.service.Actor;
import org.casemgmt.service.CaseService;
import org.casemgmt.service.PlanItemService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/case-api/v2/cases/{caseId}/plan-items")
public class PlanItemController {

    private final PlanItemService planItems;
    private final PlanItemRepository repo;
    private final CaseService cases;
    private final ActionPolicy policy;
    private final CallerResolver callers;

    public PlanItemController(PlanItemService planItems, PlanItemRepository repo, CaseService cases,
                              ActionPolicy policy, CallerResolver callers) {
        this.planItems = planItems;
        this.repo = repo;
        this.cases = cases;
        this.policy = policy;
        this.callers = callers;
    }

    @GetMapping
    public List<PlanItemResponse> list(@PathVariable String caseId, Authentication authentication) {
        Actor actor = callers.actor(authentication);
        var snapshot = cases.snapshot(caseId);
        var roles = callers.roles(caseId, actor);
        return repo.findByCase(caseId).stream()
                .map(i -> PlanItemResponse.of(i, policy.listForPlanItem(snapshot, i, roles)))
                .toList();
    }

    @PostMapping("/{itemId}/enable")
    public ResponseEntity<PlanItemResponse> enable(@PathVariable String caseId, @PathVariable String itemId,
                                                   @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                                   Authentication authentication) {
        return respond(caseId, planItems.enable(caseId, itemId, ETagSupport.parse(ifMatch),
                callers.actor(authentication)), authentication);
    }

    @PostMapping("/{itemId}/start")
    public ResponseEntity<PlanItemResponse> start(@PathVariable String caseId, @PathVariable String itemId,
                                                  @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                                  Authentication authentication) {
        return respond(caseId, planItems.start(caseId, itemId, ETagSupport.parse(ifMatch),
                callers.actor(authentication)), authentication);
    }

    @PostMapping("/{itemId}/complete")
    public ResponseEntity<PlanItemResponse> complete(@PathVariable String caseId, @PathVariable String itemId,
                                                     @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                                     Authentication authentication) {
        return respond(caseId, planItems.complete(caseId, itemId, ETagSupport.parse(ifMatch),
                callers.actor(authentication)), authentication);
    }

    @PostMapping("/{itemId}/terminate")
    public ResponseEntity<PlanItemResponse> terminate(@PathVariable String caseId, @PathVariable String itemId,
                                                      @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                                      @RequestBody(required = false) TerminateRequest request,
                                                      Authentication authentication) {
        return respond(caseId, planItems.terminate(caseId, itemId, ETagSupport.parse(ifMatch),
                request == null ? null : request.reason(), callers.actor(authentication)), authentication);
    }

    private ResponseEntity<PlanItemResponse> respond(String caseId, PlanItem item,
                                                     Authentication authentication) {
        Actor actor = callers.actor(authentication);
        var actions = policy.listForPlanItem(cases.snapshot(caseId), item,
                callers.roles(caseId, actor));
        return ResponseEntity.ok().eTag(ETagSupport.format(item.version()))
                .body(PlanItemResponse.of(item, actions));
    }
}
```

```java
package org.casemgmt.rest.controller;

import org.casemgmt.domain.CaseTask;
import org.casemgmt.rest.CallerResolver;
import org.casemgmt.rest.dto.Dtos.*;
import org.casemgmt.rest.filter.ETagSupport;
import org.casemgmt.rest.policy.ActionPolicy;
import org.casemgmt.service.Actor;
import org.casemgmt.service.CaseTaskService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/case-api/v2")
public class TaskController {

    private final CaseTaskService tasks;
    private final ActionPolicy policy;
    private final CallerResolver callers;

    public TaskController(CaseTaskService tasks, ActionPolicy policy, CallerResolver callers) {
        this.tasks = tasks;
        this.policy = policy;
        this.callers = callers;
    }

    @GetMapping("/tasks")
    public List<TaskResponse> worklist(@RequestParam(defaultValue = "50") int limit,
                                       Authentication authentication) {
        Actor actor = callers.actor(authentication);
        return tasks.worklist(actor, limit).stream().map(t -> respond(t, actor)).toList();
    }

    @GetMapping("/cases/{caseId}/tasks")
    public List<TaskResponse> forCase(@PathVariable String caseId, Authentication authentication) {
        Actor actor = callers.actor(authentication);
        return tasks.forCase(caseId).stream().map(t -> respond(t, actor)).toList();
    }

    @PostMapping("/tasks/{taskId}/claim")
    public ResponseEntity<TaskResponse> claim(@PathVariable String taskId,
                                              @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                              Authentication authentication) {
        Actor actor = callers.actor(authentication);
        CaseTask claimed = tasks.claim(taskId, ETagSupport.parse(ifMatch), actor);
        return ResponseEntity.ok().eTag(ETagSupport.format(claimed.version()))
                .body(respond(claimed, actor));
    }

    @PostMapping("/tasks/{taskId}/complete")
    public ResponseEntity<TaskResponse> complete(@PathVariable String taskId,
                                                 @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                                 @RequestBody(required = false) CompleteTaskRequest request,
                                                 Authentication authentication) {
        Actor actor = callers.actor(authentication);
        CaseTask completed = tasks.complete(taskId, ETagSupport.parse(ifMatch),
                request == null ? java.util.Map.of() : request.variables(), actor);
        return ResponseEntity.ok().eTag(ETagSupport.format(completed.version()))
                .body(respond(completed, actor));
    }

    private TaskResponse respond(CaseTask task, Actor actor) {
        return TaskResponse.of(task,
                policy.listForTask(task, actor.userId(), callers.roles(task.caseId(), actor)));
    }
}
```

```java
package org.casemgmt.rest.controller;

import org.casemgmt.repo.CaseDefinitionRepository;
import org.casemgmt.rest.CallerResolver;
import org.casemgmt.service.CaseDefinitionService;
import org.casemgmt.error.NotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/case-api/v2/case-definitions")
public class CaseDefinitionController {

    private final CaseDefinitionService service;
    private final CaseDefinitionRepository repo;
    private final CallerResolver callers;

    public CaseDefinitionController(CaseDefinitionService service, CaseDefinitionRepository repo,
                                    CallerResolver callers) {
        this.service = service;
        this.repo = repo;
        this.callers = callers;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> deploy(@RequestBody String definitionJson,
                                                      Authentication authentication) {
        var deployed = service.deploy(definitionJson, callers.actor(authentication).userId());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "id", deployed.id(), "key", deployed.key(), "version", deployed.versionNo(),
                "planItems", deployed.planItems().size()));
    }

    /**
     * The listing. A consumer with no prior knowledge starts here: it discovers which
     * case types exist rather than being told (spec §2.1, and what makes the
     * generic-consumer test in Task 27 possible without case-type constants).
     */
    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(required = false) String tenantId) {
        return repo.listLatest(tenantId).stream()
                .map(def -> Map.<String, Object>of(
                        "id", def.id(), "key", def.key(), "version", def.versionNo(),
                        "name", def.name(),
                        "tenantId", def.tenantId() == null ? "" : def.tenantId()))
                .toList();
    }

    @GetMapping("/{key}")
    public Map<String, Object> get(@PathVariable String key,
                                   @RequestParam(required = false) String tenantId) {
        var def = repo.findLatest(key, tenantId)
                .orElseThrow(() -> new NotFoundException("CaseDefinition", key));
        return Map.of("id", def.id(), "key", def.key(), "version", def.versionNo(),
                "name", def.name(), "roles", def.roles(),
                "formKeys", List.copyOf(def.forms().keySet()),
                "planItems", def.planItems().stream()
                        .map(p -> Map.of("defKey", p.defKey(), "type", p.type().name(),
                                "name", p.name(), "required", p.required(),
                                "manualActivation", p.manualActivation(),
                                "repetition", p.repetition()))
                        .toList());
    }

    @GetMapping("/{key}/forms/{formKey}")
    public Map<String, Object> form(@PathVariable String key, @PathVariable String formKey) {
        return repo.formSchema(key, formKey)
                .orElseThrow(() -> new NotFoundException("Form", key + "/" + formKey));
    }
}
```

```java
package org.casemgmt.rest.controller;

import org.casemgmt.rest.CallerResolver;
import org.casemgmt.rest.dto.Dtos.*;
import org.casemgmt.service.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/case-api/v2/cases/{caseId}")
public class CollaborationController {

    private final CommentService comments;
    private final MilestoneService milestones;
    private final LinkedProcessService processes;
    private final CallerResolver callers;

    public CollaborationController(CommentService comments, MilestoneService milestones,
                                   LinkedProcessService processes, CallerResolver callers) {
        this.comments = comments;
        this.milestones = milestones;
        this.processes = processes;
        this.callers = callers;
    }

    @GetMapping("/comments")
    public List<Map<String, Object>> listComments(@PathVariable String caseId,
                                                  @RequestParam(required = false) String visibility) {
        return comments.forCase(caseId, visibility).stream()
                .map(c -> Map.<String, Object>of("id", c.id(), "author", c.author(),
                        "text", c.text(), "visibility", c.visibility(), "createdAt", c.createdAt()))
                .toList();
    }

    @PostMapping("/comments")
    public ResponseEntity<Map<String, Object>> addComment(@PathVariable String caseId,
                                                          @RequestBody CommentRequest request,
                                                          Authentication authentication) {
        var row = comments.add(caseId, request.text(),
                request.visibility() == null ? "internal" : request.visibility(),
                callers.actor(authentication));
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "id", row.id(), "visibility", row.visibility(), "createdAt", row.createdAt()));
    }

    @GetMapping("/milestones")
    public List<Map<String, Object>> listMilestones(@PathVariable String caseId) {
        return milestones.forCase(caseId).stream()
                .map(m -> Map.<String, Object>of("id", m.id(), "name", m.name(),
                        "achieved", m.achieved(),
                        "achievedAt", String.valueOf(m.achievedAt())))
                .toList();
    }

    @PostMapping("/milestones/{milestoneId}/achieve")
    public Map<String, Object> achieve(@PathVariable String caseId, @PathVariable String milestoneId,
                                       Authentication authentication) {
        var row = milestones.achieve(caseId, milestoneId, callers.actor(authentication));
        return Map.of("id", row.id(), "name", row.name(), "achieved", row.achieved());
    }

    @GetMapping("/processes")
    public List<Map<String, Object>> listProcesses(@PathVariable String caseId) {
        return processes.forCase(caseId).stream()
                .map(p -> Map.<String, Object>of("id", p.id(),
                        "processInstanceId", p.processInstanceId(),
                        "processDefinitionKey", p.processDefinitionKey(), "state", p.state()))
                .toList();
    }

    @PostMapping("/processes")
    public ResponseEntity<Map<String, Object>> startProcess(@PathVariable String caseId,
                                                            @RequestBody StartProcessRequest request,
                                                            Authentication authentication) {
        var row = processes.start(caseId, null, request.processDefinitionKey(),
                request.variables(), callers.actor(authentication));
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "id", row.id(), "processInstanceId", row.processInstanceId(),
                "processDefinitionKey", row.processDefinitionKey()));
    }
}
```

```java
package org.casemgmt.rest.controller;

import org.casemgmt.repo.EventRepository;
import org.casemgmt.rest.CallerResolver;
import org.casemgmt.rest.dto.Dtos.WebhookRequest;
import org.casemgmt.service.WebhookService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/case-api/v2")
public class EventController {

    private final EventRepository events;
    private final WebhookService webhooks;
    private final CallerResolver callers;

    public EventController(EventRepository events, WebhookService webhooks, CallerResolver callers) {
        this.events = events;
        this.webhooks = webhooks;
        this.callers = callers;
    }

    /** Cursor pagination over SEQ_ — the recovery path for a consumer that missed webhooks. */
    @GetMapping("/events")
    public List<Map<String, Object>> events(@RequestParam(defaultValue = "0") long after,
                                            @RequestParam(defaultValue = "100") int limit) {
        return events.after(after, limit).stream()
                .map(e -> withCursor(e.event().toCloudEvent(), e.seq()))
                .toList();
    }

    @GetMapping("/cases/{caseId}/events")
    public List<Map<String, Object>> caseEvents(@PathVariable String caseId,
                                                @RequestParam(defaultValue = "0") long after,
                                                @RequestParam(defaultValue = "100") int limit) {
        return events.forCase(caseId, after, limit).stream()
                .map(e -> withCursor(e.event().toCloudEvent(), e.seq()))
                .toList();
    }

    @GetMapping("/webhooks")
    public List<Map<String, Object>> listWebhooks() {
        return webhooks.list().stream()
                .map(s -> Map.<String, Object>of("id", s.id(), "url", s.url(),
                        "eventTypes", s.eventTypes(), "active", s.active()))
                .toList();
    }

    @PostMapping("/webhooks")
    public ResponseEntity<Map<String, Object>> subscribe(@RequestBody WebhookRequest request,
                                                         Authentication authentication) {
        var created = webhooks.subscribe(request.tenantId(), request.url(), request.eventTypes(),
                callers.actor(authentication));
        // The plaintext secret is returned once and never again.
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "id", created.id(), "url", created.url(),
                "eventTypes", created.eventTypes(), "secret", created.secret()));
    }

    private Map<String, Object> withCursor(Map<String, Object> cloudEvent, long seq) {
        var copy = new java.util.LinkedHashMap<>(cloudEvent);
        copy.put("cursor", seq);
        return copy;
    }
}
```

```java
package org.casemgmt.rest.controller;

import org.casemgmt.rest.CallerResolver;
import org.casemgmt.rest.dto.Dtos.PauseSlaRequest;
import org.casemgmt.rest.filter.ETagSupport;
import org.casemgmt.sla.SlaRecord;
import org.casemgmt.sla.SlaService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/case-api/v2/cases/{caseId}/slas")
public class SlaController {

    private final SlaService sla;
    private final CallerResolver callers;

    public SlaController(SlaService sla, CallerResolver callers) {
        this.sla = sla;
        this.callers = callers;
    }

    @GetMapping
    public List<Map<String, Object>> list(@PathVariable String caseId) {
        return sla.forCase(caseId).stream().map(SlaController::body).toList();
    }

    @PostMapping("/{slaId}/pause")
    public ResponseEntity<Map<String, Object>> pause(@PathVariable String caseId, @PathVariable String slaId,
                                                     @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                                     @RequestBody(required = false) PauseSlaRequest request,
                                                     Authentication authentication) {
        SlaRecord paused = sla.pause(caseId, slaId, ETagSupport.parse(ifMatch),
                request == null ? null : request.reason(), callers.actor(authentication));
        return ResponseEntity.ok().eTag(ETagSupport.format(paused.version())).body(body(paused));
    }

    @PostMapping("/{slaId}/resume")
    public ResponseEntity<Map<String, Object>> resume(@PathVariable String caseId, @PathVariable String slaId,
                                                      @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                                      Authentication authentication) {
        SlaRecord resumed = sla.resume(caseId, slaId, ETagSupport.parse(ifMatch),
                callers.actor(authentication));
        return ResponseEntity.ok().eTag(ETagSupport.format(resumed.version())).body(body(resumed));
    }

    private static Map<String, Object> body(SlaRecord r) {
        return Map.of("id", r.id(), "targetId", r.targetId(), "status", r.status(),
                "dueAt", String.valueOf(r.dueAt()), "warnAt", String.valueOf(r.warnAt()),
                "pausedTotalSeconds", r.pausedTotalSeconds(), "version", r.version());
    }
}
```

- [ ] **Step 6: Verify compilation**

Run: `./mvnw -q -pl case-management-rest install -DskipTests`
Expected: BUILD SUCCESS. The `CaseApiIT` still fails until Task 26 supplies the application; that is expected and it is re-run there.

- [ ] **Step 7: Commit**

```bash
git add case-management-rest/src case-management-poc-app/src
git commit -m "feat(rest): case, plan item, task, collaboration, event and SLA controllers"
```

---

## Phase 7 — Packaging and proof

### Task 25: Spring Boot starter

**Files:**
- Create: `case-management-spring-boot-starter/src/main/java/org/casemgmt/starter/CaseManagementProperties.java`
- Create: `case-management-spring-boot-starter/src/main/java/org/casemgmt/starter/CaseManagementAutoConfiguration.java`
- Create: `case-management-spring-boot-starter/src/main/java/org/casemgmt/starter/EmbeddedEngineAutoConfiguration.java`
- Create: `case-management-spring-boot-starter/src/main/java/org/casemgmt/starter/RemoteEngineAutoConfiguration.java`
- Create: `case-management-spring-boot-starter/src/main/java/org/casemgmt/starter/CaseManagementSchedulers.java`
- Create: `case-management-spring-boot-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Create: `case-management-spring-boot-starter/src/test/java/org/casemgmt/starter/AutoConfigurationTest.java`
- Create: `case-management-core/src/test/java/org/casemgmt/ArchitectureTest.java`

**Interfaces:**
- Consumes: every service and repository built so far
- Produces: the import point — one dependency plus properties gives a working case service

- [ ] **Step 1: Write the failing tests**

```java
package org.casemgmt.starter;

import org.casemgmt.engine.EngineGateway;
import org.casemgmt.engine.OutboxEngineGateway;
import org.casemgmt.service.CaseService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class AutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class,
                    CaseManagementAutoConfiguration.class, RemoteEngineAutoConfiguration.class))
            .withPropertyValues(
                    "spring.datasource.url=jdbc:h2:mem:autoconfig;DB_CLOSE_DELAY=-1",
                    "spring.datasource.driver-class-name=org.h2.Driver");

    @Test
    void disabledByDefaultPropertyLeavesTheContextClean() {
        runner.withPropertyValues("casemgmt.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(CaseService.class));
    }

    @Test
    void remoteModeRegistersTheOutboxGateway() {
        runner.withPropertyValues("casemgmt.enabled=true", "casemgmt.engine-id=eng-a",
                        "casemgmt.engine.mode=remote",
                        "casemgmt.engine.remote.base-url=http://localhost:9999/engine-rest",
                        "casemgmt.events.type-prefix=org.example.cm")
                .run(context -> {
                    assertThat(context).hasSingleBean(CaseService.class);
                    assertThat(context.getBean(EngineGateway.class))
                            .isInstanceOf(OutboxEngineGateway.class);
                });
    }

    @Test
    void embeddedModeWithoutAnEngineOnTheClasspathFailsWithAClearMessage() {
        runner.withPropertyValues("casemgmt.enabled=true", "casemgmt.engine-id=eng-a",
                        "casemgmt.engine.mode=embedded",
                        "casemgmt.events.type-prefix=org.example.cm")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().hasMessageContaining("operaton-bpm-spring-boot-starter"));
    }

    @Test
    void missingEventTypePrefixFailsStartup() {
        runner.withPropertyValues("casemgmt.enabled=true", "casemgmt.engine-id=eng-a",
                        "casemgmt.engine.mode=remote",
                        "casemgmt.engine.remote.base-url=http://localhost:9999/engine-rest")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().hasMessageContaining("type-prefix"));
    }
}
```

```java
package org.casemgmt;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(packages = "org.casemgmt",
        importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    /**
     * Core must stay engine-free: that is what makes both deployment modes possible
     * and keeps the state-machine tests free of engine setup (spec §3.2).
     * operaton-juel is an expression library, not the engine, so it is exempt.
     */
    @ArchTest
    static final ArchRule coreDoesNotDependOnOperaton = noClasses()
            .that().resideInAPackage("org.casemgmt..")
            .and().resideOutsideOfPackages("org.casemgmt.engine.embedded..",
                    "org.casemgmt.engine.remote..", "org.casemgmt.starter..")
            .should().dependOnClassesThat()
            .resideInAPackage("org.operaton.bpm.engine..");

    /** No case-type knowledge leaks out of the PoC app (spec Global Constraints). */
    @ArchTest
    static final ArchRule noDomainVocabularyInTheService = noClasses()
            .that().resideInAPackage("org.casemgmt..")
            .and().resideOutsideOfPackage("org.casemgmt.poc..")
            .should().haveSimpleNameContaining("Complaint");
}
```

- [ ] **Step 2: Run them and watch them fail**

Run: `./mvnw -q -pl case-management-spring-boot-starter test -Dtest=AutoConfigurationTest`
Expected: FAIL — `cannot find symbol: class CaseManagementAutoConfiguration`.

- [ ] **Step 3: Write the properties**

```java
package org.casemgmt.starter;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "casemgmt")
public class CaseManagementProperties {

    public enum EngineMode { embedded, remote }

    /** Master switch: false leaves a plain Operaton app completely untouched. */
    private boolean enabled = true;

    /** Prefix of every globally unique case id, and the CloudEvents `source`. */
    private String engineId;

    private final Engine engine = new Engine();
    private final Events events = new Events();
    private final Schedulers schedulers = new Schedulers();

    public static class Engine {
        private EngineMode mode = EngineMode.embedded;
        private final Remote remote = new Remote();

        public EngineMode getMode() { return mode; }
        public void setMode(EngineMode mode) { this.mode = mode; }
        public Remote getRemote() { return remote; }

        public static class Remote {
            private String baseUrl;
            private String username;
            private String password;

            public String getBaseUrl() { return baseUrl; }
            public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
            public String getUsername() { return username; }
            public void setUsername(String username) { this.username = username; }
            public String getPassword() { return password; }
            public void setPassword(String password) { this.password = password; }
        }
    }

    public static class Events {
        /** No default on purpose: shipping a placeholder namespace into a broker is unfixable later. */
        private String typePrefix;

        public String getTypePrefix() { return typePrefix; }
        public void setTypePrefix(String typePrefix) { this.typePrefix = typePrefix; }
    }

    public static class Schedulers {
        private boolean enabled = true;
        private long webhookIntervalMs = 5_000;
        private long engineCommandIntervalMs = 5_000;
        private long slaSweepIntervalMs = 60_000;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public long getWebhookIntervalMs() { return webhookIntervalMs; }
        public void setWebhookIntervalMs(long v) { this.webhookIntervalMs = v; }
        public long getEngineCommandIntervalMs() { return engineCommandIntervalMs; }
        public void setEngineCommandIntervalMs(long v) { this.engineCommandIntervalMs = v; }
        public long getSlaSweepIntervalMs() { return slaSweepIntervalMs; }
        public void setSlaSweepIntervalMs(long v) { this.slaSweepIntervalMs = v; }
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getEngineId() { return engineId; }
    public void setEngineId(String engineId) { this.engineId = engineId; }
    public Engine getEngine() { return engine; }
    public Events getEvents() { return events; }
    public Schedulers getSchedulers() { return schedulers; }
}
```

- [ ] **Step 4: Write the auto-configurations**

```java
package org.casemgmt.starter;

import org.casemgmt.engine.EngineGateway;
import org.casemgmt.event.EventPublisher;
import org.casemgmt.event.WebhookDispatcher;
import org.casemgmt.repo.*;
import org.casemgmt.rest.CallerResolver;
import org.casemgmt.rest.controller.*;
import org.casemgmt.rest.error.ProblemDetailHandler;
import org.casemgmt.rest.policy.ActionPolicy;
import org.casemgmt.rules.*;
import org.casemgmt.service.*;
import org.casemgmt.sla.SlaService;
import org.casemgmt.sla.SlaSweeper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import javax.sql.DataSource;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@AutoConfiguration
@ConditionalOnProperty(prefix = "casemgmt", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(CaseManagementProperties.class)
@Import({CaseController.class, PlanItemController.class, TaskController.class,
        CaseDefinitionController.class, CollaborationController.class, EventController.class,
        SlaController.class, ProblemDetailHandler.class})
public class CaseManagementAutoConfiguration {

    /**
     * PoC-only: webhook secrets are hashed in the database, but the dispatcher needs the
     * plaintext to sign. This in-memory map holds them for the process lifetime. A
     * production build needs a secret store or reversible encryption instead — see FINDINGS.md.
     */
    private final Map<String, String> webhookSecrets = new ConcurrentHashMap<>();

    @Bean
    public JdbcClient caseJdbcClient(DataSource dataSource) {
        return JdbcClient.create(dataSource);
    }

    @Bean public CaseRepository caseRepository(JdbcClient c) { return new CaseRepository(c); }
    @Bean public CaseDefinitionRepository caseDefinitionRepository(JdbcClient c) { return new CaseDefinitionRepository(c); }
    @Bean public PlanItemRepository planItemRepository(JdbcClient c) { return new PlanItemRepository(c); }
    @Bean public CaseTaskRepository caseTaskRepository(JdbcClient c) { return new CaseTaskRepository(c); }
    @Bean public MilestoneRepository milestoneRepository(JdbcClient c) { return new MilestoneRepository(c); }
    @Bean public CommentRepository commentRepository(JdbcClient c) { return new CommentRepository(c); }
    @Bean public ParticipantRepository participantRepository(JdbcClient c) { return new ParticipantRepository(c); }
    @Bean public LinkedProcessRepository linkedProcessRepository(JdbcClient c) { return new LinkedProcessRepository(c); }
    @Bean public EventRepository eventRepository(JdbcClient c) { return new EventRepository(c); }
    @Bean public AuditRepository auditRepository(JdbcClient c) { return new AuditRepository(c); }
    @Bean public WebhookRepository webhookRepository(JdbcClient c) { return new WebhookRepository(c); }
    @Bean public IdempotencyRepository idempotencyRepository(JdbcClient c) { return new IdempotencyRepository(c); }
    @Bean public EngineCommandRepository engineCommandRepository(JdbcClient c) { return new EngineCommandRepository(c); }
    @Bean public SlaRepository slaRepository(JdbcClient c) { return new SlaRepository(c); }

    @Bean
    public EventPublisher eventPublisher(EventRepository events, AuditRepository audit,
                                         WebhookRepository webhooks, CaseManagementProperties props) {
        String prefix = props.getEvents().getTypePrefix();
        if (prefix == null || prefix.isBlank()) {
            throw new IllegalStateException(
                    "casemgmt.events.type-prefix must be set — it becomes the CloudEvents type "
                            + "namespace and there is no safe default");
        }
        return new EventPublisher(events, audit, webhooks, prefix, props.getEngineId());
    }

    @Bean public CriterionEvaluator criterionEvaluator() { return new JuelCriterionEvaluator(); }
    @Bean public PlanModelEvaluator planModelEvaluator(CriterionEvaluator c) { return new PlanModelEvaluator(c); }
    @Bean public PlanModelInstantiator planModelInstantiator() { return new PlanModelInstantiator(); }
    @Bean public StageCompletion stageCompletion() { return new StageCompletion(); }
    @Bean public FormValidator formValidator() { return new FormValidator(); }
    @Bean public ActionPolicy actionPolicy() { return new ActionPolicy(); }

    @Bean
    public CallerResolver callerResolver(ParticipantRepository participants) {
        return new CallerResolver(participants);
    }

    @Bean
    public TransitionApplier transitionApplier(PlanItemRepository planItems, CaseTaskRepository tasks,
                                               MilestoneRepository milestones, EngineGateway engine,
                                               EventPublisher publisher) {
        return new TransitionApplier(planItems, tasks, milestones, engine, publisher);
    }

    @Bean
    public CaseService caseService(CaseRepository cases, CaseDefinitionRepository definitions,
                                   PlanItemRepository planItems, MilestoneRepository milestones,
                                   ParticipantRepository participants, PlanModelEvaluator evaluator,
                                   PlanModelInstantiator instantiator, StageCompletion completion,
                                   TransitionApplier applier, EventPublisher publisher,
                                   CaseManagementProperties props) {
        return new CaseService(cases, definitions, planItems, milestones, participants, evaluator,
                instantiator, completion, applier, publisher, props.getEngineId());
    }

    @Bean
    public CaseDefinitionService caseDefinitionService(CaseDefinitionRepository repo) {
        return new CaseDefinitionService(repo);
    }

    @Bean
    public PlanItemService planItemService(PlanItemRepository planItems, CaseService cases,
                                           TransitionApplier applier, EventPublisher publisher) {
        return new PlanItemService(planItems, cases, applier, publisher);
    }

    @Bean
    public CaseTaskService caseTaskService(CaseTaskRepository tasks, CaseRepository cases,
                                           CaseDefinitionRepository definitions, EngineGateway engine,
                                           FormValidator validator, PlanItemService planItems,
                                           PlanItemRepository planItemRepo, EventPublisher publisher) {
        return new CaseTaskService(tasks, cases, definitions, engine, validator, planItems,
                planItemRepo, publisher);
    }

    @Bean
    public CommentService commentService(CommentRepository comments, CaseRepository cases,
                                         EventPublisher publisher) {
        return new CommentService(comments, cases, publisher);
    }

    @Bean
    public MilestoneService milestoneService(MilestoneRepository milestones, CaseRepository cases,
                                             EventPublisher publisher) {
        return new MilestoneService(milestones, cases, publisher);
    }

    @Bean
    public LinkedProcessService linkedProcessService(LinkedProcessRepository processes,
                                                     CaseRepository cases, EngineGateway engine,
                                                     EventPublisher publisher) {
        return new LinkedProcessService(processes, cases, engine, publisher);
    }

    @Bean
    public WebhookService webhookService(WebhookRepository webhooks) {
        return new WebhookService(webhooks) {
            @Override
            public CreatedSubscription subscribe(String tenantId, String url,
                                                 java.util.List<String> eventTypes, Actor actor) {
                CreatedSubscription created = super.subscribe(tenantId, url, eventTypes, actor);
                webhookSecrets.put(created.id(), created.secret());
                return created;
            }
        };
    }

    @Bean
    public WebhookDispatcher webhookDispatcher(WebhookRepository webhooks, EventRepository events) {
        return new WebhookDispatcher(webhooks, events, webhookSecrets::get);
    }

    @Bean
    public SlaService slaService(SlaRepository sla, CaseRepository cases) {
        return new SlaService(sla, cases);
    }

    @Bean
    public SlaSweeper slaSweeper(SlaRepository sla, CaseRepository cases, EventPublisher publisher) {
        return new SlaSweeper(sla, cases, publisher);
    }
}
```

```java
package org.casemgmt.starter;

import org.casemgmt.engine.EngineGateway;
import org.casemgmt.engine.embedded.EmbeddedEngineGateway;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.TaskService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(before = CaseManagementAutoConfiguration.class)
@ConditionalOnProperty(prefix = "casemgmt", name = "enabled", havingValue = "true", matchIfMissing = true)
public class EmbeddedEngineAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(EngineGateway.class)
    @ConditionalOnProperty(prefix = "casemgmt.engine", name = "mode", havingValue = "embedded",
            matchIfMissing = true)
    @ConditionalOnClass(ProcessEngine.class)
    public EngineGateway embeddedEngineGateway(TaskService taskService, RuntimeService runtimeService) {
        return new EmbeddedEngineGateway(taskService, runtimeService);
    }

    /**
     * Fails fast and says what is missing, instead of leaving the user with a
     * NoSuchBeanDefinitionException for EngineGateway several frames deeper.
     */
    @Bean
    @ConditionalOnProperty(prefix = "casemgmt.engine", name = "mode", havingValue = "embedded",
            matchIfMissing = true)
    public Object embeddedEngineRequirementCheck(org.springframework.beans.factory.BeanFactory beans) {
        boolean engineOnClasspath;
        try {
            Class.forName("org.operaton.bpm.engine.ProcessEngine");
            engineOnClasspath = true;
        } catch (ClassNotFoundException e) {
            engineOnClasspath = false;
        }
        if (!engineOnClasspath) {
            throw new IllegalStateException(
                    "casemgmt.engine.mode=embedded requires the Operaton engine on the classpath. "
                            + "Add org.operaton.bpm.springboot:operaton-bpm-spring-boot-starter, "
                            + "or set casemgmt.engine.mode=remote.");
        }
        return new Object();
    }
}
```

```java
package org.casemgmt.starter;

import org.casemgmt.domain.CaseTask;
import org.casemgmt.engine.EngineCommandDispatcher;
import org.casemgmt.engine.EngineGateway;
import org.casemgmt.engine.OutboxEngineGateway;
import org.casemgmt.engine.remote.RemoteEngineGateway;
import org.casemgmt.repo.CaseTaskRepository;
import org.casemgmt.repo.EngineCommandRepository;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestClient;

@AutoConfiguration(before = CaseManagementAutoConfiguration.class)
@ConditionalOnProperty(prefix = "casemgmt.engine", name = "mode", havingValue = "remote")
public class RemoteEngineAutoConfiguration {

    @Bean
    public RestClient engineRestClient(CaseManagementProperties props) {
        String baseUrl = props.getEngine().getRemote().getBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException(
                    "casemgmt.engine.mode=remote requires casemgmt.engine.remote.base-url");
        }
        // Timeouts are not optional here. Catching RestClientException handles an engine that
        // REFUSES connections, but an engine that is up and hung answers nothing at all: without
        // a read timeout the calling thread blocks forever, no exception is thrown, and the
        // command outbox never gets to make its retry-versus-dead-letter decision.
        var requestFactory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) java.time.Duration.ofSeconds(5).toMillis());
        requestFactory.setReadTimeout((int) java.time.Duration.ofSeconds(30).toMillis());

        RestClient.Builder builder = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory);
        if (props.getEngine().getRemote().getUsername() != null) {
            builder = builder.defaultHeaders(h -> h.setBasicAuth(
                    props.getEngine().getRemote().getUsername(),
                    props.getEngine().getRemote().getPassword()));
        }
        return builder.build();
    }

    /** The real gateway, used only by the dispatcher — never by a request thread. */
    @Bean
    public RemoteEngineGateway remoteEngineGateway(RestClient engineRestClient) {
        return new RemoteEngineGateway(engineRestClient);
    }

    /** What the services get: writes commands in the local transaction (spec §3.5). */
    @Bean
    public EngineGateway outboxEngineGateway(EngineCommandRepository commands) {
        return new OutboxEngineGateway(commands, id -> { });
    }

    @Bean
    public EngineCommandDispatcher engineCommandDispatcher(EngineCommandRepository commands,
                                                            RemoteEngineGateway delegate,
                                                            CaseTaskRepository tasks) {
        return new EngineCommandDispatcher(commands, delegate,
                (planItemId, sync, engineId) -> tasks.findByCase(planItemId).stream()
                        .filter(t -> t.planItemId().equals(planItemId))
                        .findFirst()
                        .ifPresent(t -> tasks.markSync(t.id(), sync, engineId)));
    }
}
```

The `SyncReporter` lambda above looks up by the wrong key — `findByCase` takes a case id, not a plan item id. Add `CaseTaskRepository.findByPlanItemId(String planItemId) : Optional<CaseTask>` (same shape as `findByEngineTaskId`) and use it here. Fix it while writing this file rather than leaving it for the integration test to find.

```java
package org.casemgmt.starter;

import org.casemgmt.engine.EngineCommandDispatcher;
import org.casemgmt.event.WebhookDispatcher;
import org.casemgmt.sla.SlaSweeper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@AutoConfiguration(after = CaseManagementAutoConfiguration.class)
@ConditionalOnProperty(prefix = "casemgmt.schedulers", name = "enabled", havingValue = "true",
        matchIfMissing = true)
@EnableScheduling
public class CaseManagementSchedulers {

    private final WebhookDispatcher webhooks;
    private final ObjectProvider<EngineCommandDispatcher> engineCommands;
    private final SlaSweeper sla;

    public CaseManagementSchedulers(WebhookDispatcher webhooks,
                                    ObjectProvider<EngineCommandDispatcher> engineCommands,
                                    SlaSweeper sla) {
        this.webhooks = webhooks;
        this.engineCommands = engineCommands;
        this.sla = sla;
    }

    @Scheduled(fixedDelayString = "${casemgmt.schedulers.webhook-interval-ms:5000}")
    public void dispatchWebhooks() {
        webhooks.drainOnce();
    }

    /** Only present in remote mode; ObjectProvider keeps this a no-op in embedded mode. */
    @Scheduled(fixedDelayString = "${casemgmt.schedulers.engine-command-interval-ms:5000}")
    public void dispatchEngineCommands() {
        engineCommands.ifAvailable(EngineCommandDispatcher::drainOnce);
    }

    @Scheduled(fixedDelayString = "${casemgmt.schedulers.sla-sweep-interval-ms:60000}")
    public void sweepSlas() {
        sla.sweep();
    }
}
```

`case-management-spring-boot-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`:

```
org.casemgmt.starter.EmbeddedEngineAutoConfiguration
org.casemgmt.starter.RemoteEngineAutoConfiguration
org.casemgmt.starter.CaseManagementAutoConfiguration
org.casemgmt.starter.CaseManagementSchedulers
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./mvnw -q -pl case-management-core,case-management-spring-boot-starter test -Dtest='AutoConfigurationTest,ArchitectureTest'`
Expected: PASS — six tests.

- [ ] **Step 6: Commit**

```bash
git add case-management-spring-boot-starter/ case-management-core/src/test
git commit -m "feat(starter): auto-configuration, properties, schedulers and ArchUnit rules"
```

---

### Task 26: The PoC application and the complaint case type

**Files:**
- Create: `case-management-poc-app/src/main/java/org/casemgmt/poc/PocApplication.java`
- Create: `case-management-poc-app/src/main/java/org/casemgmt/poc/PocSecurityConfig.java`
- Create: `case-management-poc-app/src/main/java/org/casemgmt/poc/PocBootstrap.java`
- Create: `case-management-poc-app/src/main/resources/application.yaml`
- Create: `case-management-poc-app/src/main/resources/application-remote.yaml`
- Create: `case-management-poc-app/src/main/resources/definitions/complaint-v1.json`
- Create: `case-management-poc-app/src/main/resources/processes/decision-letter.bpmn`

**Interfaces:**
- Consumes: the starter (Task 25)
- Produces: a runnable application; `PocBootstrap` seeds Operaton users/groups, deploys the complaint definition, the BPMN process, the SLA policy and the Dutch business calendar on startup

- [ ] **Step 1: Write the complaint definition**

`case-management-poc-app/src/main/resources/definitions/complaint-v1.json` — the model from spec §4.2:

```json
{
  "key": "complaint",
  "name": "Complaint Handling",
  "tenantId": "t1",
  "slaPolicyId": "sla-complaint",
  "roles": ["owner", "handler", "reviewer", "watcher"],
  "attachmentCategories": ["correspondence", "evidence", "decision"],
  "forms": {
    "registerForm": {
      "$schema": "https://json-schema.org/draft/2020-12/schema",
      "type": "object",
      "required": ["channel", "summary"],
      "properties": {
        "channel": { "type": "string", "enum": ["web", "phone", "letter", "email"], "title": "Channel" },
        "summary": { "type": "string", "title": "What happened", "ui:widget": "textarea" },
        "amount": { "type": "integer", "minimum": 0, "title": "Amount in dispute (EUR)" }
      }
    },
    "assessForm": {
      "$schema": "https://json-schema.org/draft/2020-12/schema",
      "type": "object",
      "required": ["outcome"],
      "properties": {
        "outcome": { "type": "string", "enum": ["upheld", "rejected", "needs-investigation"], "title": "Assessment" },
        "rationale": { "type": "string", "title": "Rationale", "ui:widget": "textarea" }
      }
    },
    "investigateForm": {
      "$schema": "https://json-schema.org/draft/2020-12/schema",
      "type": "object",
      "required": ["finding"],
      "properties": {
        "aspect": { "type": "string", "title": "Aspect investigated" },
        "finding": { "type": "string", "title": "Finding", "ui:widget": "textarea" }
      }
    },
    "closeForm": {
      "$schema": "https://json-schema.org/draft/2020-12/schema",
      "type": "object",
      "required": ["outcome"],
      "properties": {
        "outcome": { "type": "string", "enum": ["resolved", "withdrawn", "rejected"], "title": "Final outcome" }
      }
    }
  },
  "planItems": [
    { "defKey": "intake", "type": "STAGE", "name": "Intake", "sortOrder": 10 },
    { "defKey": "registerComplaint", "type": "HUMAN_TASK", "name": "Register complaint",
      "parentStageKey": "intake", "required": true, "formKey": "registerForm",
      "candidateGroups": ["intake"], "sortOrder": 20 },

    { "defKey": "acknowledged", "type": "MILESTONE", "name": "Acknowledged",
      "entryCriteria": ["${items.registerComplaint.state == 'COMPLETED'}"], "sortOrder": 30 },

    { "defKey": "assessment", "type": "STAGE", "name": "Assessment",
      "entryCriteria": ["${items.acknowledged.state == 'COMPLETED'}"], "sortOrder": 40 },
    { "defKey": "assessComplaint", "type": "HUMAN_TASK", "name": "Assess complaint",
      "parentStageKey": "assessment", "required": true, "formKey": "assessForm",
      "entryCriteria": ["${items.acknowledged.state == 'COMPLETED'}"],
      "candidateGroups": ["handlers"], "sortOrder": 50 },

    { "defKey": "investigation", "type": "STAGE", "name": "Investigation",
      "manualActivation": true, "sortOrder": 60 },
    { "defKey": "investigateAspect", "type": "HUMAN_TASK", "name": "Investigate aspect",
      "parentStageKey": "investigation", "manualActivation": true, "repetition": true,
      "formKey": "investigateForm", "candidateGroups": ["handlers"], "sortOrder": 70 },

    { "defKey": "decision", "type": "STAGE", "name": "Decision",
      "entryCriteria": ["${items.assessComplaint.state == 'COMPLETED'}"], "sortOrder": 80 },
    { "defKey": "sendDecisionLetter", "type": "PROCESS_TASK", "name": "Send decision letter",
      "parentStageKey": "decision", "processDefinitionKey": "decision-letter",
      "entryCriteria": ["${items.assessComplaint.state == 'COMPLETED'}"], "sortOrder": 90 },

    { "defKey": "decided", "type": "MILESTONE", "name": "Decided",
      "entryCriteria": ["${items.sendDecisionLetter.state == 'COMPLETED'}"], "sortOrder": 100 },

    { "defKey": "closure", "type": "STAGE", "name": "Closure",
      "entryCriteria": ["${items.decided.state == 'COMPLETED'}"], "sortOrder": 110 },
    { "defKey": "closeComplaint", "type": "HUMAN_TASK", "name": "Close complaint",
      "parentStageKey": "closure", "required": true, "formKey": "closeForm",
      "entryCriteria": ["${items.decided.state == 'COMPLETED'}"],
      "candidateGroups": ["handlers"], "sortOrder": 120 }
  ]
}
```

- [ ] **Step 2: Write the BPMN fragment**

`case-management-poc-app/src/main/resources/processes/decision-letter.bpmn`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                  xmlns:operaton="http://operaton.org/schema/1.0/bpmn"
                  id="defs-decision-letter" targetNamespace="http://casemgmt.org/poc">
  <bpmn:process id="decision-letter" name="Decision letter" isExecutable="true">
    <bpmn:startEvent id="start"/>
    <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="draft"/>
    <bpmn:userTask id="draft" name="Draft decision letter" operaton:candidateGroups="handlers"/>
    <bpmn:sequenceFlow id="f2" sourceRef="draft" targetRef="send"/>
    <bpmn:userTask id="send" name="Send letter" operaton:candidateGroups="handlers"/>
    <bpmn:sequenceFlow id="f3" sourceRef="send" targetRef="end"/>
    <bpmn:endEvent id="end"/>
  </bpmn:process>
</bpmn:definitions>
```

- [ ] **Step 3: Write the application, security and bootstrap**

```java
package org.casemgmt.poc;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class PocApplication {
    public static void main(String[] args) {
        SpringApplication.run(PocApplication.class, args);
    }
}
```

```java
package org.casemgmt.poc;

import org.operaton.bpm.engine.IdentityService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

import java.util.List;

/**
 * Identity comes from Operaton's own user/group tables over HTTP basic auth (spec §7),
 * so participant roles and candidate groups behave exactly as the engine sees them.
 * Swapping in OAuth2 replaces this class and nothing else.
 */
@Configuration
public class PocSecurityConfig {

    @Bean
    public AuthenticationProvider operatonAuthenticationProvider(IdentityService identityService) {
        return new AuthenticationProvider() {
            @Override
            public Authentication authenticate(Authentication authentication) throws AuthenticationException {
                String username = authentication.getName();
                String password = String.valueOf(authentication.getCredentials());

                if (!identityService.checkPassword(username, password)) {
                    throw new BadCredentialsException("Unknown user or bad password: " + username);
                }
                List<SimpleGrantedAuthority> groups = identityService.createGroupQuery()
                        .groupMember(username).list().stream()
                        .map(g -> new SimpleGrantedAuthority(g.getId()))
                        .toList();
                return new UsernamePasswordAuthenticationToken(username, password, groups);
            }

            @Override
            public boolean supports(Class<?> authentication) {
                return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
            }
        };
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())          // no browser sessions: this is an API
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/case-api/v2/**").authenticated()
                        .anyRequest().permitAll())
                .httpBasic(basic -> { })
                .build();
    }
}
```

```java
package org.casemgmt.poc;

import org.casemgmt.repo.CaseDefinitionRepository;
import org.casemgmt.repo.SlaRepository;
import org.casemgmt.service.CaseDefinitionService;
import org.operaton.bpm.engine.IdentityService;
import org.operaton.bpm.engine.RepositoryService;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** Seeds everything the PoC needs to be demonstrable from a cold database. */
@Configuration
public class PocBootstrap {

    @Bean
    public ApplicationRunner seed(IdentityService identity, RepositoryService repository,
                                  CaseDefinitionService definitions, CaseDefinitionRepository defRepo,
                                  SlaRepository sla) {
        return args -> {
            seedUsers(identity);
            seedProcesses(repository);
            seedSla(sla);
            seedDefinition(definitions, defRepo);
        };
    }

    private void seedUsers(IdentityService identity) {
        createGroup(identity, "intake");
        createGroup(identity, "handlers");
        createGroup(identity, "reviewers");
        createUser(identity, "alice", "alice", List.of("intake", "handlers"));
        createUser(identity, "bob", "bob", List.of("handlers", "reviewers"));
        createUser(identity, "carol", "carol", List.of("reviewers"));
    }

    private void createGroup(IdentityService identity, String id) {
        if (identity.createGroupQuery().groupId(id).count() == 0) {
            var group = identity.newGroup(id);
            group.setName(id);
            identity.saveGroup(group);
        }
    }

    private void createUser(IdentityService identity, String id, String password, List<String> groups) {
        if (identity.createUserQuery().userId(id).count() == 0) {
            var user = identity.newUser(id);
            user.setPassword(password);
            user.setFirstName(id);
            identity.saveUser(user);
            groups.forEach(g -> identity.createMembership(id, g));
        }
    }

    private void seedProcesses(RepositoryService repository) {
        repository.createDeployment()
                .addClasspathResource("processes/decision-letter.bpmn")
                .enableDuplicateFiltering(true)
                .name("poc-processes")
                .deploy();
    }

    private void seedSla(SlaRepository sla) {
        if (sla.calendarIdOf("sla-complaint") != null) {
            return;
        }
        Map<String, Object> workday = Map.of("from", "09:00", "to", "17:00");
        sla.insertCalendar("cal-nl", Map.of(
                "timezone", "Europe/Amsterdam",
                "workingHours", Map.of(
                        "MONDAY", List.of(workday), "TUESDAY", List.of(workday),
                        "WEDNESDAY", List.of(workday), "THURSDAY", List.of(workday),
                        "FRIDAY", List.of(workday)),
                "holidays", List.of("2026-12-25", "2026-12-26")));

        sla.insertPolicy("sla-complaint", "Complaint SLA", null, "cal-nl");
        sla.insertTarget("sla-first-response", "sla-complaint", "firstResponse",
                "First response", "PT4H", "PT3H",
                List.of("WAITING_ON_CUSTOMER"), List.of("EMIT_EVENT"));
        sla.insertTarget("sla-resolution", "sla-complaint", "resolution",
                "Resolution", "P5D", "P4D",
                List.of("WAITING_ON_CUSTOMER"), List.of("EMIT_EVENT", "ESCALATE"));
    }

    private void seedDefinition(CaseDefinitionService definitions, CaseDefinitionRepository repo) throws Exception {
        if (repo.findLatest("complaint", "t1").isPresent()) {
            return;
        }
        String json = new String(new ClassPathResource("definitions/complaint-v1.json")
                .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        definitions.deploy(json, "system");
    }
}
```

- [ ] **Step 4: Write the configuration**

`case-management-poc-app/src/main/resources/application.yaml`:

```yaml
spring:
  application:
    name: case-management-poc
  datasource:
    url: jdbc:oracle:thin:@localhost:1521/FREEPDB1
    username: cm
    password: cm
    driver-class-name: oracle.jdbc.OracleDriver
  liquibase:
    change-log: classpath:db/changelog/db.changelog-master.xml

casemgmt:
  enabled: true
  engine-id: eng-a
  engine:
    mode: embedded
  events:
    type-prefix: org.example.cm

operaton:
  bpm:
    database:
      schema-update: true
    admin-user:
      id: admin
      password: admin
```

`case-management-poc-app/src/main/resources/application-remote.yaml` — the same app, remote mode, for the second half of the definition of done:

```yaml
casemgmt:
  engine:
    mode: remote
    remote:
      base-url: http://localhost:8081/engine-rest
      username: admin
      password: admin
```

- [ ] **Step 5: Run the API integration test from Task 24**

Run: `./mvnw -q -pl case-management-poc-app test -Dtest=CaseApiIT`
Expected: PASS, all six tests. This is the first point where the whole stack runs together, so expect to fix wiring here rather than logic.

- [ ] **Step 6: Start the app by hand and walk the complaint path once**

```bash
docker compose up -d
./mvnw -q -pl case-management-poc-app spring-boot:run
```

Then, in another shell:

```bash
curl -u alice:alice -X POST localhost:8080/case-api/v2/cases \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: demo-1' \
  -d '{"caseDefinitionKey":"complaint","tenantId":"t1","title":"Broken widget","priority":"HIGH"}'
```

Expected: `201`, an `ETag` header, and `availableActions` containing `update` and `cancel` but **not** `close` — `registerComplaint` is required and still open. Confirm a `registerComplaint` task appears in `curl -u alice:alice localhost:8080/case-api/v2/tasks`.

- [ ] **Step 7: Commit**

```bash
git add case-management-poc-app/
git commit -m "feat(poc): runnable application with the complaint case type and seed data"
```

---

### Task 27: End-to-end proof and findings

**Files:**
- Create: `case-management-poc-app/src/test/java/org/casemgmt/poc/GenericConsumerIT.java`
- Create: `case-management-poc-app/src/test/java/org/casemgmt/poc/ComplaintEndToEndIT.java`
- Create: `case-management-poc-app/src/test/java/org/casemgmt/poc/RemoteModeIT.java`
- Create: `case-management-poc-app/src/test/java/org/casemgmt/poc/OpenApiConformanceIT.java`
- Create: `FINDINGS.md`

**Interfaces:**
- Consumes: the whole stack
- Produces: the definition of done from spec §1.2

**The generic consumer is the point.** It must not contain the string `complaint`, any form-field name, or any plan-item key. It discovers everything: read the case, take an action from `availableActions[]`, fetch the referenced schema, generate a conforming payload from that schema, post it, repeat. If it cannot complete the case that way, the model-driven contract is insufficient — which is exactly what R3 needs to know.

- [ ] **Step 1: Write the generic consumer test**

```java
package org.casemgmt.poc;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives a case to completion knowing ONLY the API. No case-type constants, no field
 * names, no plan-item keys appear below. This stands in for the deferred UI as R3's
 * partial proof (spec §8): if a renderer could not be written against this contract,
 * this test could not pass either.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GenericConsumerIT extends OracleBackedPocTest {

    @LocalServerPort int port;

    private RestClient client(String user) {
        return RestClient.builder()
                .baseUrl("http://localhost:" + port + "/case-api/v2")
                .defaultHeaders(h -> h.setBasicAuth(user, user))
                .build();
    }

    @Test
    void completesACaseUsingOnlyAvailableActionsAndFormSchemas() {
        RestClient api = client("alice");

        // Discovered, not hardcoded: the consumer learns which case types exist.
        String definitionKey = firstDeployedDefinitionKey(api);

        Map<String, Object> created = api.post().uri("/cases")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("caseDefinitionKey", definitionKey, "tenantId", "t1", "title", "Generic"))
                .retrieve().body(Map.class);

        String caseId = (String) created.get("id");
        int guard = 0;

        while (guard++ < 40) {
            List<Map<String, Object>> tasks = api.get().uri("/cases/{id}/tasks", caseId)
                    .retrieve().body(List.class);

            Optional<Map<String, Object>> actionable = tasks.stream()
                    .filter(t -> !((List<?>) t.get("availableActions")).isEmpty())
                    .findFirst();

            if (actionable.isEmpty()) {
                break;
            }
            driveTask(api, actionable.get(), definitionKey);
        }

        Map<String, Object> finalCase = api.get().uri("/cases/{id}", caseId)
                .retrieve().body(Map.class);

        // Either the case closed, or it is waiting on something no task can advance.
        // Both are informative; a stuck case with zero actionable tasks is a finding.
        assertThat(finalCase.get("state")).isIn("ACTIVE", "CLOSED");
        assertThat((List<?>) api.get().uri("/cases/{id}/events?after=0", caseId)
                .retrieve().body(List.class)).isNotEmpty();
    }

    @SuppressWarnings("unchecked")
    private String firstDeployedDefinitionKey(RestClient api) {
        List<Map<String, Object>> definitions = api.get().uri("/case-definitions?tenantId=t1")
                .retrieve().body(List.class);
        assertThat(definitions)
                .withFailMessage("GET /case-definitions returned nothing — a consumer with no "
                        + "prior knowledge has no entry point")
                .isNotEmpty();
        return (String) definitions.get(0).get("key");
    }

    @SuppressWarnings("unchecked")
    private void driveTask(RestClient api, Map<String, Object> task, String definitionKey) {
        List<Map<String, Object>> actions = (List<Map<String, Object>>) task.get("availableActions");

        Map<String, Object> claim = actions.stream()
                .filter(a -> a.get("action").equals("claim")).findFirst().orElse(null);
        if (claim != null) {
            invoke(api, claim, task, Map.of());
            return;
        }

        Map<String, Object> complete = actions.stream()
                .filter(a -> a.get("action").equals("complete")).findFirst().orElse(null);
        if (complete != null) {
            Map<String, Object> payload = Map.of();
            String formKey = (String) complete.get("formKey");
            if (formKey != null) {
                Map<String, Object> schema = api.get()
                        .uri("/case-definitions/{key}/forms/{formKey}", definitionKey, formKey)
                        .retrieve().body(Map.class);
                payload = SchemaPayloadGenerator.generate(schema);
            }
            invoke(api, complete, task, Map.of("variables", payload));
        }
    }

    private void invoke(RestClient api, Map<String, Object> action,
                        Map<String, Object> resource, Map<String, Object> body) {
        api.method(org.springframework.http.HttpMethod.valueOf((String) action.get("method")))
                .uri((String) action.get("href"))
                .header("If-Match", "\"" + resource.get("version") + "\"")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve().toBodilessEntity();
    }
}
```

And the payload generator it needs — schema in, conforming instance out, no domain knowledge:

```java
package org.casemgmt.poc;

import java.util.*;

/** Builds a minimal instance that satisfies a JSON Schema. Knows schemas, not domains. */
public final class SchemaPayloadGenerator {

    private SchemaPayloadGenerator() {}

    @SuppressWarnings("unchecked")
    public static Map<String, Object> generate(Map<String, Object> schema) {
        Map<String, Object> payload = new LinkedHashMap<>();
        Map<String, Object> properties =
                (Map<String, Object>) schema.getOrDefault("properties", Map.of());
        List<String> required = (List<String>) schema.getOrDefault("required", List.of());

        for (String field : required) {
            Map<String, Object> spec = (Map<String, Object>) properties.get(field);
            payload.put(field, valueFor(spec));
        }
        return payload;
    }

    @SuppressWarnings("unchecked")
    private static Object valueFor(Map<String, Object> spec) {
        if (spec == null) {
            return "generated";
        }
        if (spec.get("enum") instanceof List<?> options && !options.isEmpty()) {
            return options.get(0);
        }
        return switch (String.valueOf(spec.get("type"))) {
            case "integer" -> ((Number) spec.getOrDefault("minimum", 1)).intValue();
            case "number" -> 1.0;
            case "boolean" -> true;
            case "array" -> List.of();
            case "object" -> Map.of();
            default -> "generated";
        };
    }
}
```

- [ ] **Step 2: Run it, and prove it is actually generic**

Run: `./mvnw -q -pl case-management-poc-app test -Dtest=GenericConsumerIT`
Expected: PASS.

Then verify the constraint that gives this test its meaning:

Run: `grep -icE 'complaint|assess|investigat|acknowledg|registerForm' case-management-poc-app/src/test/java/org/casemgmt/poc/GenericConsumerIT.java case-management-poc-app/src/test/java/org/casemgmt/poc/SchemaPayloadGenerator.java`
Expected: `0` for both files. A non-zero count means the test knows the domain and proves less than it claims — fix the test, not the count.

If it gets stuck — no actionable task but the case is not closed — **do not add case-type knowledge to make it pass.** Find out which step the contract failed to expose (a plan item needing `enable`/`start` that no task represents is the likely one), fix the contract by adding plan-item actions to the case response, and record the gap in `FINDINGS.md`. That gap *is* the R3 finding.

- [ ] **Step 3: Write the explicit complaint end-to-end test**

```java
package org.casemgmt.poc;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The named path, asserted step by step, so a regression says exactly where it broke. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ComplaintEndToEndIT extends OracleBackedPocTest {

    @LocalServerPort int port;

    private RestClient api(String user) {
        return RestClient.builder().baseUrl("http://localhost:" + port + "/case-api/v2")
                .defaultHeaders(h -> h.setBasicAuth(user, user)).build();
    }

    @Test
    void walksIntakeAssessmentDecisionAndClosure() {
        RestClient alice = api("alice");

        Map<String, Object> created = alice.post().uri("/cases")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("caseDefinitionKey", "complaint", "tenantId", "t1",
                        "title", "Broken widget", "priority", "HIGH",
                        "variables", Map.of("channel", "web")))
                .retrieve().body(Map.class);
        String caseId = (String) created.get("id");

        // Intake: the required registerComplaint task exists and blocks closing.
        List<Map<String, Object>> tasks = alice.get().uri("/cases/{id}/tasks", caseId)
                .retrieve().body(List.class);
        assertThat(tasks).extracting(t -> t.get("name")).containsExactly("Register complaint");
        assertThat((List<?>) created.get("availableActions"))
                .extracting(a -> ((Map<?, ?>) a).get("action"))
                .doesNotContain("close");

        completeTask(alice, tasks.get(0), Map.of("channel", "web", "summary", "It broke"));

        // The acknowledged milestone fires automatically, which opens the assessment stage.
        assertThat(milestoneAchieved(alice, caseId, "Acknowledged")).isTrue();

        List<Map<String, Object>> assessTasks = alice.get().uri("/cases/{id}/tasks", caseId)
                .retrieve().body(List.class);
        Map<String, Object> assess = assessTasks.stream()
                .filter(t -> t.get("name").equals("Assess complaint")).findFirst().orElseThrow();
        completeTask(alice, assess, Map.of("outcome", "upheld", "rationale", "Clear case"));

        // The decision stage starts a BPMN process correlated to the case.
        List<Map<String, Object>> processes = alice.get().uri("/cases/{id}/processes", caseId)
                .retrieve().body(List.class);
        assertThat(processes).isNotEmpty();

        // The event log tells the whole story.
        List<Map<String, Object>> events = alice.get().uri("/cases/{id}/events?after=0&limit=200", caseId)
                .retrieve().body(List.class);
        assertThat(events).extracting(e -> e.get("type"))
                .anySatisfy(t -> assertThat((String) t).endsWith("case.created"))
                .anySatisfy(t -> assertThat((String) t).endsWith("case.task.completed"))
                .anySatisfy(t -> assertThat((String) t).endsWith("case.milestone.achieved"));
    }

    private void completeTask(RestClient api, Map<String, Object> task, Map<String, Object> variables) {
        api.post().uri("/tasks/{id}/claim", task.get("id"))
                .header("If-Match", "\"" + task.get("version") + "\"")
                .retrieve().toBodilessEntity();

        Map<String, Object> claimed = api.get().uri("/cases/{id}/tasks", task.get("caseId"))
                .retrieve().body(List.class).stream()
                .map(t -> (Map<String, Object>) t)
                .filter(t -> t.get("id").equals(task.get("id")))
                .findFirst().orElseThrow();

        api.post().uri("/tasks/{id}/complete", task.get("id"))
                .header("If-Match", "\"" + claimed.get("version") + "\"")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("variables", variables))
                .retrieve().toBodilessEntity();
    }

    private boolean milestoneAchieved(RestClient api, String caseId, String name) {
        List<Map<String, Object>> milestones = api.get().uri("/cases/{id}/milestones", caseId)
                .retrieve().body(List.class);
        return milestones.stream()
                .anyMatch(m -> name.equals(m.get("name")) && Boolean.TRUE.equals(m.get("achieved")));
    }
}
```

- [ ] **Step 4: Write the remote-mode test**

```java
package org.casemgmt.poc;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static java.time.Duration.ofSeconds;

/**
 * The second half of the definition of done: the same path in remote mode.
 * Tasks appear as PENDING first and become claimable only once the command
 * dispatcher has created them on the remote engine (spec §3.5).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "casemgmt.engine.mode=remote",
                "casemgmt.schedulers.engine-command-interval-ms=500"
        })
class RemoteModeIT extends OracleBackedPocTest {

    @LocalServerPort int port;

    @Test
    void tasksBecomeClaimableOnlyAfterTheEngineConfirms() {
        RestClient api = RestClient.builder()
                .baseUrl("http://localhost:" + port + "/case-api/v2")
                .defaultHeaders(h -> h.setBasicAuth("alice", "alice")).build();

        Map<String, Object> created = api.post().uri("/cases")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("caseDefinitionKey", "complaint", "tenantId", "t1", "title", "Remote"))
                .retrieve().body(Map.class);
        String caseId = (String) created.get("id");

        List<Map<String, Object>> immediately = api.get().uri("/cases/{id}/tasks", caseId)
                .retrieve().body(List.class);
        assertThat(immediately).singleElement()
                .satisfies(t -> {
                    assertThat(t.get("engineSync")).isEqualTo("PENDING");
                    assertThat((List<?>) t.get("availableActions")).isEmpty();
                });

        await().atMost(ofSeconds(30)).untilAsserted(() -> {
            List<Map<String, Object>> later = api.get().uri("/cases/{id}/tasks", caseId)
                    .retrieve().body(List.class);
            assertThat(later).singleElement()
                    .satisfies(t -> {
                        assertThat(t.get("engineSync")).isEqualTo("SYNCED");
                        assertThat((List<?>) t.get("availableActions")).isNotEmpty();
                    });
        });
    }
}
```

**Prerequisite:** remote mode needs an engine to talk to. Add a second Spring Boot application class in `src/test/java` annotated `@SpringBootApplication` with `casemgmt.enabled=false` and `operaton-bpm-spring-boot-starter-rest` on the classpath, started on a fixed port `8081` by a `@BeforeAll`, and point `casemgmt.engine.remote.base-url` at it. Add `org.awaitility:awaitility` as a test dependency.

- [ ] **Step 5: Write the OpenAPI conformance test**

```java
package org.casemgmt.poc;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.model.SimpleRequest;
import com.atlassian.oai.validator.model.SimpleResponse;
import com.atlassian.oai.validator.report.ValidationReport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Keeps openapi-specs.md the contract of record rather than decoration (spec §9). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OpenApiConformanceIT extends OracleBackedPocTest {

    @LocalServerPort int port;

    @Test
    void caseResponsesConformToTheSpec() throws Exception {
        // openapi-specs.md is a YAML document in a .md file; strip nothing, it parses as YAML.
        String spec = Files.readString(Path.of("..", "openapi-specs.md"));
        OpenApiInteractionValidator validator = OpenApiInteractionValidator
                .createForInlineApiSpecification(spec).build();

        RestClient api = RestClient.builder()
                .baseUrl("http://localhost:" + port + "/case-api/v2")
                .defaultHeaders(h -> h.setBasicAuth("alice", "alice")).build();

        var response = api.post().uri("/cases")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("caseDefinitionKey", "complaint", "tenantId", "t1", "title", "Spec"))
                .retrieve().toEntity(String.class);

        ValidationReport report = validator.validateResponse("/cases",
                com.atlassian.oai.validator.model.Request.Method.POST,
                SimpleResponse.Builder.status(response.getStatusCode().value())
                        .withContentType("application/json")
                        .withBody(response.getBody())
                        .build());

        assertThat(report.getMessages())
                .withFailMessage("Response does not match openapi-specs.md: %s", report)
                .isEmpty();
    }
}
```

Expect this test to fail the first time. When it does, decide deliberately per mismatch: either the implementation is wrong (fix the code) or the spec is wrong (fix `openapi-specs.md` and note it). Record every spec change in `FINDINGS.md` — spec defects found by implementation are among the most valuable outputs of this PoC.

- [ ] **Step 6: Write `FINDINGS.md`**

```markdown
# PoC Findings

Verdicts on the four risks from
`docs/superpowers/specs/2026-07-31-case-management-poc-design.md` §1.1.
Written as work happens, not at the end.

## R1 — Plan-item state machine

**Verdict:** _(held / held with changes / did not hold)_

- Did fixpoint evaluation after every mutation behave as specified?
- Was the iteration cap ever hit by a legitimate model?
- What did repetition cost, and did `repeatable()` need the extra "criteria changed" guard?
- Spec changes required: …

## R2 — Operaton integration

**Verdict:**

- Did the same contract suite pass unchanged against both gateways? Which tests needed
  per-mode handling, and why?
- Standalone-task variable queries: which query form actually worked?
- Remote mode: how visible was eventual consistency in practice?
- Spec changes required: …

## R3 — Model-driven contract (partial — no UI)

**Verdict:**

- Did the generic consumer complete the case using only `availableActions[]` and schemas?
- Where did the contract fall short (actions not exposed, missing hrefs, schema gaps)?
- **Still unproven without a UI:** whether the schemas render into something usable,
  whether the action model maps onto real interaction patterns, whether event-cursor
  polling is a workable live-update mechanism.

## R4 — Events and federation

**Verdict:**

- Did the outbox hold under rollback? Any event emitted for a rolled-back change?
- Retry/DLQ behaviour observed.
- Would this event stream genuinely support a cross-engine index?

## Deviations that must not be inherited

- Webhook secrets are held in an in-memory map for signing (Task 25). Production needs
  a secret store or reversible encryption.
- `CM_ENGINE_COMMAND` and `ENGINE_SYNC_` are PoC-only additions (spec D3).
- Direct writes in Operaton Tasklist bypass the state machine in remote mode (spec D4).
- Basic auth instead of the spec's OAuth2 (spec D2).

## Spec and DDL defects found

| Where | Defect | Fix applied |
|---|---|---|
| | | |

## Dependency notes

- Operaton 2.1.3 pins Spring Boot 4.0.7 / Spring 7.0.8 (the repo's AGENTS.md says 3.5.6 — stale).
- Jackson generation resolved: _(record Jackson 2 or 3 from Task 1 Step 6)_
```

- [ ] **Step 7: Run the whole suite**

Run: `./mvnw clean verify`
Expected: BUILD SUCCESS with every test green.

- [ ] **Step 8: Commit**

```bash
git add case-management-poc-app/ FINDINGS.md
git commit -m "test(poc): generic consumer, end-to-end, remote mode and OpenAPI conformance"
```

---

## Plan Self-Review

**Spec coverage.** Every section of the design spec maps to a task: §2.1 endpoints → Tasks 24–25; §3.2 artifacts → Task 1; §3.3 configuration → Task 25; §3.4 gateway → Tasks 10–12; §3.5 consistency → Task 13; §4.1–4.2 definitions and the complaint model → Tasks 5, 26; §4.3 evaluation → Tasks 8–9; §4.4 JUEL sandbox → Task 7; §4.5 ActionPolicy → Task 23; §4.6 forms → Tasks 5, 17; §4.7 SLA → Tasks 20–21; §5 persistence → Tasks 2, 4, 6; §6.1–6.2 outbox and CloudEvents → Tasks 14, 19; §6.3–6.5 ETag, idempotency, errors → Task 22; §7 identity → Task 26; §8 the three UI-substitute obligations → Tasks 23, 27; §9 testing → distributed across every task plus Task 27; §10 environment → Tasks 1–2; §11 deviations → recorded in `FINDINGS.md` (Task 27).

**Known gaps, deliberately left:** `GET /queues`, bulk operations and saved filters are out of scope per §2.2. `POST /cases/{id}/escalate` is deferred with the rest of the SLA-action surface even though `BREACH_ACTIONS_JSON_` can name `ESCALATE` — the sweeper emits the event and stops there.

**Three places where the plan tells the implementer to expect a fight**, rather than pretending the code is right: the standalone-task variable query in Task 11, `POST /task/create` semantics in Task 12, and the `TransitionApplier` double-write in Task 16. Each names the symptom, the likely fix, and instructs recording the outcome. Task 17 contains one deliberate wart with an explicit instruction to fix it before running — treat that as a review checkpoint, not a typo.

