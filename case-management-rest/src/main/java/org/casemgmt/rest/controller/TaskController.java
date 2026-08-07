package org.casemgmt.rest.controller;

import org.casemgmt.domain.CaseTask;
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
 * <p>The role set fed to the policy is {@link CallerResolver#taskRoles} — participant roles plus
 * the caller's identity groups — because {@code ActionPolicy}'s task rule intersects against the
 * task's {@code candidateGroups}, which are group names. See that method for why the brief's
 * participant-roles-only version would have made the candidate-group half of the rule dead code.
 */
@RestController
@RequestMapping("/case-api/v2")
public class TaskController {

    private final CaseTaskService tasks;
    private final CaseTaskRepository taskRepo;
    private final ActionPolicy policy;
    private final CallerResolver callers;

    public TaskController(CaseTaskService tasks, CaseTaskRepository taskRepo, ActionPolicy policy,
                          CallerResolver callers) {
        this.tasks = tasks;
        this.taskRepo = taskRepo;
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
        long version = expectedVersion(ifMatch, taskId);

        CaseTask current = taskRepo.require(taskId);
        policy.assertAllowedOnTask(current, actor.userId(),
                callers.taskRoles(current.caseId(), actor), "claim");

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
        policy.assertAllowedOnTask(current, actor.userId(),
                callers.taskRoles(current.caseId(), actor), "complete");

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

    private TaskResponse respond(CaseTask task, Actor actor) {
        return TaskResponse.of(task,
                policy.listForTask(task, actor.userId(), callers.taskRoles(task.caseId(), actor)));
    }
}
