# Quick start: complete your first BPMN case

This tutorial starts with a fresh checkout and ends with a closed BPMN-backed complaint. You will
start Oracle, build and run the PoC application, call the case API, complete three human tasks,
observe two milestones, and see the root BPMN process close the case automatically.

Allow 30–45 minutes the first time. Most of that time is dependency download and Oracle startup.

## What you are about to run

The repository contains a small complaint process:

```text
Start
  -> Intake stage
     -> Register complaint
  -> Acknowledged milestone
  -> Assessment stage
     -> Assess complaint
  -> Decided milestone
  -> Close complaint
  -> End
```

Operaton executes that BPMN flow. The case-management service observes it and exposes ordinary
case, task, stage, and milestone resources under `/case-api/v2`.

You do not need to understand BPMN XML before starting. The important beginner idea is that a
**token** moves from left to right through the diagram. A user task pauses the token until someone
claims and completes the task. When the token reaches the final end event, the root process ends
and the case closes.

## 1. Install the prerequisites

You need:

- Git.
- Docker Desktop or another Docker-compatible runtime with Compose.
- Java 21. The project compiles for Java 21 even if a newer JDK happens to work locally.
- `curl` for HTTP requests.
- `jq` for reading JSON in the terminal. It is optional, but all commands below use it.

You do **not** need to install Maven. The checked-in `mvnw` wrapper downloads the correct Maven
version.

From the repository root, check the tools:

```bash
java -version
docker version
docker compose version
curl --version
jq --version
```

The Java output should start with version `21`. If Docker commands cannot connect, start Docker
Desktop and wait until it reports that the engine is running.

## 2. Start Oracle

The application uses Oracle-specific JSON constraints and migrations, so H2 is not a replacement
for this tutorial.

```bash
docker compose up -d oracle
docker compose ps
```

The first command downloads and starts Oracle Free 23. The second shows its state. Wait until the
`oracle` service is healthy. You can watch startup logs with:

```bash
docker compose logs -f oracle
```

Press `Ctrl+C` when the log says the database is ready. That stops following the log; it does not
stop Oracle.

The Compose startup hook creates or repairs the `cm` database user used by
[`application.yaml`](../../case-management-poc-app/src/main/resources/application.yaml). The hook
is idempotent, so restarting an existing tutorial container is safe.

## 3. Create a local webhook encryption key

The default webhook store encrypts signing secrets. The application deliberately refuses to start
without an encryption key.

In the terminal where you will start the application, run:

```bash
export CASEMGMT_WEBHOOK_SECRET_ENCRYPTION_KEY="$(openssl rand -base64 32)"
```

This creates a temporary development key for this shell. Do not copy this pattern into production:
production keys belong in a secret manager and must remain stable across restarts.

## 4. Build the repository

Build every Java module and create the executable PoC JAR:

```bash
./mvnw -B -DskipTests package
```

`-DskipTests` keeps the first-run tutorial focused and fast. CI runs the full `clean install` test
suite on Java 21 with Docker.

A successful build ends with `BUILD SUCCESS`. The runnable file is:

```text
case-management-poc-app/target/case-management-poc-app-0.1.0-SNAPSHOT.jar
```

## 5. Start the application

```bash
java -jar case-management-poc-app/target/case-management-poc-app-0.1.0-SNAPSHOT.jar
```

Keep this terminal open. On first startup the application:

1. Connects to Oracle at `localhost:1521/FREEPDB1`.
2. Runs the Liquibase schema migrations.
3. Creates the embedded Operaton process-engine tables.
4. Seeds local users and groups.
5. Publishes the `complaint-bpmn` orchestration, contract, and presentation releases.
6. Binds those releases into an immutable case-definition version.

Wait for a log line containing `Started PocApplication`. The API now listens on
`http://localhost:8080`.

If startup fails, go to [Troubleshooting](#troubleshooting-the-quick-start) before changing code.

## 6. Open a second terminal

Leave the application running and open another terminal at the repository root. Define two short
variables so the commands are easier to read:

```bash
export CM_API="http://localhost:8080/case-api/v2"
export CM_AUTH="alice:alice"
```

The PoC users are deliberately simple:

| User | Password | Groups | Useful for |
|---|---|---|---|
| `alice` | `alice` | `intake`, `handlers`, `tenant:t1` | Completing this tutorial |
| `bob` | `bob` | `handlers`, `reviewers`, `tenant:t1` | Testing another worker |
| `carol` | `carol` | `reviewers`, `tenant:t1` | Testing restricted worklists |
| `olivia` | `olivia` | `admin`, `tenant:t1` | Publishing definitions |

These credentials are development data. Never use this basic-auth setup in production.

## 7. Check that the API is reachable

```bash
curl --fail --silent --show-error \
  --user "$CM_AUTH" \
  "$CM_API/case-definitions" | jq
```

You should see both `complaint` and `complaint-bpmn`. The BPMN entry includes:

```json
{
  "key": "complaint-bpmn",
  "orchestrationMode": "BPMN",
  "deploymentStatus": "ACTIVE",
  "bindingStatus": "ACTIVE",
  "activatedAt": "...",
  "engineProcessDefinitionKey": "complaint-bpmn",
  "engineProcessDefinitionVersion": 1,
  "engineTenantId": "t1",
  "orchestrationReleaseId": "...",
  "contractReleaseId": "...",
  "presentationReleaseId": "..."
}
```

The exact IDs are generated during publication and will differ on your machine.

## 8. Create a BPMN-backed case

```bash
export CASE_ID="$(curl --fail --silent --show-error \
  --user "$CM_AUTH" \
  --request POST \
  --header 'Content-Type: application/json' \
  --header 'Idempotency-Key: quickstart-complaint-1' \
  --data '{
    "caseDefinitionKey": "complaint-bpmn",
    "title": "My first BPMN complaint",
    "businessKey": "QS-001",
    "priority": "HIGH"
  }' \
  "$CM_API/cases" | tee /tmp/casemgmt-created-case.json | jq -r '.id')"

echo "$CASE_ID"
jq . /tmp/casemgmt-created-case.json
```

The response is `201 Created`. Important fields are:

- `id`: the case-management case ID captured in `CASE_ID`.
- `rootProcessInstanceId`: the Operaton process instance started for this case.
- `state: ACTIVE`: the root process is running.
- `projectionStatus: CURRENT`: the local projection is current.
- `availableActions`: actions the server has authorized right now.

Notice that `close` is not advertised. BPMN cases close when their root process ends.

If you repeat the command with the same `Idempotency-Key` and identical body, the original create
result is replayed instead of creating another case. Use a different key when you intentionally
want another case.

## 9. Find and claim the registration task

List the case's tasks:

```bash
curl --fail --silent --show-error \
  --user "$CM_AUTH" \
  "$CM_API/cases/$CASE_ID/tasks" | jq
```

There should be one open task named `Register complaint`. Capture its ID:

```bash
export TASK_ID="$(curl --fail --silent --show-error \
  --user "$CM_AUTH" \
  "$CM_API/cases/$CASE_ID/tasks" \
  | jq -r '.[] | select(.name == "Register complaint") | .id')"

echo "$TASK_ID"
```

Claiming says “Alice is now responsible for this task”:

```bash
curl --fail --silent --show-error \
  --user "$CM_AUTH" \
  --request POST \
  --header 'If-Match: *' \
  "$CM_API/tasks/$TASK_ID/claim" | jq
```

`If-Match` protects against two people editing the same resource version. The wildcard is
convenient for this single-user tutorial. Production clients should send the exact ETag/version
they last read and handle conflicts by re-reading.

## 10. Complete the registration form

The BPMN user task names `registerForm`. The pinned contract says that `channel` and `summary` are
required and that `channel` must be one of `web`, `phone`, `letter`, or `email`.

```bash
curl --fail --silent --show-error \
  --user "$CM_AUTH" \
  --request POST \
  --header 'Content-Type: application/json' \
  --header 'If-Match: *' \
  --data '{
    "variables": {
      "channel": "web",
      "summary": "Learning how BPMN-backed cases work",
      "amount": 100
    }
  }' \
  "$CM_API/tasks/$TASK_ID/complete" | jq
```

Completing the task lets the BPMN token leave the Intake subprocess. The embedded Operaton event
bridge projects the stage completion, achieves the `Acknowledged` milestone, and creates the next
user task in the same application environment.

Check the milestones:

```bash
curl --fail --silent --show-error \
  --user "$CM_AUTH" \
  "$CM_API/cases/$CASE_ID/milestones" | jq
```

`Acknowledged` should have `achieved: true`.

## 11. Assess the complaint

Capture and claim the next task:

```bash
export TASK_ID="$(curl --fail --silent --show-error \
  --user "$CM_AUTH" \
  "$CM_API/cases/$CASE_ID/tasks" \
  | jq -r '.[] | select(.name == "Assess complaint") | .id')"

curl --fail --silent --show-error \
  --user "$CM_AUTH" \
  --request POST \
  --header 'If-Match: *' \
  "$CM_API/tasks/$TASK_ID/claim" | jq
```

Complete it with a value allowed by `assessForm`:

```bash
curl --fail --silent --show-error \
  --user "$CM_AUTH" \
  --request POST \
  --header 'Content-Type: application/json' \
  --header 'If-Match: *' \
  --data '{
    "variables": {
      "outcome": "upheld",
      "rationale": "The complaint is supported by the supplied information"
    }
  }' \
  "$CM_API/tasks/$TASK_ID/complete" | jq
```

The `Decided` milestone should now be achieved:

```bash
curl --fail --silent --show-error \
  --user "$CM_AUTH" \
  "$CM_API/cases/$CASE_ID/milestones" | jq
```

## 12. Complete the final task

```bash
export TASK_ID="$(curl --fail --silent --show-error \
  --user "$CM_AUTH" \
  "$CM_API/cases/$CASE_ID/tasks" \
  | jq -r '.[] | select(.name == "Close complaint") | .id')"

curl --fail --silent --show-error \
  --user "$CM_AUTH" \
  --request POST \
  --header 'If-Match: *' \
  "$CM_API/tasks/$TASK_ID/claim" | jq

curl --fail --silent --show-error \
  --user "$CM_AUTH" \
  --request POST \
  --header 'Content-Type: application/json' \
  --header 'If-Match: *' \
  --data '{"variables":{"outcome":"resolved"}}' \
  "$CM_API/tasks/$TASK_ID/complete" | jq
```

This completes the last BPMN user task. The token reaches the BPMN end event, the root process
ends, and the case-management projection changes the case to `CLOSED`.

## 13. Verify the result

```bash
curl --fail --silent --show-error \
  --user "$CM_AUTH" \
  "$CM_API/cases/$CASE_ID" | jq
```

Look for:

```json
{
  "state": "CLOSED",
  "projectionStatus": "CURRENT",
  "availableActions": []
}
```

Inspect the projected stages, tasks, and milestones:

```bash
curl --fail --silent --show-error --user "$CM_AUTH" \
  "$CM_API/cases/$CASE_ID/plan-items" | jq

curl --fail --silent --show-error --user "$CM_AUTH" \
  "$CM_API/cases/$CASE_ID/tasks" | jq

curl --fail --silent --show-error --user "$CM_AUTH" \
  "$CM_API/cases/$CASE_ID/milestones" | jq
```

You have now completed the whole BPMN case journey. At no point did you call
`POST /cases/{id}/close`; root-process completion owned that decision.

## 14. See the three source artifacts

The seeded definition comes from:

- [`complaint-bpmn.bpmn`](../../case-management-poc-app/src/main/resources/processes/complaint-bpmn.bpmn) — executable flow.
- [`complaint-bpmn-contract.json`](../../case-management-poc-app/src/main/resources/definitions/complaint-bpmn-contract.json) — fields, JSON Schema forms, groups, roles, search, and ad-hoc action.
- [`complaint-bpmn-presentation.json`](../../case-management-poc-app/src/main/resources/definitions/complaint-bpmn-presentation.json) — Scenario A UI sections.

Open the BPMN file in a BPMN 2.0 modeler to see the diagram. The repository also contains a
[case-management element-template catalog](../../modeler-templates/case-management.json). The
[Operaton download guide](https://docs.operaton.org/docs/documentation/introduction/downloading-operaton/)
points beginners to a compatible BPMN modeler.

The form key and candidate groups live on each BPMN user task. The contract must declare matching
forms and groups. The presentation manifest may reference only fields, forms, actions, search
profiles, and primitives supported by the pinned contract and UI runtime.

Continue with [Core concepts](concepts.md), then [Writing a case definition](case-definitions.md).

## 15. Stop the tutorial

In the application terminal, press `Ctrl+C`. To keep the current Oracle container for the next
session, stop it without removing it:

```bash
docker compose stop oracle
```

Restart it later with `docker compose start oracle`. The current Compose file does not mount the
Oracle data directory as a persistent volume, so `docker compose down` removes the database
container and gives you an empty tutorial database on the next `up`.

## Troubleshooting the quick start

### `java -version` is not 21

Install a Java 21 JDK and make it the active JDK. Maven's compiler target does not replace the JDK
that launches Maven or the application.

### Docker cannot connect

Start Docker Desktop, wait for it to be ready, and retry `docker version`. On macOS, Docker may
need more memory if the Oracle container exits during startup.

### Oracle never becomes healthy

Read its logs:

```bash
docker compose logs oracle
```

An exit code such as `137` or `139` normally indicates a Docker resource or platform problem,
not a Liquibase validation error—the database process ended before the application connected.

If the log says `DATABASE IS READY TO USE!` but `docker compose ps` still says `unhealthy`, apply
the current Compose configuration and restart Oracle:

```bash
docker compose up -d --force-recreate oracle
docker compose ps
```

The quickstart does not persist Oracle data outside its container. Force-recreating it therefore
starts with an empty tutorial database. Do not use this recovery command for a database containing
data you need to retain.

### Startup ends at `processEngineFactoryBean` or reports `ORA-01017`

The Spring bean error is only a wrapper. Near the bottom of the stack trace, `ORA-01017` means the
embedded Operaton engine could not log in as `cm`. This can happen when Oracle's first
initialization was interrupted before the quickstart account was created.

First confirm that Oracle is healthy:

```bash
docker compose ps
```

If it is unhealthy, use the force-recreate recovery directly above and wait for `(healthy)` before
starting the JAR again. The current startup hook creates, unlocks, and resets the local `cm/cm`
account on every container start.

### The application says the webhook key is missing

Export `CASEMGMT_WEBHOOK_SECRET_ENCRYPTION_KEY` in the same terminal that runs `java -jar`.
Environment variables set in another terminal are not shared automatically.

### Port 8080 or 1521 is already in use

Stop the program using that port, or change the host port and matching datasource/application
configuration. Do not start a second Oracle container on the same host port.

### Every API call returns `401`

Use one of the seeded username/password pairs and include `--user "$CM_AUTH"`.

### Every API call returns `403`

The caller needs exactly one `tenant:<id>` authority. The seeded users have `tenant:t1`. Definition
publication additionally needs the `admin` group, so use `olivia` for that chapter.

### `TASK_ID` is empty

Print the current tasks without filtering:

```bash
curl --silent --user "$CM_AUTH" "$CM_API/cases/$CASE_ID/tasks" | jq
```

Check that the previous task completed successfully and that you copied the task name exactly.

### Form completion returns `422`

The submitted `variables` failed the pinned JSON Schema. Read `violations[]` in the
`application/problem+json` response. Fix the named field rather than bypassing validation.

### A response says `projectionStatus: STALE`

In embedded mode this is unexpected and should be investigated. In remote mode it means the local
projection has not been reconciled with Operaton recently; see [Remote observation and eventual
consistency](operations.md#remote-observation-and-eventual-consistency).
