openapi: 3.0.3
info:
  title: Case Management API (Camunda 7 Extension)
  version: 2.0.0
  description: |
    Case management service layer running alongside a Camunda 7 engine.
    Decentralized: every DevOps team runs its own engine + case service.

    Cross-cutting conventions:
    * **Concurrency:** mutable resources return `ETag`; mutations require `If-Match` (412 on mismatch).
    * **Idempotency:** POST /cases (and other creates) accept `Idempotency-Key`.
    * **Discoverability:** responses embed `availableActions[]` computed from state + caller permissions.
    * **Events:** every state change emits a CloudEvents 1.0 record (pull via /events, push via /webhooks).
    * **Federation:** all IDs are globally unique; every resource carries `engineId` and `tenantId`.
servers:
  - url: /case-api/v2
security:
  - oauth2: [case:read, case:write]

tags:
  - name: Case Definitions
  - name: Cases
  - name: Plan Items
  - name: Tasks
  - name: Processes
  - name: Milestones
  - name: SLA
  - name: Participants
  - name: Collaboration
  - name: Queues & Routing
  - name: Saved Filters
  - name: Bulk Operations
  - name: Events & Webhooks
  - name: History & Audit

paths:
  # ---------- Case Definitions ----------
  /case-definitions:
    get:
      tags: [Case Definitions]
      summary: List deployed case definitions (latest versions)
      parameters:
        - $ref: '#/components/parameters/tenantId'
        - $ref: '#/components/parameters/page'
        - $ref: '#/components/parameters/pageSize'
      responses:
        '200':
          description: OK
          content:
            application/json:
              schema:
                allOf:
                  - $ref: '#/components/schemas/Page'
                  - properties:
                      items:
                        type: array
                        items: {$ref: '#/components/schemas/CaseDefinition'}
                    additionalProperties: true
    post:
      tags: [Case Definitions]
      summary: Deploy a new case definition (new key or new version)
      requestBody:
        required: true
        content:
          application/json:
            schema: {$ref: '#/components/schemas/CaseDefinition'}
      responses:
        '201':
          description: Deployed
          content:
            application/json:
              schema: {$ref: '#/components/schemas/CaseDefinition'}
        '400': {$ref: '#/components/responses/BadRequest'}

  /case-definitions/{key}:
    get:
      tags: [Case Definitions]
      summary: Get latest version of a case definition
      parameters: [{$ref: '#/components/parameters/definitionKey'}]
      responses:
        '200':
          description: OK
          content:
            application/json:
              schema: {$ref: '#/components/schemas/CaseDefinition'}
        '404': {$ref: '#/components/responses/NotFound'}

  /case-definitions/{key}/versions:
    get:
      tags: [Case Definitions]
      summary: List all versions of a case definition
      parameters: [{$ref: '#/components/parameters/definitionKey'}]
      responses:
        '200':
          description: OK
          content:
            application/json:
              schema:
                type: array
                items: {$ref: '#/components/schemas/CaseDefinition'}

  /case-definitions/{key}/forms/{formKey}:
    get:
      tags: [Case Definitions]
      summary: Get JSON Schema for a task/action form (model-driven frontends)
      parameters:
        - {$ref: '#/components/parameters/definitionKey'}
        - name: formKey
          in: path
          required: true
          schema: {type: string}
      responses:
        '200':
          description: JSON Schema (draft 2020-12) describing the form
          content:
            application/schema+json:
              schema: {type: object}
        '404': {$ref: '#/components/responses/NotFound'}

  /case-definitions/{key}/identity-links:
    get:
      tags: [Case Definitions]
      summary: Who may start / administer this case type
      parameters: [{$ref: '#/components/parameters/definitionKey'}]
      responses:
        '200':
          description: OK
          content:
            application/json:
              schema:
                type: array
                items: {$ref: '#/components/schemas/IdentityLink'}
    post:
      tags: [Case Definitions]
      summary: Add identity link (candidate starter user/group)
      parameters: [{$ref: '#/components/parameters/definitionKey'}]
      requestBody:
        required: true
        content:
          application/json:
            schema: {$ref: '#/components/schemas/IdentityLink'}
      responses:
        '201': {description: Created}

  /case-definitions/{key}/identity-links/{linkId}:
    delete:
      tags: [Case Definitions]
      summary: Remove identity link
      parameters:
        - {$ref: '#/components/parameters/definitionKey'}
        - name: linkId
          in: path
          required: true
          schema: {type: string}
      responses:
        '204': {description: Deleted}

  # ---------- Cases ----------
  /cases:
    get:
      tags: [Cases]
      summary: Query cases (back-office worklists)
      parameters:
        - $ref: '#/components/parameters/tenantId'
        - name: state
          in: query
          schema:
            type: array
            items: {$ref: '#/components/schemas/CaseState'}
          style: form
          explode: false
        - name: caseDefinitionKey
          in: query
          schema: {type: string}
        - name: businessKey
          in: query
          schema: {type: string}
        - name: assignee
          in: query
          schema: {type: string}
        - name: participantUser
          in: query
          schema: {type: string}
        - name: queueId
          in: query
          schema: {type: string}
        - name: slaStatus
          in: query
          schema: {type: string, enum: [ON_TRACK, WARNING, BREACHED]}
        - name: priority
          in: query
          schema: {type: string}
        - name: freeText
          in: query
          description: Full-text search over title, business key, comments
          schema: {type: string}
        - name: createdAfter
          in: query
          schema: {type: string, format: date-time}
        - name: createdBefore
          in: query
          schema: {type: string, format: date-time}
        - name: sort
          in: query
          schema: {type: string, example: '-createdAt,priority'}
        - $ref: '#/components/parameters/page'
        - $ref: '#/components/parameters/pageSize'
      responses:
        '200':
          description: OK
          content:
            application/json:
              schema:
                allOf:
                  - $ref: '#/components/schemas/Page'
                  - properties:
                      items:
                        type: array
                        items: {$ref: '#/components/schemas/Case'}
                    additionalProperties: true
    post:
      tags: [Cases]
      summary: Create a case
      parameters: [{$ref: '#/components/parameters/idempotencyKey'}]
      requestBody:
        required: true
        content:
          application/json:
            schema: {$ref: '#/components/schemas/CaseCreateRequest'}
      responses:
        '201':
          description: Created (repeated Idempotency-Key returns the original)
          headers:
            ETag: {$ref: '#/components/headers/ETag'}
          content:
            application/json:
              schema: {$ref: '#/components/schemas/Case'}
        '400': {$ref: '#/components/responses/BadRequest'}
        '409':
          description: Idempotency-Key reused with a different payload

  /cases/{caseId}:
    parameters: [{$ref: '#/components/parameters/caseId'}]
    get:
      tags: [Cases]
      summary: Get case (includes availableActions)
      responses:
        '200':
          description: OK
          headers:
            ETag: {$ref: '#/components/headers/ETag'}
          content:
            application/json:
              schema: {$ref: '#/components/schemas/Case'}
        '404': {$ref: '#/components/responses/NotFound'}
    patch:
      tags: [Cases]
      summary: Update metadata / variables (JSON Merge Patch)
      parameters: [{$ref: '#/components/parameters/ifMatch'}]
      requestBody:
        required: true
        content:
          application/merge-patch+json:
            schema: {$ref: '#/components/schemas/CasePatch'}
      responses:
        '200':
          description: Updated
          headers:
            ETag: {$ref: '#/components/headers/ETag'}
          content:
            application/json:
              schema: {$ref: '#/components/schemas/Case'}
        '412': {$ref: '#/components/responses/PreconditionFailed'}

  /cases/{caseId}/close:
    post:
      tags: [Cases]
      summary: Close case (requires all required plan items finished)
      parameters:
        - {$ref: '#/components/parameters/caseId'}
        - {$ref: '#/components/parameters/ifMatch'}
      requestBody:
        content:
          application/json:
            schema:
              type: object
              properties:
                outcome: {type: string}
                reason: {type: string}
      responses:
        '200': {$ref: '#/components/responses/CaseResponse'}
        '409': {$ref: '#/components/responses/InvalidTransition'}
        '412': {$ref: '#/components/responses/PreconditionFailed'}

  /cases/{caseId}/cancel:
    post:
      tags: [Cases]
      summary: Cancel case (terminates active plan items and linked processes)
      parameters:
        - {$ref: '#/components/parameters/caseId'}
        - {$ref: '#/components/parameters/ifMatch'}
      requestBody:
        content:
          application/json:
            schema:
              type: object
              required: [reason]
              properties:
                reason: {type: string}
      responses:
        '200': {$ref: '#/components/responses/CaseResponse'}
        '409': {$ref: '#/components/responses/InvalidTransition'}
        '412': {$ref: '#/components/responses/PreconditionFailed'}

  /cases/{caseId}/suspend:
    post:
      tags: [Cases]
      summary: Suspend case (optionally pauses SLA clocks per policy)
      parameters:
        - {$ref: '#/components/parameters/caseId'}
        - {$ref: '#/components/parameters/ifMatch'}
      responses:
        '200': {$ref: '#/components/responses/CaseResponse'}
        '409': {$ref: '#/components/responses/InvalidTransition'}
        '412': {$ref: '#/components/responses/PreconditionFailed'}

  /cases/{caseId}/resume:
    post:
      tags: [Cases]
      summary: Resume suspended case
      parameters:
        - {$ref: '#/components/parameters/caseId'}
        - {$ref: '#/components/parameters/ifMatch'}
      responses:
        '200': {$ref: '#/components/responses/CaseResponse'}
        '409': {$ref: '#/components/responses/InvalidTransition'}
        '412': {$ref: '#/components/responses/PreconditionFailed'}

  /cases/{caseId}/reactivate:
    post:
      tags: [Cases]
      summary: >
        Reactivate a closed case, selecting which plan items become active
        again (richer than a naive 'reopen'; modeled after Flowable case
        reactivation).
      parameters:
        - {$ref: '#/components/parameters/caseId'}
        - {$ref: '#/components/parameters/ifMatch'}
      requestBody:
        content:
          application/json:
            schema:
              type: object
              properties:
                reason: {type: string}
                activatePlanItemIds:
                  type: array
                  items: {type: string}
                variables: {$ref: '#/components/schemas/Variables'}
      responses:
        '200': {$ref: '#/components/responses/CaseResponse'}
        '409': {$ref: '#/components/responses/InvalidTransition'}

  /cases/{caseId}/migrate:
    post:
      tags: [Cases]
      summary: Migrate a running case to another definition version
      parameters:
        - {$ref: '#/components/parameters/caseId'}
        - {$ref: '#/components/parameters/ifMatch'}
      requestBody:
        required: true
        content:
          application/json:
            schema:
              type: object
              required: [targetDefinitionVersion]
              properties:
                targetDefinitionVersion: {type: integer}
                planItemMappings:
                  type: array
                  items:
                    type: object
                    properties:
                      from: {type: string}
                      to: {type: string}
      responses:
        '200': {$ref: '#/components/responses/CaseResponse'}
        '409': {description: Migration plan invalid for current state}

  /cases/{caseId}/escalate:
    post:
      tags: [SLA]
      summary: Escalate a case (first-class action, emits sla/escalation event)
      parameters:
        - {$ref: '#/components/parameters/caseId'}
        - {$ref: '#/components/parameters/ifMatch'}
      requestBody:
        required: true
        content:
          application/json:
            schema:
              type: object
              required: [reason]
              properties:
                level: {type: integer, minimum: 1}
                reason: {type: string}
                escalateTo:
                  type: object
                  properties:
                    userId: {type: string}
                    groupId: {type: string}
      responses:
        '200': {$ref: '#/components/responses/CaseResponse'}

  # ---------- Plan Items ----------
  /cases/{caseId}/plan-items:
    parameters: [{$ref: '#/components/parameters/caseId'}]
    get:
      tags: [Plan Items]
      summary: List plan items (stages, tasks, milestones) with lifecycle state
      responses:
        '200':
          description: OK
          content:
            application/json:
              schema:
                type: array
                items: {$ref: '#/components/schemas/PlanItem'}
    post:
      tags: [Plan Items]
      summary: Add an ad-hoc plan item (discretionary human task) to the case
      parameters: [{$ref: '#/components/parameters/ifMatch'}]
      requestBody:
        required: true
        content:
          application/json:
            schema: {$ref: '#/components/schemas/AdHocPlanItemRequest'}
      responses:
        '201':
          description: Created
          content:
            application/json:
              schema: {$ref: '#/components/schemas/PlanItem'}

  /cases/{caseId}/plan-items/{itemId}/enable:
    post:
      tags: [Plan Items]
      summary: 'Transition AVAILABLE -> ENABLED'
      parameters:
        - {$ref: '#/components/parameters/caseId'}
        - {$ref: '#/components/parameters/planItemId'}
        - {$ref: '#/components/parameters/ifMatch'}
      responses:
        '200': {$ref: '#/components/responses/PlanItemResponse'}
        '409': {$ref: '#/components/responses/InvalidTransition'}

  /cases/{caseId}/plan-items/{itemId}/start:
    post:
      tags: [Plan Items]
      summary: 'Transition ENABLED -> ACTIVE (creates task / starts process)'
      parameters:
        - {$ref: '#/components/parameters/caseId'}
        - {$ref: '#/components/parameters/planItemId'}
        - {$ref: '#/components/parameters/ifMatch'}
      responses:
        '200': {$ref: '#/components/responses/PlanItemResponse'}
        '409': {$ref: '#/components/responses/InvalidTransition'}

  /cases/{caseId}/plan-items/{itemId}/complete:
    post:
      tags: [Plan Items]
      summary: 'Complete an ACTIVE plan item; plan model is re-evaluated'
      parameters:
        - {$ref: '#/components/parameters/caseId'}
        - {$ref: '#/components/parameters/planItemId'}
        - {$ref: '#/components/parameters/ifMatch'}
      requestBody:
        content:
          application/json:
            schema:
              type: object
              properties:
                variables: {$ref: '#/components/schemas/Variables'}
      responses:
        '200': {$ref: '#/components/responses/PlanItemResponse'}
        '409': {$ref: '#/components/responses/InvalidTransition'}

  /cases/{caseId}/plan-items/{itemId}/terminate:
    post:
      tags: [Plan Items]
      summary: Terminate a plan item (manual exit)
      parameters:
        - {$ref: '#/components/parameters/caseId'}
        - {$ref: '#/components/parameters/planItemId'}
        - {$ref: '#/components/parameters/ifMatch'}
      requestBody:
        content:
          application/json:
            schema:
              type: object
              properties:
                reason: {type: string}
      responses:
        '200': {$ref: '#/components/responses/PlanItemResponse'}
        '409': {$ref: '#/components/responses/InvalidTransition'}

  # ---------- Tasks ----------
  /tasks:
    get:
      tags: [Tasks]
      summary: Cross-case worklist (wraps Camunda task service, adds case context)
      parameters:
        - name: assignee
          in: query
          schema: {type: string}
        - name: candidateGroup
          in: query
          schema: {type: string}
        - name: unassigned
          in: query
          schema: {type: boolean}
        - name: caseDefinitionKey
          in: query
          schema: {type: string}
        - name: dueBefore
          in: query
          schema: {type: string, format: date-time}
        - $ref: '#/components/parameters/page'
        - $ref: '#/components/parameters/pageSize'
      responses:
        '200':
          description: OK
          content:
            application/json:
              schema:
                allOf:
                  - $ref: '#/components/schemas/Page'
                  - properties:
                      items:
                        type: array
                        items: {$ref: '#/components/schemas/Task'}
                    additionalProperties: true

  /cases/{caseId}/tasks:
    get:
      tags: [Tasks]
      summary: Tasks of one case
      parameters: [{$ref: '#/components/parameters/caseId'}]
      responses:
        '200':
          description: OK
          content:
            application/json:
              schema:
                type: array
                items: {$ref: '#/components/schemas/Task'}

  /tasks/{taskId}:
    parameters: [{$ref: '#/components/parameters/taskId'}]
    get:
      tags: [Tasks]
      summary: Get task (includes availableActions and formKey)
      responses:
        '200':
          description: OK
          headers:
            ETag: {$ref: '#/components/headers/ETag'}
          content:
            application/json:
              schema: {$ref: '#/components/schemas/Task'}
    patch:
      tags: [Tasks]
      summary: Update task (due date, priority, description)
      parameters: [{$ref: '#/components/parameters/ifMatch'}]
      requestBody:
        required: true
        content:
          application/merge-patch+json:
            schema:
              type: object
              properties:
                dueDate: {type: string, format: date-time}
                priority: {type: integer}
                description: {type: string}
      responses:
        '200': {$ref: '#/components/responses/TaskResponse'}
        '412': {$ref: '#/components/responses/PreconditionFailed'}

  /tasks/{taskId}/claim:
    post:
      tags: [Tasks]
      summary: Claim task for the caller
      parameters:
        - {$ref: '#/components/parameters/taskId'}
        - {$ref: '#/components/parameters/ifMatch'}
      responses:
        '200': {$ref: '#/components/responses/TaskResponse'}
        '409': {description: Already claimed}

  /tasks/{taskId}/unclaim:
    post:
      tags: [Tasks]
      summary: Release task back to its queue / candidate groups
      parameters:
        - {$ref: '#/components/parameters/taskId'}
        - {$ref: '#/components/parameters/ifMatch'}
      responses:
        '200': {$ref: '#/components/responses/TaskResponse'}

  /tasks/{taskId}/delegate:
    post:
      tags: [Tasks]
      summary: Delegate task to another user
      parameters:
        - {$ref: '#/components/parameters/taskId'}
        - {$ref: '#/components/parameters/ifMatch'}
      requestBody:
        required: true
        content:
          application/json:
            schema:
              type: object
              required: [userId]
              properties:
                userId: {type: string}
      responses:
        '200': {$ref: '#/components/responses/TaskResponse'}

  /tasks/{taskId}/complete:
    post:
      tags: [Tasks]
      summary: Complete task with form payload (validated against form schema)
      parameters:
        - {$ref: '#/components/parameters/taskId'}
        - {$ref: '#/components/parameters/ifMatch'}
      requestBody:
        content:
          application/json:
            schema:
              type: object
              properties:
                variables: {$ref: '#/components/schemas/Variables'}
                outcome: {type: string}
      responses:
        '200': {$ref: '#/components/responses/TaskResponse'}
        '400': {description: Payload fails form schema validation}
        '412': {$ref: '#/components/responses/PreconditionFailed'}

  # ---------- Processes ----------
  /cases/{caseId}/processes:
    parameters: [{$ref: '#/components/parameters/caseId'}]
    get:
      tags: [Processes]
      summary: BPMN process instances linked to the case
      responses:
        '200':
          description: OK
          content:
            application/json:
              schema:
                type: array
                items: {$ref: '#/components/schemas/LinkedProcess'}
    post:
      tags: [Processes]
      summary: Start a BPMN process correlated to this case
      parameters: [{$ref: '#/components/parameters/ifMatch'}]
      requestBody:
        required: true
        content:
          application/json:
            schema:
              type: object
              required: [processDefinitionKey]
              properties:
                processDefinitionKey: {type: string}
                variables: {$ref: '#/components/schemas/Variables'}
                planItemId:
                  type: string
                  description: Optional plan item this process fulfils
      responses:
        '201':
          description: Started
          content:
            application/json:
              schema: {$ref: '#/components/schemas/LinkedProcess'}

  # ---------- Milestones ----------
  /cases/{caseId}/milestones:
    get:
      tags: [Milestones]
      summary: Milestones with achieved state
      parameters: [{$ref: '#/components/parameters/caseId'}]
      responses:
        '200':
          description: OK
          content:
            application/json:
              schema:
                type: array
                items: {$ref: '#/components/schemas/Milestone'}

  /cases/{caseId}/milestones/{milestoneId}/achieve:
    post:
      tags: [Milestones]
      summary: Manually achieve a milestone (emits milestone.achieved event)
      parameters:
        - {$ref: '#/components/parameters/caseId'}
        - name: milestoneId
          in: path
          required: true
          schema: {type: string}
        - {$ref: '#/components/parameters/ifMatch'}
      responses:
        '200':
          description: Achieved
          content:
            application/json:
              schema: {$ref: '#/components/schemas/Milestone'}
        '409': {description: Already achieved}

  # ---------- SLA ----------
  /sla-policies:
    get:
      tags: [SLA]
      summary: SLA policies available to this engine/tenant
      parameters: [{$ref: '#/components/parameters/tenantId'}]
      responses:
        '200':
          description: OK
          content:
            application/json:
              schema:
                type: array
                items: {$ref: '#/components/schemas/SlaPolicy'}

  /cases/{caseId}/slas:
    get:
      tags: [SLA]
      summary: Active SLA records of the case (targets, clocks, breach status)
      parameters: [{$ref: '#/components/parameters/caseId'}]
      responses:
        '200':
          description: OK
          content:
            application/json:
              schema:
                type: array
                items: {$ref: '#/components/schemas/SlaRecord'}

  /cases/{caseId}/slas/{slaId}/pause:
    post:
      tags: [SLA]
      summary: Pause SLA clock (e.g. waiting on customer)
      parameters:
        - {$ref: '#/components/parameters/caseId'}
        - {$ref: '#/components/parameters/slaId'}
      requestBody:
        required: true
        content:
          application/json:
            schema:
              type: object
              required: [reason]
              properties:
                reason: {type: string}
      responses:
        '200':
          description: Paused
          content:
            application/json:
              schema: {$ref: '#/components/schemas/SlaRecord'}
        '409': {description: Already paused or completed}

  /cases/{caseId}/slas/{slaId}/resume:
    post:
      tags: [SLA]
      summary: Resume SLA clock
      parameters:
        - {$ref: '#/components/parameters/caseId'}
        - {$ref: '#/components/parameters/slaId'}
      responses:
        '200':
          description: Resumed
          content:
            application/json:
              schema: {$ref: '#/components/schemas/SlaRecord'}
        '409': {description: Not paused}

  # ---------- Participants ----------
  /cases/{caseId}/participants:
    parameters: [{$ref: '#/components/parameters/caseId'}]
    get:
      tags: [Participants]
      summary: List participants and roles
      responses:
        '200':
          description: OK
          content:
            application/json:
              schema:
                type: array
                items: {$ref: '#/components/schemas/Participant'}
    post:
      tags: [Participants]
      summary: Add participant (owner, handler, reviewer, watcher, ...)
      requestBody:
        required: true
        content:
          application/json:
            schema: {$ref: '#/components/schemas/Participant'}
      responses:
        '201': {description: Added}

  /cases/{caseId}/participants/{participantId}:
    delete:
      tags: [Participants]
      summary: Remove participant
      parameters:
        - {$ref: '#/components/parameters/caseId'}
        - name: participantId
          in: path
          required: true
          schema: {type: string}
      responses:
        '204': {description: Removed}

  # ---------- Collaboration ----------
  /cases/{caseId}/comments:
    parameters: [{$ref: '#/components/parameters/caseId'}]
    get:
      tags: [Collaboration]
      summary: List comments (filter by visibility)
      parameters:
        - name: visibility
          in: query
          schema: {type: string, enum: [internal, external]}
      responses:
        '200':
          description: OK
          content:
            application/json:
              schema:
                type: array
                items: {$ref: '#/components/schemas/Comment'}
    post:
      tags: [Collaboration]
      summary: Add comment (internal note or customer-facing)
      requestBody:
        required: true
        content:
          application/json:
            schema: {$ref: '#/components/schemas/Comment'}
      responses:
        '201':
          description: Created
          content:
            application/json:
              schema: {$ref: '#/components/schemas/Comment'}

  /cases/{caseId}/documents:
    parameters: [{$ref: '#/components/parameters/caseId'}]
    get:
      tags: [Collaboration]
      summary: List document metadata (content lives in DMS/S3)
      responses:
        '200':
          description: OK
          content:
            application/json:
              schema:
                type: array
                items: {$ref: '#/components/schemas/Document'}
    post:
      tags: [Collaboration]
      summary: Register a document (metadata + external content reference)
      requestBody:
        required: true
        content:
          application/json:
            schema: {$ref: '#/components/schemas/Document'}
      responses:
        '201':
          description: Created
          content:
            application/json:
              schema: {$ref: '#/components/schemas/Document'}

  /cases/{caseId}/documents/{documentId}:
    delete:
      tags: [Collaboration]
      summary: Remove document reference
      parameters:
        - {$ref: '#/components/parameters/caseId'}
        - name: documentId
          in: path
          required: true
          schema: {type: string}
      responses:
        '204': {description: Deleted}

  /cases/{caseId}/attachment-categories:
    get:
      tags: [Collaboration]
      summary: Allowed attachment categories for this case type
      parameters: [{$ref: '#/components/parameters/caseId'}]
      responses:
        '200':
          description: OK
          content:
            application/json:
              schema:
                type: array
                items:
                  type: object
                  properties:
                    id: {type: string}
                    name: {type: string}

  /cases/{caseId}/links:
    parameters: [{$ref: '#/components/parameters/caseId'}]
    get:
      tags: [Collaboration]
      summary: Case-to-case relations (may reference cases on other engines)
      responses:
        '200':
          description: OK
          content:
            application/json:
              schema:
                type: array
                items: {$ref: '#/components/schemas/CaseLink'}
    post:
      tags: [Collaboration]
      summary: Create case link
      requestBody:
        required: true
        content:
          application/json:
            schema: {$ref: '#/components/schemas/CaseLink'}
      responses:
        '201': {description: Created}

  /cases/{caseId}/links/{linkId}:
    delete:
      tags: [Collaboration]
      summary: Remove case link
      parameters:
        - {$ref: '#/components/parameters/caseId'}
        - name: linkId
          in: path
          required: true
          schema: {type: string}
      responses:
        '204': {description: Deleted}

  # ---------- Queues & Routing ----------
  /queues:
    get:
      tags: [Queues & Routing]
      summary: List work queues
      parameters: [{$ref: '#/components/parameters/tenantId'}]
      responses:
        '200':
          description: OK
          content:
            application/json:
              schema:
                type: array
                items: {$ref: '#/components/schemas/Queue'}

  /queues/{queueId}/items:
    get:
      tags: [Queues & Routing]
      summary: Cases/tasks currently in a queue (unclaimed work)
      parameters:
        - name: queueId
          in: path
          required: true
          schema: {type: string}
        - $ref: '#/components/parameters/page'
        - $ref: '#/components/parameters/pageSize'
      responses:
        '200':
          description: OK
          content:
            application/json:
              schema:
                allOf:
                  - $ref: '#/components/schemas/Page'
                  - properties:
                      items:
                        type: array
                        items: {$ref: '#/components/schemas/QueueItem'}
                    additionalProperties: true

  /cases/{caseId}/assign:
    post:
      tags: [Queues & Routing]
      summary: Assign case (explicit target or rule-based auto-assignment)
      parameters:
        - {$ref: '#/components/parameters/caseId'}
        - {$ref: '#/components/parameters/ifMatch'}
      requestBody:
        required: true
        content:
          application/json:
            schema:
              type: object
              properties:
                userId: {type: string}
                queueId: {type: string}
                strategy:
                  type: string
                  enum: [EXPLICIT, ROUND_ROBIN, LEAST_LOADED, RULE]
                  default: EXPLICIT
      responses:
        '200': {$ref: '#/components/responses/CaseResponse'}

  # ---------- Saved Filters ----------
  /saved-filters:
    get:
      tags: [Saved Filters]
      summary: List saved filters (own + shared)
      responses:
        '200':
          description: OK
          content:
            application/json:
              schema:
                type: array
                items: {$ref: '#/components/schemas/SavedFilter'}
    post:
      tags: [Saved Filters]
      summary: Create saved filter
      requestBody:
        required: true
        content:
          application/json:
            schema: {$ref: '#/components/schemas/SavedFilter'}
      responses:
        '201':
          description: Created
          content:
            application/json:
              schema: {$ref: '#/components/schemas/SavedFilter'}

  /saved-filters/{filterId}:
    parameters: [{$ref: '#/components/parameters/filterId'}]
    patch:
      tags: [Saved Filters]
      summary: Update saved filter
      requestBody:
        required: true
        content:
          application/merge-patch+json:
            schema: {$ref: '#/components/schemas/SavedFilter'}
      responses:
        '200': {description: Updated}
    delete:
      tags: [Saved Filters]
      summary: Delete saved filter
      responses:
        '204': {description: Deleted}

  /saved-filters/{filterId}/results:
    get:
      tags: [Saved Filters]
      summary: Execute saved filter (JSON or CSV export)
      parameters:
        - {$ref: '#/components/parameters/filterId'}
        - name: format
          in: query
          schema: {type: string, enum: [json, csv], default: json}
        - $ref: '#/components/parameters/page'
        - $ref: '#/components/parameters/pageSize'
      responses:
        '200':
          description: OK
          content:
            application/json:
              schema:
                allOf:
                  - $ref: '#/components/schemas/Page'
                  - properties:
                      items:
                        type: array
                        items: {$ref: '#/components/schemas/Case'}
                    additionalProperties: true
            text/csv:
              schema: {type: string}

  # ---------- Bulk Operations ----------
  /cases/bulk:
    post:
      tags: [Bulk Operations]
      summary: >
        Async bulk operation on cases selected by ID list or saved filter
        (e.g. reassign 200 cases when someone leaves). Returns 202 + operation
        resource.
      parameters: [{$ref: '#/components/parameters/idempotencyKey'}]
      requestBody:
        required: true
        content:
          application/json:
            schema: {$ref: '#/components/schemas/BulkRequest'}
      responses:
        '202':
          description: Accepted
          content:
            application/json:
              schema: {$ref: '#/components/schemas/Operation'}

  /operations/{operationId}:
    get:
      tags: [Bulk Operations]
      summary: Bulk operation status (progress, per-item errors)
      parameters: [{$ref: '#/components/parameters/operationId'}]
      responses:
        '200':
          description: OK
          content:
            application/json:
              schema: {$ref: '#/components/schemas/Operation'}

  /operations/{operationId}/cancel:
    post:
      tags: [Bulk Operations]
      summary: Cancel a running bulk operation
      parameters: [{$ref: '#/components/parameters/operationId'}]
      responses:
        '200':
          description: Cancelling / cancelled
          content:
            application/json:
              schema: {$ref: '#/components/schemas/Operation'}

  # ---------- Events & Webhooks ----------
  /events:
    get:
      tags: [Events & Webhooks]
      summary: Engine-wide event log (CloudEvents), cursor-based for consumers
      parameters:
        - name: type
          in: query
          description: Event type prefix filter, e.g. com.example.case.sla
          schema: {type: string}
        - name: after
          in: query
          description: Cursor (event id or timestamp) to resume from
          schema: {type: string}
        - $ref: '#/components/parameters/pageSize'
      responses:
        '200':
          description: OK
          content:
            application/json:
              schema:
                type: object
                properties:
                  items:
                    type: array
                    items: {$ref: '#/components/schemas/CloudEvent'}
                  nextCursor: {type: string}

  /cases/{caseId}/events:
    get:
      tags: [Events & Webhooks]
      summary: Event log of one case
      parameters: [{$ref: '#/components/parameters/caseId'}]
      responses:
        '200':
          description: OK
          content:
            application/json:
              schema:
                type: array
                items: {$ref: '#/components/schemas/CloudEvent'}

  /webhooks:
    get:
      tags: [Events & Webhooks]
      summary: List webhook subscriptions
      responses:
        '200':
          description: OK
          content:
            application/json:
              schema:
                type: array
                items: {$ref: '#/components/schemas/WebhookSubscription'}
    post:
      tags: [Events & Webhooks]
      summary: >
        Subscribe an endpoint. Delivery is at-least-once, HMAC-signed
        (X-Case-Signature), retried with exponential backoff, then
        dead-lettered.
      requestBody:
        required: true
        content:
          application/json:
            schema: {$ref: '#/components/schemas/WebhookSubscription'}
      responses:
        '201':
          description: Created (secret returned once)
          content:
            application/json:
              schema: {$ref: '#/components/schemas/WebhookSubscription'}

  /webhooks/{webhookId}:
    parameters: [{$ref: '#/components/parameters/webhookId'}]
    patch:
      tags: [Events & Webhooks]
      summary: Update subscription (url, event filter, active)
      requestBody:
        required: true
        content:
          application/merge-patch+json:
            schema: {$ref: '#/components/schemas/WebhookSubscription'}
      responses:
        '200': {description: Updated}
    delete:
      tags: [Events & Webhooks]
      summary: Delete subscription
      responses:
        '204': {description: Deleted}

  /webhooks/{webhookId}/test:
    post:
      tags: [Events & Webhooks]
      summary: Send a test event to the subscriber endpoint
      parameters: [{$ref: '#/components/parameters/webhookId'}]
      responses:
        '200':
          description: Delivery attempt result
          content:
            application/json:
              schema:
                type: object
                properties:
                  delivered: {type: boolean}
                  statusCode: {type: integer}
                  latencyMs: {type: integer}

  /webhooks/{webhookId}/dead-letters:
    get:
      tags: [Events & Webhooks]
      summary: Undeliverable events for this subscription
      parameters: [{$ref: '#/components/parameters/webhookId'}]
      responses:
        '200':
          description: OK
          content:
            application/json:
              schema:
                type: array
                items:
                  type: object
                  properties:
                    event: {$ref: '#/components/schemas/CloudEvent'}
                    attempts: {type: integer}
                    lastError: {type: string}
                    failedAt: {type: string, format: date-time}

  /webhooks/{webhookId}/dead-letters/redeliver:
    post:
      tags: [Events & Webhooks]
      summary: Re-deliver dead-lettered events
      parameters: [{$ref: '#/components/parameters/webhookId'}]
      responses:
        '202': {description: Redelivery scheduled}

  # ---------- History & Audit ----------
  /cases/{caseId}/audit-log:
    get:
      tags: [History & Audit]
      summary: Full audit trail (who/when/what for every state change)
      parameters:
        - {$ref: '#/components/parameters/caseId'}
        - $ref: '#/components/parameters/page'
        - $ref: '#/components/parameters/pageSize'
      responses:
        '200':
          description: OK
          content:
            application/json:
              schema:
                allOf:
                  - $ref: '#/components/schemas/Page'
                  - properties:
                      items:
                        type: array
                        items: {$ref: '#/components/schemas/AuditEntry'}
                    additionalProperties: true

  /case-history:
    get:
      tags: [History & Audit]
      summary: Query closed/cancelled cases for reporting
      parameters:
        - $ref: '#/components/parameters/tenantId'
        - name: caseDefinitionKey
          in: query
          schema: {type: string}
        - name: closedAfter
          in: query
          schema: {type: string, format: date-time}
        - name: closedBefore
          in: query
          schema: {type: string, format: date-time}
        - $ref: '#/components/parameters/page'
        - $ref: '#/components/parameters/pageSize'
      responses:
        '200':
          description: OK
          content:
            application/json:
              schema:
                allOf:
                  - $ref: '#/components/schemas/Page'
                  - properties:
                      items:
                        type: array
                        items: {$ref: '#/components/schemas/Case'}
                    additionalProperties: true

components:
  securitySchemes:
    oauth2:
      type: oauth2
      flows:
        clientCredentials:
          tokenUrl: https://idp.example.com/token
          scopes:
            case:read: Read cases
            case:write: Modify cases
            case:admin: Deploy definitions, manage webhooks

  parameters:
    caseId:
      name: caseId
      in: path
      required: true
      description: Globally unique case id ({engineId}:{uuid})
      schema: {type: string}
    taskId:
      name: taskId
      in: path
      required: true
      schema: {type: string}
    planItemId:
      name: itemId
      in: path
      required: true
      schema: {type: string}
    slaId:
      name: slaId
      in: path
      required: true
      schema: {type: string}
    filterId:
      name: filterId
      in: path
      required: true
      schema: {type: string}
    webhookId:
      name: webhookId
      in: path
      required: true
      schema: {type: string}
    operationId:
      name: operationId
      in: path
      required: true
      schema: {type: string}
    definitionKey:
      name: key
      in: path
      required: true
      schema: {type: string}
    tenantId:
      name: tenantId
      in: query
      schema: {type: string}
    page:
      name: page
      in: query
      schema: {type: integer, minimum: 0, default: 0}
    pageSize:
      name: pageSize
      in: query
      schema: {type: integer, minimum: 1, maximum: 200, default: 25}
    ifMatch:
      name: If-Match
      in: header
      required: true
      description: ETag from a previous read; 412 if stale (optimistic locking)
      schema: {type: string}
    idempotencyKey:
      name: Idempotency-Key
      in: header
      required: false
      description: Client-generated key; retries return the original response
      schema: {type: string, maxLength: 128}

  headers:
    ETag:
      description: Current entity version for optimistic concurrency
      schema: {type: string}

  responses:
    BadRequest:
      description: Validation error
      content:
        application/problem+json:
          schema: {$ref: '#/components/schemas/Problem'}
    NotFound:
      description: Resource not found
      content:
        application/problem+json:
          schema: {$ref: '#/components/schemas/Problem'}
    PreconditionFailed:
      description: ETag mismatch — resource was modified concurrently
      content:
        application/problem+json:
          schema: {$ref: '#/components/schemas/Problem'}
    InvalidTransition:
      description: Action not allowed in current lifecycle state
      content:
        application/problem+json:
          schema: {$ref: '#/components/schemas/Problem'}
    CaseResponse:
      description: Updated case
      headers:
        ETag: {$ref: '#/components/headers/ETag'}
      content:
        application/json:
          schema: {$ref: '#/components/schemas/Case'}
    PlanItemResponse:
      description: Updated plan item (plan model re-evaluated)
      content:
        application/json:
          schema: {$ref: '#/components/schemas/PlanItem'}
    TaskResponse:
      description: Updated task
      headers:
        ETag: {$ref: '#/components/headers/ETag'}
      content:
        application/json:
          schema: {$ref: '#/components/schemas/Task'}

  schemas:
    Problem:
      type: object
      description: RFC 7807 problem details
      properties:
        type: {type: string, format: uri}
        title: {type: string}
        status: {type: integer}
        detail: {type: string}
        instance: {type: string}

    Page:
      type: object
      # additionalProperties is deliberate, and it is a workaround, not a preference. Every
      # listing composes this envelope with `allOf: [Page, {properties: {items: [...]}}]`, and a
      # validator that treats an undeclared property as an error (the common default, including
      # swagger-request-validator, which OpenApiConformanceIT uses) then finds NO satisfiable
      # instance: subschema 0 rejects `items`, subschema 1 rejects `page`/`pageSize`. `allOf` and
      # implicit additionalProperties:false are mutually hostile in OpenAPI 3.0 and this idiom is
      # unvalidatable without one of them giving way. Found by implementation in Task 27.
      # A production spec should declare a concrete per-collection page component
      # (CasePage, TaskPage, ...) instead; that keeps strictness AND typing. Not done here
      # because it multiplies seven usages into seven components for a PoC.
      additionalProperties: true
      properties:
        page: {type: integer}
        pageSize: {type: integer}
        totalItems: {type: integer}
        totalPages: {type: integer}

    Variables:
      type: object
      additionalProperties: true
      description: Case/task variables as a JSON object

    AvailableAction:
      type: object
      description: >
        Server-computed action the caller may perform now (HATEOAS-lite).
        Frontends render these instead of re-implementing the state machine.
      properties:
        # Renamed from `id` to `action` in Task 27 to match the implementation and every consumer
        # written against it. This is the single most load-bearing field name in the whole
        # document — it is what a generic client switches on — and a client written from the old
        # spec would have found no `id` on any response.
        action: {type: string, example: close}
        # NOT IMPLEMENTED: nothing emits a human-readable label. Kept declared because a renderer
        # needs one; recorded as an R3 gap in FINDINGS.md rather than quietly deleted.
        name: {type: string, example: Close case}
        method: {type: string, example: POST}
        href: {type: string, example: /cases/eng-a:123/close}
        formKey:
          type: string
          nullable: true
          description: Optional form schema to render before invoking

    CaseState:
      type: string
      enum: [CREATED, ACTIVE, SUSPENDED, CLOSED, CANCELLED]

    Case:
      type: object
      properties:
        id: {type: string, description: 'Globally unique: {engineId}:{uuid}'}
        engineId: {type: string}
        tenantId: {type: string}
        caseDefinitionKey: {type: string}
        caseDefinitionVersion: {type: integer}
        businessKey: {type: string, nullable: true}
        title: {type: string}
        state: {$ref: '#/components/schemas/CaseState'}
        priority: {type: string, enum: [LOW, MEDIUM, HIGH, CRITICAL]}
        assignee: {type: string, nullable: true}
        queueId: {type: string, nullable: true}
        initiator: {type: string, nullable: true}
        slaStatus: {type: string, enum: [ON_TRACK, WARNING, BREACHED, NONE]}
        # Added in Task 27: both are emitted on every case response and were undeclared.
        # `version` is not decoration — it is the value of the ETag, so a client written from this
        # document could not have found the number it needs for the next If-Match.
        outcome: {type: string, nullable: true}
        version: {type: integer, format: int64}
        variables: {$ref: '#/components/schemas/Variables'}
        createdAt: {type: string, format: date-time}
        # NOT IMPLEMENTED: CaseResponse carries createdAt and closedAt but no updatedAt, though
        # CM_CASE.UPDATED_AT_ exists and is maintained. Recorded in FINDINGS.md.
        updatedAt: {type: string, format: date-time}
        closedAt: {type: string, format: date-time, nullable: true}
        availableActions:
          type: array
          items: {$ref: '#/components/schemas/AvailableAction'}

    CaseCreateRequest:
      type: object
      required: [caseDefinitionKey]
      properties:
        caseDefinitionKey: {type: string}
        caseDefinitionVersion:
          type: integer
          description: Omit for latest
        businessKey: {type: string}
        title: {type: string}
        priority: {type: string}
        tenantId: {type: string}
        variables: {$ref: '#/components/schemas/Variables'}

    CasePatch:
      type: object
      properties:
        title: {type: string}
        priority: {type: string}
        businessKey: {type: string}
        variables: {$ref: '#/components/schemas/Variables'}

    CaseDefinition:
      type: object
      required: [key, name]
      properties:
        key: {type: string}
        name: {type: string}
        version: {type: integer, readOnly: true}
        tenantId: {type: string}
        description: {type: string}
        slaPolicyId: {type: string}
        roles:
          type: array
          items: {type: string}
          example: [owner, handler, reviewer, watcher]
        attachmentCategories:
          type: array
          items: {type: string}
        planModel:
          type: array
          items: {$ref: '#/components/schemas/PlanItemDefinition'}
        forms:
          type: object
          additionalProperties:
            type: object
            description: JSON Schema per form key
        routingRules:
          type: array
          items:
            type: object
            properties:
              condition: {type: string, description: FEEL/JUEL expression}
              queueId: {type: string}
        deployedAt: {type: string, format: date-time, readOnly: true}

    PlanItemDefinition:
      type: object
      required: [id, type, name]
      properties:
        id: {type: string}
        type:
          type: string
          enum: [STAGE, HUMAN_TASK, PROCESS_TASK, MILESTONE]
        name: {type: string}
        parentStageId: {type: string, nullable: true}
        manualActivation: {type: boolean, default: true}
        required: {type: boolean, default: false}
        repetition: {type: boolean, default: false}
        entryCriteria:
          type: array
          items: {type: string}
          description: Expressions over variables and sibling plan-item states
        exitCriteria:
          type: array
          items: {type: string}
        formKey: {type: string}
        processDefinitionKey:
          type: string
          description: For PROCESS_TASK, the BPMN process to start
        candidateGroups:
          type: array
          items: {type: string}

    PlanItem:
      type: object
      properties:
        id: {type: string}
        definitionId: {type: string}
        type: {type: string, enum: [STAGE, HUMAN_TASK, PROCESS_TASK, MILESTONE]}
        name: {type: string}
        state:
          type: string
          enum: [AVAILABLE, ENABLED, ACTIVE, COMPLETED, TERMINATED]
        parentStageId: {type: string, nullable: true}
        adHoc: {type: boolean}
        taskId: {type: string, nullable: true}
        processInstanceId: {type: string, nullable: true}
        availableActions:
          type: array
          items: {$ref: '#/components/schemas/AvailableAction'}

    AdHocPlanItemRequest:
      type: object
      required: [name]
      properties:
        name: {type: string}
        parentStageId: {type: string}
        assignee: {type: string}
        candidateGroups:
          type: array
          items: {type: string}
        dueDate: {type: string, format: date-time}
        formKey: {type: string}

    Task:
      type: object
      properties:
        id: {type: string}
        caseId: {type: string}
        caseTitle: {type: string}
        planItemId: {type: string}
        camundaTaskId: {type: string}
        name: {type: string}
        description: {type: string}
        assignee: {type: string, nullable: true}
        candidateGroups:
          type: array
          items: {type: string}
        formKey: {type: string, nullable: true}
        priority: {type: integer}
        dueDate: {type: string, format: date-time, nullable: true}
        createdAt: {type: string, format: date-time}
        # Added in Task 27: all three are emitted on every task response and were undeclared.
        # `state` and `version` are ordinary omissions (version is the task's ETag value).
        # `engineSync` is a PoC-only addition (deviation D3): in remote mode a task exists in
        # CM_TASK before it exists on the engine, and this field is what tells a client the
        # difference — availableActions is empty until it reads SYNCED.
        state: {type: string, enum: [OPEN, CLAIMED, COMPLETED, TERMINATED]}
        engineSync: {type: string, enum: [PENDING, SYNCED, FAILED]}
        version: {type: integer, format: int64}
        availableActions:
          type: array
          items: {$ref: '#/components/schemas/AvailableAction'}

    LinkedProcess:
      type: object
      properties:
        processInstanceId: {type: string}
        processDefinitionKey: {type: string}
        planItemId: {type: string, nullable: true}
        state: {type: string, enum: [ACTIVE, COMPLETED, TERMINATED, SUSPENDED]}
        startedAt: {type: string, format: date-time}
        endedAt: {type: string, format: date-time, nullable: true}

    Milestone:
      type: object
      properties:
        id: {type: string}
        name: {type: string}
        achieved: {type: boolean}
        achievedAt: {type: string, format: date-time, nullable: true}
        achievedBy: {type: string, nullable: true}

    SlaPolicy:
      type: object
      properties:
        id: {type: string}
        name: {type: string}
        selector:
          type: string
          description: Expression selecting this policy (priority, tier, ...)
        businessCalendarId: {type: string}
        targets:
          type: array
          items:
            type: object
            properties:
              id: {type: string, example: firstResponse}
              name: {type: string}
              duration: {type: string, example: PT8H, description: ISO 8601}
              warningAt:
                type: string
                example: PT6H
                description: Emit sla.warning when elapsed time passes this
              pausedInStates:
                type: array
                items: {type: string}
                example: [WAITING_ON_CUSTOMER, SUSPENDED]
              breachActions:
                type: array
                items:
                  type: string
                  enum: [EMIT_EVENT, ESCALATE, REASSIGN, NOTIFY]

    SlaRecord:
      type: object
      properties:
        id: {type: string}
        targetId: {type: string}
        status: {type: string, enum: [RUNNING, PAUSED, MET, BREACHED]}
        dueAt: {type: string, format: date-time}
        warningAt: {type: string, format: date-time}
        pausedAt: {type: string, format: date-time, nullable: true}
        pausedReason: {type: string, nullable: true}
        elapsedBusinessTime: {type: string, description: ISO 8601 duration}

    Participant:
      type: object
      required: [role]
      properties:
        id: {type: string, readOnly: true}
        userId: {type: string}
        groupId: {type: string}
        role: {type: string, example: handler}
        addedAt: {type: string, format: date-time, readOnly: true}

    IdentityLink:
      type: object
      properties:
        id: {type: string, readOnly: true}
        userId: {type: string}
        groupId: {type: string}
        type: {type: string, enum: [candidateStarter, administrator]}

    Comment:
      type: object
      required: [text]
      properties:
        id: {type: string, readOnly: true}
        author: {type: string, readOnly: true}
        text: {type: string}
        visibility:
          type: string
          enum: [internal, external]
          default: internal
          description: internal = worker note, external = customer-facing
        createdAt: {type: string, format: date-time, readOnly: true}

    Document:
      type: object
      required: [name, contentUrl]
      properties:
        id: {type: string, readOnly: true}
        name: {type: string}
        category: {type: string}
        mimeType: {type: string}
        sizeBytes: {type: integer}
        contentUrl:
          type: string
          format: uri
          description: External storage reference (DMS/S3), not engine DB
        uploadedBy: {type: string, readOnly: true}
        uploadedAt: {type: string, format: date-time, readOnly: true}

    CaseLink:
      type: object
      required: [targetCaseId, type]
      properties:
        id: {type: string, readOnly: true}
        targetCaseId:
          type: string
          description: May reference a case on another engine (globally unique)
        type:
          type: string
          enum: [parentOf, childOf, duplicateOf, relatedTo, blockedBy]

    Queue:
      type: object
      properties:
        id: {type: string}
        name: {type: string}
        candidateGroups:
          type: array
          items: {type: string}
        itemCount: {type: integer, readOnly: true}
        oldestItemAge: {type: string, description: ISO 8601 duration}

    QueueItem:
      type: object
      properties:
        caseId: {type: string}
        taskId: {type: string, nullable: true}
        title: {type: string}
        priority: {type: string}
        slaStatus: {type: string}
        enqueuedAt: {type: string, format: date-time}

    SavedFilter:
      type: object
      required: [name, criteria]
      properties:
        id: {type: string, readOnly: true}
        name: {type: string}
        owner: {type: string, readOnly: true}
        shared: {type: boolean, default: false}
        criteria:
          type: object
          additionalProperties: true
          description: Same query parameters as GET /cases
        sort: {type: string}

    BulkRequest:
      type: object
      required: [action]
      properties:
        caseIds:
          type: array
          items: {type: string}
        filterId:
          type: string
          description: Alternative selection via saved filter
        action:
          type: string
          enum: [ASSIGN, CLOSE, CANCEL, SUSPEND, RESUME, SET_PRIORITY, ADD_PARTICIPANT]
        parameters:
          type: object
          additionalProperties: true

    Operation:
      type: object
      properties:
        id: {type: string}
        status:
          type: string
          enum: [PENDING, RUNNING, COMPLETED, COMPLETED_WITH_ERRORS, CANCELLED]
        totalItems: {type: integer}
        processedItems: {type: integer}
        errors:
          type: array
          items:
            type: object
            properties:
              caseId: {type: string}
              error: {type: string}
        startedAt: {type: string, format: date-time}
        finishedAt: {type: string, format: date-time, nullable: true}

    CloudEvent:
      type: object
      description: CloudEvents 1.0 envelope
      required: [id, source, type, specversion]
      properties:
        specversion: {type: string, example: '1.0'}
        id: {type: string, description: Consumers deduplicate on this}
        source: {type: string, example: '/engines/eng-a/cases'}
        type: {type: string, example: com.example.case.planitem.transitioned}
        subject: {type: string, example: 'eng-a:3f2c...'}
        time: {type: string, format: date-time}
        datacontenttype: {type: string, example: application/json}
        data:
          type: object
          additionalProperties: true

    WebhookSubscription:
      type: object
      required: [url, eventTypes]
      properties:
        id: {type: string, readOnly: true}
        url: {type: string, format: uri}
        eventTypes:
          type: array
          items: {type: string}
          example: [com.example.case.sla.*, com.example.case.closed]
        active: {type: boolean, default: true}
        secret:
          type: string
          writeOnly: true
          description: HMAC secret; deliveries signed via X-Case-Signature
        maxRetries: {type: integer, default: 8}
        deadLetteredCount: {type: integer, readOnly: true}
        createdAt: {type: string, format: date-time, readOnly: true}

    AuditEntry:
      type: object
      properties:
        id: {type: string}
        timestamp: {type: string, format: date-time}
        actor: {type: string}
        action: {type: string, example: planitem.start}
        resourceType: {type: string}
        resourceId: {type: string}
        before:
          type: object
          additionalProperties: true
        after:
          type: object
          additionalProperties: true
