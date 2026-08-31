# Writing a case definition

This chapter takes you from a process idea to a publishable BPMN case-definition version. Read
[Core concepts](concepts.md) first if “user task”, “token”, or “root process” is unfamiliar.

## Start with a small process

For your first model, choose a happy path with two or three human tasks. Avoid parallel gateways,
timers, messages, call activities, and multi-instance work until the basic path runs.

Write the flow in plain language before opening a modeler:

```text
Receive request -> Review request -> Record decision -> End
```

Then decide:

- Which steps require a person?
- Which group can claim each task?
- Which data must each person submit?
- Which sections should appear as stages?
- Which events matter enough to record as milestones?
- Which optional work should be available outside the normal token flow?

## Install a BPMN modeler

Use a BPMN 2.0 desktop modeler compatible with Operaton extension attributes. The
[Operaton download documentation](https://docs.operaton.org/docs/documentation/introduction/downloading-operaton/)
describes the supported modeling tool.

Import the checked-in element-template catalog:

```text
modeler-templates/case-management.json
```

The template helps authors enter case-management metadata consistently. Studio is planned but is
not yet the production authoring path, so Desktop Modeler remains the supported starting point.

## The artifact set

Create a folder for one case type:

```text
my-request/
├── contract.json
├── presentation.json
└── processes/
    └── my-request.bpmn
```

Optional `.dmn` files can sit under any relative subdirectory. The root archive paths
`contract.json` and `presentation.json` are fixed.

The contract `key` and root BPMN process ID must be the same stable key. Use lowercase words and
hyphens, for example `my-request`. Do not change the key when publishing a new version.

## 1. Draw the orchestration

Create one executable BPMN process:

```xml
<bpmn:process id="my-request" name="My request" isExecutable="true">
```

Add one start event, your tasks in sequence, and an end event. Give every element a stable,
meaningful ID. IDs are used for migration and observation; changing them casually makes future
process migration harder.

### User tasks

For each human step, use a BPMN user task:

```xml
<bpmn:userTask id="review-request"
               name="Review request"
               operaton:formKey="reviewForm"
               operaton:candidateGroups="reviewers"/>
```

- `id` is the stable BPMN element ID.
- `name` is shown to users.
- `operaton:formKey` must match a form in `contract.json`.
- `operaton:candidateGroups` must use groups declared by the contract and identity provider.
- `casemgmt:slaTargetId` is optional and must match an entry in the contract's `slaBindings`.

### Extension namespaces

The prefix you bind is your own choice, but the namespace URI is not — publication reads each
property from exactly one namespace and rejects a recognised name found anywhere else:

| Property | Namespace URI |
|---|---|
| `formKey`, `candidateGroups`, `decisionRef` | `http://operaton.org/schema/1.0/bpmn` |
| `stage`, `milestoneId`, `slaTargetId` | `https://casemgmt.org/bpmn` |

A misspelled prefix therefore fails at publication with the element ID and the expected
namespace, rather than being silently ignored and diverging once a live case reaches the element.
`slaRef` was the earlier spelling of `slaTargetId` and is rejected outright.

See Operaton's [user-task reference](https://docs.operaton.org/docs/documentation/reference/bpmn20/tasks/user-task/)
for the underlying BPMN extension attributes. In this project, the form key points to a contract
JSON Schema rendered by the Scenario A UI; it is not an Operaton Tasklist HTML form.

### Stages

Use an embedded subprocess when several activities form a meaningful stage. Apply the
case-management stage marker:

```xml
<bpmn:subProcess id="review" name="Review" casemgmt:stage="true">
```

Each runtime occurrence projects as a `STAGE` plan item. Keep the marker on a subprocess, not on an
arbitrary task.

### Milestones

Use an intermediate throw event for a point-in-time milestone:

```xml
<bpmn:intermediateThrowEvent id="reviewed"
                            name="Reviewed"
                            casemgmt:milestoneId="reviewed"/>
```

Milestone IDs must be unique in the orchestration release. Re-observing the same occurrence is
idempotent and does not create another achievement.

### Root process rules

- Exactly one process must be the root process whose ID equals the contract key.
- The root process must be executable.
- Root completion closes the case.
- A called or ad-hoc child process ending must not close the case.
- Static call-activity and DMN decision references must resolve inside the orchestration release.

## 2. Write the contract

The contract is the server-enforced vocabulary shared by BPMN, API, and UI. Use
[`case-contract-v1.schema.json`](../schemas/case-contract-v1.schema.json) as the machine-readable
reference.

A small example:

```json
{
  "key": "my-request",
  "name": "My request",
  "version": "1.0",
  "orchestrationMode": "BPMN",
  "roles": ["owner", "handler", "watcher"],
  "candidateGroups": ["reviewers"],
  "fields": {
    "requestSummary": {"schema": {"type": "string"}},
    "decision": {"schema": {"type": "string"}},
    "rationale": {"schema": {"type": "string"}}
  },
  "forms": {
    "reviewForm": {
      "schema": {
        "type": "object",
        "required": ["decision"],
        "properties": {
          "decision": {
            "type": "string",
            "enum": ["approve", "reject"]
          },
          "rationale": {"type": "string"}
        }
      },
      "uiSchema": {
        "rationale": {"widget": "textarea"}
      }
    }
  },
  "searchProfiles": {
    "requests": {"scopes": ["cases", "documents"]}
  },
  "adHocActions": []
}
```

### Fields

`fields` is the canonical field catalog. The key is the stable field ID used by forms and
presentation sections. Put validation rules under `schema`.

Field values are not automatically public. Worker Permissions decide which values reach case,
task, document, collaboration, search, error, and extension-component DTOs. Missing or empty field
decisions deny values.

### Forms

Each form contains:

- `schema`: JSON Schema used by the server on task/action execution.
- `uiSchema`: optional bounded presentation hints for the Lit renderer.

The BPMN form key is a reference, not a duplicate schema. If the task says
`operaton:formKey="reviewForm"`, the contract must contain `forms.reviewForm`.

### Roles and candidate groups

Declare every role used by ad-hoc actions and every candidate group used by BPMN user tasks.
Contract vocabulary and real identity-provider groups must match exactly, including case.

### SLA bindings

An SLA reference on BPMN must resolve to a contract `slaBindings` entry. The referenced policy and
calendar must exist operationally before cases depend on it.

### Ad-hoc actions

Ad-hoc process and message requests are explicit external capabilities. They cannot activate a
human task: that remains exclusively in the BPMN token flow. Supported types are:

| Type | Required reference | Effect |
|---|---|---|
| `PROCESS` | Exact active `orchestrationReleaseId` and `processDefinitionKey` | Requests the pinned discretionary process through the normal command path |
| `MESSAGE` | `messageName` | Requests message correlation through the normal command path |

Every action needs an ID, type, at least one role, and an availability expression. Execution
reauthorizes, validates the form, checks optimistic locking, audits, and publishes requested,
confirmed, or failed events. In remote mode an `Idempotency-Key` binds the request to one durable
operation; no local pending task, plan item, or process projection is written before engine
evidence arrives.

Example:

```json
{
  "id": "request-information-process",
  "type": "PROCESS",
  "name": "Request information",
  "roles": ["handler"],
  "orchestrationReleaseId": "orchestration:request-information:1",
  "processDefinitionKey": "request-information",
  "availabilityExpression": "${case.state == 'ACTIVE'}"
}
```

## 3. Write the Scenario A presentation manifest

Scenario A means the Lit shell interprets a server-published manifest and fetches ordinary REST
resources. There is no composed `/view` endpoint and no `ViewComposer`.

Use [`presentation-manifest-v1.schema.json`](../schemas/presentation-manifest-v1.schema.json) as the
reference:

```json
{
  "version": "1.0",
  "localeNamespace": "my-request",
  "sections": [
    {
      "id": "summary",
      "primitive": "summary-fields",
      "title": "Request",
      "fields": ["system:title", "system:state", "requestSummary", "decision"]
    },
    {"id": "progress", "primitive": "plan-tree", "title": "Progress"},
    {"id": "tasks", "primitive": "task-list", "title": "Work"},
    {"id": "milestones", "primitive": "milestone-list", "title": "Milestones"},
    {"id": "search", "primitive": "search", "searchProfileId": "requests"},
    {"id": "actions", "primitive": "actions", "actions": ["cancel"]}
  ]
}
```

The manifest may reference only fields, forms, actions, search profiles, and extension components
allowed by the pinned contract/runtime. The shell renders only server-returned
`availableActions`; listing an action in the manifest does not grant it.

Unsupported major manifest versions are rejected predictably. Custom elements must come from the
allowlisted, versioned registry and receive masked props plus a case-scoped capability facade—never
tokens, an unrestricted API client, or a raw portal adapter.

## 4. Validate the artifact set mentally

Before publishing, check:

- [ ] Contract `key` equals the root BPMN process ID.
- [ ] There is exactly one root process.
- [ ] Every BPMN user-task form key exists in `forms`.
- [ ] Every BPMN candidate group exists in `candidateGroups`.
- [ ] Every milestone ID is unique.
- [ ] Every BPMN SLA reference exists in `slaBindings`.
- [ ] Static DMN and call-activity references resolve within the orchestration release.
- [ ] Every presentation field exists in the field catalog or starts with `system:`.
- [ ] Every presentation form, action, and search profile exists in the contract.
- [ ] Every ad-hoc action uses declared roles/groups and has the fields required by its type.
- [ ] XML contains no `DOCTYPE` or entity declarations.

The server repeats these checks. Client-side validation improves feedback but never replaces
server validation.

## 5. Publish with one combined ZIP

From the directory containing `contract.json`, `presentation.json`, and `processes/`:

```bash
zip -r my-request.zip contract.json presentation.json processes
```

Publish as the PoC admin:

```bash
curl --fail --silent --show-error \
  --user olivia:olivia \
  --request POST \
  --header 'Content-Type: application/zip' \
  --data-binary @my-request.zip \
  http://localhost:8080/case-api/v2/case-definitions | jq
```

The server safely unpacks the archive, publishes all three immutable releases, performs
cross-artifact validation, deploys orchestration, and binds a new definition version.

The ZIP accepts:

- `contract.json` at the archive root.
- `presentation.json` at the archive root.
- At least one `.bpmn` file at any safe relative path.
- Optional `.dmn` files at safe relative paths.

It rejects absolute/backslash/`..` paths, normalized aliases, duplicate paths, unsupported file
types, XML entities, more than 100 files, and more than 25 MiB decompressed content.

Embedded publication returns an `ACTIVE` binding immediately. Remote publication can return
`DEPLOYING`; it later becomes `ACTIVE` or `FAILED` after outbox delivery.

## 6. Publish releases independently

Use independent publication when only one artifact changes or when release management publishes
and approves artifacts separately.

### Publish orchestration

```bash
curl --user olivia:olivia --request POST \
  --header 'Content-Type: application/bpmn+xml' \
  --data-binary @processes/my-request.bpmn \
  http://localhost:8080/case-api/v2/case-definitions/my-request/orchestration-releases
```

Use `application/zip` instead when orchestration contains multiple BPMN/DMN resources.

### Publish the contract

```bash
curl --user olivia:olivia --request POST \
  --header 'Content-Type: application/json' \
  --data-binary @contract.json \
  http://localhost:8080/case-api/v2/case-definitions/my-request/contract-releases
```

### Publish the presentation

```bash
curl --user olivia:olivia --request POST \
  --header 'Content-Type: application/json' \
  --data-binary @presentation.json \
  http://localhost:8080/case-api/v2/case-definitions/my-request/presentation-releases
```

Capture each response's `id`. Then bind exact releases:

```bash
curl --user olivia:olivia --request POST \
  --header 'Content-Type: application/json' \
  --data '{
    "orchestrationReleaseId": "ORCHESTRATION_RELEASE_ID",
    "contractReleaseId": "CONTRACT_RELEASE_ID",
    "presentationReleaseId": "PRESENTATION_RELEASE_ID"
  }' \
  http://localhost:8080/case-api/v2/case-definitions/my-request/versions
```

Binding is the point where cross-artifact references are validated. Publication stores one
artifact immutably; it cannot know which other releases you intend to combine until binding.

Every publication response also supplies a `Location` for release metadata:

```text
GET /case-api/v2/case-definitions/{key}/releases/{releaseId}
```

This administrator endpoint works for orchestration, contract, and presentation releases. It
returns the immutable release identifiers, `status`, and a safe `failureDetail` capped at 2,000
characters. The existing kind-specific contract and presentation GETs continue to download
content. A combined deployment validates all deterministic cross-artifact rules before writing;
after publication begins, each immutable release commits in its own transaction so a definitive
`FAILED` orchestration result remains available even if the later version-binding step fails.

Remote deployment reports are ordered by the case definition's business version under a shared
tenant-and-key lock. If a delayed report for version 2 arrives after version 3 is active, version 2
is recorded as superseded and cannot retire or replace version 3. The same rule applies to
tenant-scoped and tenant-less definitions.

## Release immutability and versioning

Content is hashed with SHA-256. Republishing identical content for the same tenant, definition key,
and release kind resolves to the immutable release rather than creating mutable history.

Use this versioning rule:

- BPMN/DMN change: publish a new orchestration release.
- Field/form/action/SLA contract change: publish a new contract release.
- Layout/localization/component placement change: publish a new presentation release.
- Runnable combination change: create a new case-definition version binding exact releases.

Never modify a previously published release in the database.
