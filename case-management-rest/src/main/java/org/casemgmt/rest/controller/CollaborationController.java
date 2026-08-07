package org.casemgmt.rest.controller;

import org.casemgmt.domain.CaseInstance;
import org.casemgmt.error.NotFoundException;
import org.casemgmt.error.OptimisticLockException;
import org.casemgmt.repo.CommentRepository;
import org.casemgmt.repo.LinkedProcessRepository;
import org.casemgmt.repo.MilestoneRepository;
import org.casemgmt.rest.CallerResolver;
import org.casemgmt.rest.dto.Dtos.CommentRequest;
import org.casemgmt.rest.dto.Dtos.StartProcessRequest;
import org.casemgmt.rest.filter.ETagSupport;
import org.casemgmt.rest.policy.ActionPolicy;
import org.casemgmt.rest.policy.AvailableAction;
import org.casemgmt.rules.CaseSnapshot;
import org.casemgmt.service.Actor;
import org.casemgmt.service.CaseService;
import org.casemgmt.service.CommentService;
import org.casemgmt.service.LinkedProcessService;
import org.casemgmt.service.MilestoneService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
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
 * Comments, milestones and linked processes for one case.
 *
 * <p><b>Authorization (fix round 1, Critical 1).</b> The first cut of this class was
 * authenticated but had no rule at all: the caller the case-level policy refuses a title edit to
 * could still comment on the case, achieve its milestones and start a BPMN process against it.
 * All three writes now assert against {@code ActionPolicy}'s own collaboration and milestone
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
    private final MilestoneService milestones;
    private final LinkedProcessService processes;
    private final CaseService cases;
    private final ActionPolicy policy;
    private final CallerResolver callers;

    public CollaborationController(CommentService comments, MilestoneService milestones,
                                   LinkedProcessService processes, CaseService cases,
                                   ActionPolicy policy, CallerResolver callers) {
        this.comments = comments;
        this.milestones = milestones;
        this.processes = processes;
        this.cases = cases;
        this.policy = policy;
        this.callers = callers;
    }

    @GetMapping("/comments")
    public List<Map<String, Object>> listComments(@PathVariable String caseId,
                                                  @RequestParam(required = false) String visibility,
                                                  Authentication authentication) {
        visible(caseId, callers.actor(authentication));
        return comments.forCase(caseId, visibility).stream()
                .map(CollaborationController::commentBody)
                .toList();
    }

    @PostMapping("/comments")
    public ResponseEntity<Map<String, Object>> addComment(@PathVariable String caseId,
                                                          @RequestBody CommentRequest request,
                                                          Authentication authentication) {
        Actor actor = callers.actor(authentication);
        policy.assertAllowedOnCollaboration(snapshot(caseId, actor),
                callers.roles(caseId, actor), "comment");

        CommentRepository.CommentRow row = comments.add(caseId, request.text(),
                request.visibility() == null ? "internal" : request.visibility(), actor);
        return ResponseEntity.status(HttpStatus.CREATED).body(commentBody(row));
    }

    @GetMapping("/milestones")
    public List<Map<String, Object>> listMilestones(@PathVariable String caseId,
                                                    Authentication authentication) {
        Actor actor = callers.actor(authentication);
        CaseSnapshot snapshot = snapshot(caseId, actor);
        Set<String> roles = callers.roles(caseId, actor);
        return milestones.forCase(caseId).stream()
                .map(m -> milestoneBody(m, policy.listForMilestone(snapshot, m.id(), m.achieved(), roles)))
                .toList();
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
        policy.assertAllowedOnMilestone(cases.snapshot(c), milestoneId, current.achieved(),
                roles, "achieve");

        MilestoneRepository.MilestoneRow achieved = milestones.achieve(caseId, milestoneId, actor);
        return milestoneBody(achieved,
                policy.listForMilestone(cases.snapshot(caseId), milestoneId, achieved.achieved(), roles));
    }

    @GetMapping("/processes")
    public List<Map<String, Object>> listProcesses(@PathVariable String caseId,
                                                   Authentication authentication) {
        visible(caseId, callers.actor(authentication));
        return processes.forCase(caseId).stream()
                .map(CollaborationController::processBody)
                .toList();
    }

    /**
     * {@code planItemId} is threaded through to {@code LinkedProcessService.start} (fix round 1,
     * I6). It was hardcoded {@code null}, which made the outbox correlation Task 18 built for
     * plan-item-backed processes unreachable from HTTP. {@code If-Match} is accepted and checked
     * against the case, for the same reason as {@link #achieve}.
     */
    @PostMapping("/processes")
    public ResponseEntity<Map<String, Object>> startProcess(@PathVariable String caseId,
                                                            @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                                            @RequestBody StartProcessRequest request,
                                                            Authentication authentication) {
        Actor actor = callers.actor(authentication);
        CaseInstance c = visible(caseId, actor);
        requireCaseVersion(c, ifMatch);
        policy.assertAllowedOnCollaboration(cases.snapshot(c), callers.roles(caseId, actor),
                "start-process");

        LinkedProcessRepository.LinkedProcessRow row = processes.start(caseId, request.planItemId(),
                request.processDefinitionKey(), request.variables(), actor);
        return ResponseEntity.status(HttpStatus.CREATED).body(processBody(row));
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
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", c.id());
        body.put("author", c.author());
        body.put("text", c.text());
        body.put("visibility", c.visibility());
        body.put("createdAt", c.createdAt());
        return body;
    }

    private static Map<String, Object> milestoneBody(MilestoneRepository.MilestoneRow m,
                                                     List<AvailableAction> actions) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", m.id());
        body.put("name", m.name());
        body.put("achieved", m.achieved());
        body.put("achievedAt", m.achievedAt());
        body.put("achievedBy", m.achievedBy());
        body.put("availableActions", actions);
        return body;
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
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", p.id());
        body.put("planItemId", p.planItemId());
        body.put("processInstanceId", p.processInstanceId());
        body.put("processDefinitionKey", p.processDefinitionKey());
        body.put("state", p.state());
        body.put("engineSync", p.engineSync().name());
        return body;
    }
}
