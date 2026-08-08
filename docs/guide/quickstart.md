# Quick start

By the end you'll have a running service, a deployed case type, and a case you drove from creation
to close over HTTP. About 15 minutes.

## 1. Add the starter

Pull in the starter and **exactly one** engine module — both are `optional` in the starter's own
POM so you choose which you carry.

```xml
<dependency>
  <groupId>org.casemgmt</groupId>
  <artifactId>case-management-spring-boot-starter</artifactId>
</dependency>

<!-- pick ONE engine module -->
<dependency>
  <groupId>org.casemgmt</groupId>
  <artifactId>case-management-engine-embedded</artifactId>
</dependency>

<!-- embedded mode also needs the Operaton starter itself -->
<dependency>
  <groupId>org.operaton.bpm.springboot</groupId>
  <artifactId>operaton-bpm-spring-boot-starter</artifactId>
</dependency>
```

> **Do not add `spring-boot-starter-parent`.** Spring Boot's version is pinned transitively by
> Operaton. A Boot parent will fight it.

## 2. Start Oracle

The schema uses `IS JSON` check constraints, so H2 is not a substitute. Liquibase creates
everything on first boot.

```bash
docker compose up -d oracle
```

## 3. Configure

```yaml
spring:
  datasource:
    url: jdbc:oracle:thin:@localhost:1521/FREEPDB1
    username: cm
    password: cm
  liquibase:
    change-log: classpath:db/changelog/db.changelog-master.xml

casemgmt:
  enabled: true
  engine-id: eng-a          # stamped as the CloudEvents `source`
  engine:
    mode: embedded
  events:
    type-prefix: com.acme.cases   # no default — startup fails without it
```

`type-prefix` deliberately has no default, so events can never ship under an accidental namespace.

## 4. Give the caller a tenant

Every request runs under exactly one tenant, read from the principal's authorities — **never from a
request body**. Your principal needs:

- exactly one `tenant:<id>` authority (zero or several is a `403`)
- whatever identity groups match your tasks' `candidateGroups`, e.g. `handlers`

## 5. Deploy a case type

Two work items: register the request, then approve it. The second only opens once the first is done.

```http
POST /case-api/v2/case-definitions
```
```json
{
  "key": "access-request",
  "name": "Access Request",
  "roles": ["owner", "handler"],
  "forms": {
    "approveForm": {
      "type": "object",
      "required": ["decision"],
      "properties": {
        "decision": { "type": "string", "enum": ["grant", "deny"] }
      }
    }
  },
  "planItems": [
    { "defKey": "register", "type": "HUMAN_TASK", "name": "Register request",
      "required": true, "candidateGroups": ["handlers"], "sortOrder": 10 },

    { "defKey": "approve", "type": "HUMAN_TASK", "name": "Approve",
      "required": true, "formKey": "approveForm", "candidateGroups": ["handlers"],
      "entryCriteria": ["${items.register.state == 'COMPLETED'}"], "sortOrder": 20 }
  ]
}
```

Deploying is admin-gated. If you include `tenantId` it must equal yours, or you get `403`.

## 6. Start a case

```http
POST /case-api/v2/cases
```
```json
{ "caseDefinitionKey": "access-request", "title": "Laptop access for R. Bok" }
```

You get `201` with an `ETag` and an `availableActions[]` array. **Read that array instead of
hardcoding what to do next** — it's the whole point of the model.

## 7. Work the case

```http
GET  /case-api/v2/tasks                      # your worklist
POST /case-api/v2/tasks/{taskId}/claim       # If-Match: "<version>"
POST /case-api/v2/tasks/{taskId}/complete    # If-Match: "<version>"
     { "variables": { "decision": "grant" } }
```

Completing `register` satisfies `approve`'s entry criterion, so the second task appears without you
doing anything. Re-read the case and `availableActions[]` will now offer `close`.

## 8. Close it

```http
POST /case-api/v2/cases/{caseId}/close       # If-Match: "<version>"
     { "outcome": "granted" }
```

Close is refused while a `required` item is unfinished — you get `409 illegal-transition`, and the
body names what's blocking.

---

## What you just relied on

You never told the service that approval follows registration. You wrote a **condition**, and the
evaluator worked it out — after every single change. That mechanism is what the rest of the guide
is about; start with [Core concepts](concepts.md).
