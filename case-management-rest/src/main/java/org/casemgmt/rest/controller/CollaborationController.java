package org.casemgmt.rest.controller;

import org.casemgmt.domain.CaseInstance;
import org.casemgmt.error.CaseConflictException;
import org.casemgmt.error.NotFoundException;
import org.casemgmt.error.OptimisticLockException;
import org.casemgmt.permissions.PermissionActions;
import org.casemgmt.permissions.PermissionDecision;
import org.casemgmt.permissions.ResourceTypes;
import org.casemgmt.permissions.WorkerPermissionEvaluator;
import org.casemgmt.permissions.WorkerPermissionResource;
import org.casemgmt.repo.CommentRepository;
import org.casemgmt.repo.DocumentRepository;
import org.casemgmt.repo.LinkedProcessRepository;
import org.casemgmt.repo.MilestoneRepository;
import org.casemgmt.repo.PlanItemRepository;
import org.casemgmt.rest.CallerResolver;
import org.casemgmt.rest.dto.Dtos.CommentRequest;
import org.casemgmt.rest.dto.Dtos.DocumentRequest;
import org.casemgmt.rest.dto.Dtos.StartProcessRequest;
import org.casemgmt.rest.error.InvalidRequestException;
import org.casemgmt.rest.filter.ETagSupport;
import org.casemgmt.rest.policy.ActionPolicy;
import org.casemgmt.rest.policy.AvailableAction;
import org.casemgmt.rules.CaseSnapshot;
import org.casemgmt.service.Actor;
import org.casemgmt.service.CaseService;
import org.casemgmt.service.CommentService;
import org.casemgmt.service.DocumentService;
import org.casemgmt.service.LinkedProcessService;
import org.casemgmt.service.MilestoneService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;

/**
 * Comments, document references, milestones and linked processes for one case.
 *
 * <p><b>Authorization (fix round 1, Critical 1).</b> The first cut of this class was
 * authenticated but had no rule at all: the caller the case-level policy refuses a title edit to
 * could still comment on the case, achieve its milestones and start a BPMN process against it.
 * Collaboration writes now assert against {@code ActionPolicy}'s own collaboration and milestone
 * rules, and every method — read or write — first resolves the case through {@link #visible},
 * so a case in another tenant is a 404 rather than a source of data (Critical 2).
 *
 * <p>Bodies are built with {@link LinkedHashMap} rather than {@code Map.of} because several
 * fields are legitimately null — most sharply {@code achievedAt} on an unachieved milestone.
 * The task brief's draft wrote {@code String.valueOf(m.achievedAt())} to squeeze that past
 * {@code Map.of}'s null intolerance, which ships the four-character string {@code "null"} to
 * clients: the exact defect Task 17 fixed for {@code CM_TASK.OUTCOME_}.
 */
@RestController
@RequestMapping("/case-api/v2/cases/{caseId}")
public class CollaborationController {

    private final CommentService comments;
    private final DocumentService documents;
    private final MilestoneService milestones;
    private final LinkedProcessService processes;
    private final CaseService cases;
    private final PlanItemRepository planItems;
    private final ActionPolicy policy;
    private final CallerResolver callers;
    private final WorkerPermissionEvaluator permissions;

    public CollaborationController(CommentService comments, DocumentService documents,
                                   MilestoneService milestones, LinkedProcessService processes,
                                   CaseService cases, PlanItemRepository planItems,
                                   ActionPolicy policy, CallerResolver callers,
                                   WorkerPermissionEvaluator permissions) {
        this.comments = comments;
        this.documents = documents;
        this.milestones = milestones;
        this.processes = processes;
        this.cases = cases;
        this.planItems = planItems;
        this.policy = policy;
        this.callers = callers;
        this.permissions = permissions;
    }

    @GetMapping("/comments")
    public List<Map<String, Object>> listComments(@PathVariable String caseId,
                                                  @RequestParam(required = false) String visibility,
                                                  Authentication authentication) {
        Actor actor = callers.actor(authentication);
        CaseInstance c = visible(caseId, actor);
        permissions.assertAllowed(actor, c.tenantId(), PermissionActions.CASE_READ,
                ResourceTypes.CASE, c.id(), caseContext(c));
        return authorizedCommentBodies(comments.forCase(caseId, visibility), actor, c.tenantId());
    }

    @PostMapping("/comments")
    public ResponseEntity<Map<String, Object>> addComment(@PathVariable String caseId,
                                                          @RequestBody CommentRequest request,
                                                          Authentication authentication) {
        Actor actor = callers.actor(authentication);
        CaseInstance c = visible(caseId, actor);
        permissions.assertAllowed(actor, c.tenantId(), PermissionActions.COMMENT_ADD,
                ResourceTypes.COMMENT, "new:" + caseId, commentCreateContext(caseId, request));
        policy.assertAllowedOnCollaboration(cases.snapshot(c),
                callers.roles(caseId, actor), "comment");

        CommentRepository.CommentRow row = comments.add(caseId, request.text(),
                request.visibility() == null ? "internal" : request.visibility(), actor);
        return ResponseEntity.status(HttpStatus.CREATED).body(commentBody(row));
    }

    @GetMapping("/documents")
    public List<Map<String, Object>> listDocuments(@PathVariable String caseId,
                                                   Authentication authentication) {
        Actor actor = callers.actor(authentication);
        CaseInstance c = visible(caseId, actor);
        permissions.assertAllowed(actor, c.tenantId(), PermissionActions.CASE_READ,
                ResourceTypes.CASE, c.id(), caseContext(c));
        return authorizedDocumentBodies(documents.forCase(caseId), actor, c.tenantId());
    }

    @PostMapping("/documents")
    public ResponseEntity<Map<String, Object>> addDocument(@PathVariable String caseId,
                                                           @RequestBody(required = false) DocumentRequest request,
                                                           Authentication authentication) {
        Actor actor = callers.actor(authentication);
        CaseInstance c = visible(caseId, actor);
        DocumentRequest body = requireDocumentRequest(request);
        permissions.assertAllowed(actor, c.tenantId(), PermissionActions.DOCUMENT_LINK,
                ResourceTypes.DOCUMENT, "new:" + caseId, documentCreateContext(caseId, body));
        policy.assertAllowedOnCollaboration(cases.snapshot(c),
                callers.roles(caseId, actor), "add-document");

        DocumentRepository.DocumentRow row = documents.add(caseId, body.name(), body.category(),
                body.mimeType(), body.sizeBytes(), body.contentUrl(), actor);
        return ResponseEntity.status(HttpStatus.CREATED).body(documentBody(row));
    }

    @DeleteMapping("/documents/{documentId}")
    public ResponseEntity<Void> removeDocument(@PathVariable String caseId,
                                               @PathVariable String documentId,
                                               Authentication authentication) {
        Actor actor = callers.actor(authentication);
        CaseInstance c = visible(caseId, actor);
        permissions.assertAllowed(actor, c.tenantId(), PermissionActions.DOCUMENT_REMOVE,
                ResourceTypes.DOCUMENT, documentId, documentContext(caseId, documentId));
        policy.assertAllowedOnCollaboration(cases.snapshot(c),
                callers.roles(caseId, actor), "remove-document");
        documents.remove(caseId, documentId, actor);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/milestones")
    public List<Map<String, Object>> listMilestones(@PathVariable String caseId,
                                                    Authentication authentication) {
        Actor actor = callers.actor(authentication);
        CaseInstance c = visible(caseId, actor);
        permissions.assertAllowed(actor, c.tenantId(), PermissionActions.CASE_READ,
                ResourceTypes.CASE, c.id(), caseContext(c));
        CaseSnapshot snapshot = cases.snapshot(c);
        Set<String> roles = callers.roles(caseId, actor);
        return authorizedMilestoneBodies(milestones.forCase(caseId), snapshot, roles,
                actor, c.tenantId());
    }

    /**
     * {@code If-Match} is accepted here per the spec (fix round 1, I6), and it is enforced
     * against the <b>case's</b> ETag rather than the milestone's: {@code CM_MILESTONE} carries no
     * version column, and the milestone is a sub-resource of the case whose event stream a client
     * is tracking. Requiring it means a client that has not read the case since something else
     * changed it cannot blind-fire an achievement at it.
     */
    @PostMapping("/milestones/{milestoneId}/achieve")
    public Map<String, Object> achieve(@PathVariable String caseId, @PathVariable String milestoneId,
                                       @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                       Authentication authentication) {
        Actor actor = callers.actor(authentication);
        CaseInstance c = visible(caseId, actor);
        requireCaseVersion(c, ifMatch);

        MilestoneRepository.MilestoneRow current = milestones.forCase(caseId).stream()
                .filter(m -> m.id().equals(milestoneId)).findFirst()
                .orElseThrow(() -> new NotFoundException("Milestone", milestoneId));
        Set<String> roles = callers.roles(caseId, actor);
        permissions.assertAllowed(actor, c.tenantId(), PermissionActions.MILESTONE_ACHIEVE,
                ResourceTypes.MILESTONE, milestoneId, milestoneContext(caseId, current));
        policy.assertAllowedOnMilestone(cases.snapshot(c), milestoneId, current.achieved(),
                roles, "achieve");

        MilestoneRepository.MilestoneRow achieved = milestones.achieve(caseId, milestoneId, actor);
        return milestoneBody(achieved,
                policy.listForMilestone(cases.snapshot(caseId), milestoneId, achieved.achieved(), roles));
    }

    @GetMapping("/processes")
    public List<Map<String, Object>> listProcesses(@PathVariable String caseId,
                                                   Authentication authentication) {
        Actor actor = callers.actor(authentication);
        CaseInstance c = visible(caseId, actor);
        permissions.assertAllowed(actor, c.tenantId(), PermissionActions.CASE_READ,
                ResourceTypes.CASE, c.id(), caseContext(c));
        return authorizedProcessBodies(processes.forCase(caseId), actor, c.tenantId());
    }

    /**
     * {@code planItemId} is threaded through to {@code LinkedProcessService.start} (fix round 1,
     * I6). It was hardcoded {@code null}, which made the outbox correlation Task 18 built for
     * plan-item-backed processes unreachable from HTTP. {@code If-Match} is accepted and checked
     * against the case, for the same reason as {@link #achieve}.
     *
     * <p>Exposing that field also made it caller-controlled, so it is validated against the URL's
     * case (fix round 2, review finding Important 1) — the same defect shape as M9, on the
     * sibling endpoint M9's fix did not cover. {@code LinkedProcessService.start} does not check
     * it and {@code CM_LINKED_PROCESS.PLAN_ITEM_ID_} has no foreign key, so without this a caller
     * with {@code owner} on their own case could write a linked-process row pointing at a plan
     * item of another case — and that value propagates: {@code OutboxEngineGateway} copies it into
     * the command payload, so a dead-lettered {@code START_PROCESS} has
     * {@code EngineCommandDispatcher.reportFailure} write against a row in a case the request
     * merely named.
     */
    @PostMapping("/processes")
    public ResponseEntity<Map<String, Object>> startProcess(@PathVariable String caseId,
                                                            @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                                            @RequestBody StartProcessRequest request,
                                                            Authentication authentication) {
        Actor actor = callers.actor(authentication);
        CaseInstance c = visible(caseId, actor);
        requireCaseVersion(c, ifMatch);
        permissions.assertAllowed(actor, c.tenantId(), PermissionActions.PROCESS_START,
                ResourceTypes.PROCESS, "new:" + caseId, processCreateContext(caseId, request));
        policy.assertAllowedOnCollaboration(cases.snapshot(c), callers.roles(caseId, actor),
                "start-process");

        requirePlanItemOfCase(request.planItemId(), caseId);

        LinkedProcessRepository.LinkedProcessRow row = processes.start(caseId, request.planItemId(),
                request.processDefinitionKey(), request.variables(), actor);
        return ResponseEntity.status(HttpStatus.CREATED).body(processBody(row));
    }

    /**
     * Rejects a {@code planItemId} that is not this case's. {@code null} stays legal — the spec
     * makes the field optional, for a process started ad hoc rather than to fulfil a plan item.
     *
     * <p>An unknown id and a foreign id get the identical answer on purpose: distinguishing them
     * would turn this endpoint into an existence oracle for plan items the caller cannot see.
     * The check runs AFTER the policy check, so an unauthorized caller learns nothing about plan
     * items either way.
     */
    private void requirePlanItemOfCase(String planItemId, String caseId) {
        if (planItemId == null) {
            return;
        }
        boolean belongs = planItems.findById(planItemId)
                .filter(item -> item.caseId().equals(caseId))
                .isPresent();
        if (!belongs) {
            throw new CaseConflictException("wrong-case",
                    "Plan item " + planItemId + " does not belong to case " + caseId, List.of());
        }
    }

    /** Loads the case, or reports it absent when it is not this caller's tenant's. */
    private CaseInstance visible(String caseId, Actor actor) {
        CaseInstance c = cases.get(caseId);
        callers.requireVisible("Case", caseId, c.tenantId(), actor);
        return c;
    }

    private CaseSnapshot snapshot(String caseId, Actor actor) {
        return cases.snapshot(visible(caseId, actor));
    }

    /**
     * Enforces {@code If-Match} against the case's current version. These sub-resources have no
     * version of their own, so "accept If-Match" can only mean "the caller's view of the case
     * must still be current" — a real check, not a header read and discarded.
     */
    private void requireCaseVersion(CaseInstance c, String ifMatch) {
        long expected = ETagSupport.expectedVersion(ifMatch, "case " + c.id(),
                () -> OptionalLong.of(c.version()));
        if (expected != c.version()) {
            throw new OptimisticLockException("Case", c.id(), expected);
        }
    }

    private static Map<String, Object> commentBody(CommentRepository.CommentRow c) {
        return commentBody(c, PermissionDecision.allow(c.id()));
    }

    private static Map<String, Object> commentBody(CommentRepository.CommentRow c,
                                                   PermissionDecision decision) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", c.id());
        body.put("caseId", c.caseId());
        putIfAllowed(body, decision, "author", c.author());
        putIfAllowed(body, decision, "text", c.text());
        putIfAllowed(body, decision, "visibility", c.visibility());
        putIfAllowed(body, decision, "createdAt", c.createdAt());
        return body;
    }

    private List<Map<String, Object>> authorizedCommentBodies(
            List<CommentRepository.CommentRow> rows, Actor actor, String tenant) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<String, PermissionDecision> decisions = permissions.evaluate(actor, tenant,
                PermissionActions.COMMENT_READ, ResourceTypes.COMMENT,
                rows.stream()
                        .map(row -> new WorkerPermissionResource(row.id(), commentContext(row)))
                        .toList());
        return rows.stream()
                .map(row -> Map.entry(row, decisions.getOrDefault(row.id(),
                        PermissionDecision.deny(row.id()))))
                .filter(entry -> entry.getValue().allowed())
                .map(entry -> commentBody(entry.getKey(), entry.getValue()))
                .toList();
    }

    private static DocumentRequest requireDocumentRequest(DocumentRequest request) {
        if (request == null) {
            throw new InvalidRequestException("Document request body is required");
        }
        if (request.name() == null || request.name().isBlank()) {
            throw new InvalidRequestException("Document name is required");
        }
        if (request.contentUrl() == null || request.contentUrl().isBlank()) {
            throw new InvalidRequestException("Document contentUrl is required");
        }
        if (request.sizeBytes() != null && request.sizeBytes() < 0) {
            throw new InvalidRequestException("Document sizeBytes must not be negative");
        }
        return request;
    }

    private static Map<String, Object> documentBody(DocumentRepository.DocumentRow d) {
        return documentBody(d, PermissionDecision.allow(d.id()));
    }

    private static Map<String, Object> documentBody(DocumentRepository.DocumentRow d,
                                                    PermissionDecision decision) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", d.id());
        body.put("caseId", d.caseId());
        putIfAllowed(body, decision, "name", d.name());
        putIfAllowed(body, decision, "category", d.category());
        putIfAllowed(body, decision, "mimeType", d.mimeType());
        putIfAllowed(body, decision, "sizeBytes", d.sizeBytes());
        putIfAllowed(body, decision, "contentUrl", d.contentUrl());
        putIfAllowed(body, decision, "uploadedBy", d.uploadedBy());
        body.put("uploadedAt", d.uploadedAt());
        return body;
    }

    private List<Map<String, Object>> authorizedDocumentBodies(
            List<DocumentRepository.DocumentRow> rows, Actor actor, String tenant) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<String, PermissionDecision> decisions = permissions.evaluate(actor, tenant,
                PermissionActions.DOCUMENT_READ, ResourceTypes.DOCUMENT,
                rows.stream()
                        .map(row -> new WorkerPermissionResource(row.id(),
                                documentContext(row.caseId(), row.id())))
                        .toList());
        return rows.stream()
                .map(row -> Map.entry(row, decisions.getOrDefault(row.id(),
                        PermissionDecision.deny(row.id()))))
                .filter(entry -> entry.getValue().allowed())
                .map(entry -> documentBody(entry.getKey(), entry.getValue()))
                .toList();
    }

    private static Map<String, Object> commentCreateContext(String caseId,
                                                            CommentRequest request) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("caseId", caseId);
        context.put("visibility", request == null || request.visibility() == null
                ? "internal" : request.visibility());
        context.put("hasText", request != null && request.text() != null && !request.text().isBlank());
        return context;
    }

    private static Map<String, Object> commentContext(CommentRepository.CommentRow row) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("caseId", row.caseId());
        context.put("visibility", row.visibility());
        context.put("author", row.author());
        return context;
    }

    private static Map<String, Object> documentCreateContext(String caseId, DocumentRequest request) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("caseId", caseId);
        context.put("name", request.name());
        if (request.category() != null) {
            context.put("category", request.category());
        }
        if (request.mimeType() != null) {
            context.put("mimeType", request.mimeType());
        }
        return context;
    }

    private static Map<String, Object> documentContext(String caseId, String documentId) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("caseId", caseId);
        context.put("documentId", documentId);
        return context;
    }

    private static Map<String, Object> milestoneContext(String caseId,
                                                        MilestoneRepository.MilestoneRow row) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("caseId", caseId);
        context.put("milestoneId", row.id());
        if (row.planItemId() != null) {
            context.put("planItemId", row.planItemId());
        }
        context.put("achieved", row.achieved());
        return context;
    }

    private static Map<String, Object> processCreateContext(String caseId,
                                                            StartProcessRequest request) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("caseId", caseId);
        if (request != null && request.planItemId() != null) {
            context.put("planItemId", request.planItemId());
        }
        if (request != null && request.processDefinitionKey() != null) {
            context.put("processDefinitionKey", request.processDefinitionKey());
        }
        return context;
    }

    private static Map<String, Object> processContext(LinkedProcessRepository.LinkedProcessRow row) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("caseId", row.caseId());
        if (row.planItemId() != null) {
            context.put("planItemId", row.planItemId());
        }
        context.put("processDefinitionKey", row.processDefinitionKey());
        context.put("state", row.state());
        context.put("engineSync", row.engineSync().name());
        return context;
    }

    private static Map<String, Object> caseContext(CaseInstance c) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("caseDefinitionKey", c.caseDefKey());
        context.put("state", c.state().name());
        context.put("priority", c.priority().name());
        if (c.businessKey() != null) {
            context.put("businessKey", c.businessKey());
        }
        return context;
    }

    private static boolean fieldAllowed(PermissionDecision decision, String field) {
        return decision.allowedFields().isEmpty()
                || decision.allowedFields().contains("*")
                || decision.allowedFields().contains(field);
    }

    private static void putIfAllowed(Map<String, Object> target, PermissionDecision decision,
                                     String field, Object value) {
        if (value != null && fieldAllowed(decision, field)) {
            target.put(field, value);
        }
    }

    private static void putIfAllowedNullable(Map<String, Object> target,
                                             PermissionDecision decision,
                                             String field, Object value) {
        if (fieldAllowed(decision, field)) {
            target.put(field, value);
        }
    }

    private static Map<String, Object> milestoneBody(MilestoneRepository.MilestoneRow m,
                                                     List<AvailableAction> actions) {
        return milestoneBody(m, actions, PermissionDecision.allow(m.id()));
    }

    private static Map<String, Object> milestoneBody(MilestoneRepository.MilestoneRow m,
                                                     List<AvailableAction> actions,
                                                     PermissionDecision decision) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", m.id());
        body.put("caseId", m.caseId());
        putIfAllowed(body, decision, "planItemId", m.planItemId());
        putIfAllowed(body, decision, "name", m.name());
        putIfAllowed(body, decision, "achieved", m.achieved());
        putIfAllowedNullable(body, decision, "achievedAt", m.achievedAt());
        putIfAllowedNullable(body, decision, "achievedBy", m.achievedBy());
        body.put("availableActions", actions);
        return body;
    }

    private List<Map<String, Object>> authorizedMilestoneBodies(
            List<MilestoneRepository.MilestoneRow> rows, CaseSnapshot snapshot,
            Set<String> roles, Actor actor, String tenant) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<String, PermissionDecision> decisions = permissions.evaluate(actor, tenant,
                PermissionActions.MILESTONE_READ, ResourceTypes.MILESTONE,
                rows.stream()
                        .map(row -> new WorkerPermissionResource(row.id(), milestoneContext(row.caseId(), row)))
                        .toList());
        return rows.stream()
                .map(row -> Map.entry(row, decisions.getOrDefault(row.id(),
                        PermissionDecision.deny(row.id()))))
                .filter(entry -> entry.getValue().allowed())
                .map(entry -> milestoneBody(entry.getKey(),
                        policy.listForMilestone(snapshot, entry.getKey().id(),
                                entry.getKey().achieved(), roles),
                        entry.getValue()))
                .toList();
    }

    /**
     * Carried finding C6, resolved here as the REST layer it was deferred to.
     *
     * <p>{@code LinkedProcessRepository.findByCase} applies no {@code ENGINE_SYNC_} filter,
     * unlike {@code CaseTaskRepository.worklist}, so in remote mode it returns a row whose
     * {@code processInstanceId} is still a locally-minted placeholder. <b>Decision: keep
     * showing the row, and publish {@code engineSync} alongside it</b> rather than adding the
     * hide-until-synced filter.
     *
     * <p>The asymmetry with the worklist is justified, not accidental. The worklist hides an
     * unsynced task because the only thing a user does with a worklist entry is claim it, and
     * claiming an unsynced task is guaranteed to fail ({@code CaseTaskService.claim} rejects it
     * with {@code engine-sync-pending}) — offering it is offering a broken button. A linked
     * process carries no action at all in this API; it is a read-only record. Hiding it would
     * mean {@code POST /cases/{id}/processes} returns 201 and the immediately following
     * {@code GET} shows nothing, which reads as data loss to a client that has no way to know a
     * dispatcher is still draining. Publishing {@code engineSync} tells the client exactly what
     * it needs — that {@code processInstanceId} is not yet the engine's real id — without
     * making a successful create disappear.
     */
    private static Map<String, Object> processBody(LinkedProcessRepository.LinkedProcessRow p) {
        return processBody(p, PermissionDecision.allow(p.id()));
    }

    private static Map<String, Object> processBody(LinkedProcessRepository.LinkedProcessRow p,
                                                   PermissionDecision decision) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", p.id());
        body.put("caseId", p.caseId());
        putIfAllowed(body, decision, "planItemId", p.planItemId());
        putIfAllowed(body, decision, "processInstanceId", p.processInstanceId());
        putIfAllowed(body, decision, "processDefinitionKey", p.processDefinitionKey());
        putIfAllowed(body, decision, "state", p.state());
        putIfAllowed(body, decision, "engineSync", p.engineSync().name());
        return body;
    }

    private List<Map<String, Object>> authorizedProcessBodies(
            List<LinkedProcessRepository.LinkedProcessRow> rows, Actor actor, String tenant) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<String, PermissionDecision> decisions = permissions.evaluate(actor, tenant,
                PermissionActions.PROCESS_READ, ResourceTypes.PROCESS,
                rows.stream()
                        .map(row -> new WorkerPermissionResource(row.id(), processContext(row)))
                        .toList());
        return rows.stream()
                .map(row -> Map.entry(row, decisions.getOrDefault(row.id(),
                        PermissionDecision.deny(row.id()))))
                .filter(entry -> entry.getValue().allowed())
                .map(entry -> processBody(entry.getKey(), entry.getValue()))
                .toList();
    }
}
