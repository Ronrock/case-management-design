package org.casemgmt.rest.controller;

import org.casemgmt.repo.CommentRepository;
import org.casemgmt.repo.LinkedProcessRepository;
import org.casemgmt.repo.MilestoneRepository;
import org.casemgmt.rest.CallerResolver;
import org.casemgmt.rest.dto.Dtos.CommentRequest;
import org.casemgmt.rest.dto.Dtos.StartProcessRequest;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Comments, milestones and linked processes for one case.
 *
 * <p>Bodies are built with {@link LinkedHashMap} rather than {@code Map.of} because several
 * fields are legitimately null — most sharply {@code achievedAt} on an unachieved milestone.
 * The task brief's draft wrote {@code String.valueOf(m.achievedAt())} to squeeze that past
 * {@code Map.of}'s null intolerance, which ships the four-character string {@code "null"} to
 * clients: the exact defect Task 17 fixed for {@code CM_TASK.OUTCOME_}. A null timestamp is
 * serialised as JSON {@code null} here, and a real one as an ISO-8601 instant, so a consumer
 * can parse the field without special-casing a sentinel.
 *
 * <p>These endpoints are authenticated but not gated by {@code ActionPolicy}: its rule table
 * (Task 23) defines actions for the case, plan-item and task surfaces only, and there is no
 * comment/milestone/linked-process action to assert. Extending that table is Task 23's shape of
 * work, not something to improvise per-controller — recorded as a known gap rather than papered
 * over with a borrowed action name whose 409 message would name the wrong operation.
 */
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
                .map(CollaborationController::commentBody)
                .toList();
    }

    @PostMapping("/comments")
    public ResponseEntity<Map<String, Object>> addComment(@PathVariable String caseId,
                                                          @RequestBody CommentRequest request,
                                                          Authentication authentication) {
        CommentRepository.CommentRow row = comments.add(caseId, request.text(),
                request.visibility() == null ? "internal" : request.visibility(),
                callers.actor(authentication));
        return ResponseEntity.status(HttpStatus.CREATED).body(commentBody(row));
    }

    @GetMapping("/milestones")
    public List<Map<String, Object>> listMilestones(@PathVariable String caseId) {
        return milestones.forCase(caseId).stream()
                .map(CollaborationController::milestoneBody)
                .toList();
    }

    @PostMapping("/milestones/{milestoneId}/achieve")
    public Map<String, Object> achieve(@PathVariable String caseId, @PathVariable String milestoneId,
                                       Authentication authentication) {
        return milestoneBody(milestones.achieve(caseId, milestoneId, callers.actor(authentication)));
    }

    @GetMapping("/processes")
    public List<Map<String, Object>> listProcesses(@PathVariable String caseId) {
        return processes.forCase(caseId).stream()
                .map(CollaborationController::processBody)
                .toList();
    }

    @PostMapping("/processes")
    public ResponseEntity<Map<String, Object>> startProcess(@PathVariable String caseId,
                                                            @RequestBody StartProcessRequest request,
                                                            Authentication authentication) {
        LinkedProcessRepository.LinkedProcessRow row = processes.start(caseId, null,
                request.processDefinitionKey(), request.variables(), callers.actor(authentication));
        return ResponseEntity.status(HttpStatus.CREATED).body(processBody(row));
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

    private static Map<String, Object> milestoneBody(MilestoneRepository.MilestoneRow m) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", m.id());
        body.put("name", m.name());
        body.put("achieved", m.achieved());
        body.put("achievedAt", m.achievedAt());
        body.put("achievedBy", m.achievedBy());
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
