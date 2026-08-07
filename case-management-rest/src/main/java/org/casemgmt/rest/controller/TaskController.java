package org.casemgmt.rest.controller;

import org.casemgmt.domain.CaseInstance;
import org.casemgmt.domain.CaseTask;
import org.casemgmt.repo.CaseRepository;
import org.casemgmt.repo.CaseTaskRepository;
import org.casemgmt.rest.CallerResolver;
import org.casemgmt.rest.dto.Dtos.CompleteTaskRequest;
import org.casemgmt.rest.dto.Dtos.TaskResponse;
import org.casemgmt.rest.filter.ETagSupport;
import org.casemgmt.rest.policy.ActionPolicy;
import org.casemgmt.service.Actor;
import org.casemgmt.service.CaseTaskService;
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

import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

/**
 * Worklist, claim and complete (spec §4.5/§4.6).
 *
 * <p><b>This class is where task authorization happens — nowhere else does.</b>
 * {@code CaseTaskService.claim} and {@code .complete} check task state, engine-sync state and
 * the optimistic version, and deliberately no identity at all (carried finding C1): the service
 * will happily let a watcher claim a task, or let someone who is not the assignee complete one.
 * {@link ActionPolicy#assertAllowedOnTask} is the only thing that stops either, and it is
 * called immediately before both service calls, with no branch around it.
 *
 * <p>{@code ActionPolicy} is given the caller's participant roles and identity groups as two
 * SEPARATE arguments (fix round 1, review finding I3). An earlier cut passed their union, because
 * {@code candidateGroups} holds group names while {@code mayMutate} tests role names; that union
 * meant an identity group literally named {@code owner} or {@code handler} granted claim and
 * complete on every task in every case. Keeping them apart puts the invariant in the code instead
 * of in a convention about how groups are named.
 *
 * <p>Every method also resolves the owning case and refuses another tenant's (Critical 2):
 * identity groups are global, so without that a user of tenant B who happens to be in a group
 * named {@code reviewers} could see and claim tenant A's work.
 */
@RestController
@RequestMapping("/case-api/v2")
public class TaskController {

    private final CaseTaskService tasks;
    private final CaseTaskRepository taskRepo;
    private final CaseRepository caseRepo;
    private final ActionPolicy policy;
    private final CallerResolver callers;

    public TaskController(CaseTaskService tasks, CaseTaskRepository taskRepo, CaseRepository caseRepo,
                          ActionPolicy policy, CallerResolver callers) {
        this.tasks = tasks;
        this.taskRepo = taskRepo;
        this.caseRepo = caseRepo;
        this.policy = policy;
        this.callers = callers;
    }

    @GetMapping("/tasks")
    public List<TaskResponse> worklist(@RequestParam(defaultValue = "50") int limit,
                                       Authentication authentication) {
        Actor actor = callers.actor(authentication);
        return tasks.worklist(callers.tenantId(actor), actor,
                        Math.clamp(limit, 1, CaseController.MAX_PAGE_SIZE)).stream()
                .map(t -> respond(t, actor)).toList();
    }

    @GetMapping("/cases/{caseId}/tasks")
    public List<TaskResponse> forCase(@PathVariable String caseId, Authentication authentication) {
        Actor actor = callers.actor(authentication);
        visibleCaseOf(caseId, actor);
        return tasks.forCase(caseId).stream().map(t -> respond(t, actor)).toList();
    }

    @PostMapping("/tasks/{taskId}/claim")
    public ResponseEntity<TaskResponse> claim(@PathVariable String taskId,
                                              @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                              Authentication authentication) {
        Actor actor = callers.actor(authentication);
        long version = expectedVersion(ifMatch, taskId);

        CaseTask current = taskRepo.require(taskId);
        visibleCaseOf(current.caseId(), actor);
        policy.assertAllowedOnTask(current, actor.userId(),
                callers.roles(current.caseId(), actor), callers.groups(actor), "claim");

        CaseTask claimed = tasks.claim(taskId, version, actor);
        return ResponseEntity.ok().eTag(ETagSupport.format(claimed.version()))
                .body(respond(claimed, actor));
    }

    @PostMapping("/tasks/{taskId}/complete")
    public ResponseEntity<TaskResponse> complete(@PathVariable String taskId,
                                                 @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                                 @RequestBody(required = false) CompleteTaskRequest request,
                                                 Authentication authentication) {
        Actor actor = callers.actor(authentication);
        long version = expectedVersion(ifMatch, taskId);

        CaseTask current = taskRepo.require(taskId);
        visibleCaseOf(current.caseId(), actor);
        policy.assertAllowedOnTask(current, actor.userId(),
                callers.roles(current.caseId(), actor), callers.groups(actor), "complete");

        CaseTask completed = tasks.complete(taskId, version,
                request == null || request.variables() == null ? Map.of() : request.variables(), actor);
        return ResponseEntity.ok().eTag(ETagSupport.format(completed.version()))
                .body(respond(completed, actor));
    }

    private long expectedVersion(String ifMatch, String taskId) {
        return ETagSupport.expectedVersion(ifMatch, "task " + taskId,
                () -> taskRepo.findById(taskId)
                        .map(t -> OptionalLong.of(t.version()))
                        .orElseGet(OptionalLong::empty));
    }

    /** A task belongs to a case; a case belongs to a tenant. This is where that is enforced. */
    private CaseInstance visibleCaseOf(String caseId, Actor actor) {
        CaseInstance c = caseRepo.require(caseId);
        callers.requireVisible("Case", caseId, c.tenantId(), actor);
        return c;
    }

    private TaskResponse respond(CaseTask task, Actor actor) {
        return TaskResponse.of(task, policy.listForTask(task, actor.userId(),
                callers.roles(task.caseId(), actor), callers.groups(actor)));
    }
}
