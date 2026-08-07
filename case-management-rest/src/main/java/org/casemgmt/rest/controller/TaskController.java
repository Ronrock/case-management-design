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

        // Tenant check BEFORE If-Match (final whole-branch review, Minor). See expectedVersion.
        CaseTask current = taskRepo.require(taskId);
        visibleCaseOf(current.caseId(), actor);
        long version = expectedVersion(ifMatch, taskId, actor);
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

        // Tenant check BEFORE If-Match (final whole-branch review, Minor). See expectedVersion.
        CaseTask current = taskRepo.require(taskId);
        visibleCaseOf(current.caseId(), actor);
        long version = expectedVersion(ifMatch, taskId, actor);
        policy.assertAllowedOnTask(current, actor.userId(),
                callers.roles(current.caseId(), actor), callers.groups(actor), "complete");

        CaseTask completed = tasks.complete(taskId, version,
                request == null || request.variables() == null ? Map.of() : request.variables(), actor);
        return ResponseEntity.ok().eTag(ETagSupport.format(completed.version()))
                .body(respond(completed, actor));
    }

    /**
     * Resolves {@code If-Match} for this task, including the {@code *} wildcard.
     *
     * <p><b>Tenant-filtered, and called only after the tenant check</b> (final whole-branch
     * review, Minor). Both were missing: {@code If-Match} used to be resolved before
     * {@code visibleCaseOf} ran, against an unfiltered lookup, so {@code If-Match: *} answered
     * 412 for an id that does not exist and proceeded for an id that exists in ANOTHER tenant —
     * an existence oracle across the tenant boundary. {@code CaseController.expectedVersion} was
     * given exactly this filter for exactly this reason; the task path was not, and the asymmetry
     * with its own sibling is what made it a defect rather than a choice.
     *
     * <p>Belt and braces, deliberately. The reordering alone closes it (the caller now throws
     * before this method runs), and the filter alone would too, but each is one edit away from
     * being undone by someone who does not know the other is load-bearing: a future action method
     * that forgets the ordering still gets a tenant-safe answer, and a refactor that "simplifies"
     * the filter still cannot see past the ordering.
     */
    private long expectedVersion(String ifMatch, String taskId, Actor actor) {
        return ETagSupport.expectedVersion(ifMatch, "task " + taskId,
                () -> taskRepo.findById(taskId)
                        .filter(t -> caseRepo.findById(t.caseId())
                                .map(c -> c.tenantId() != null
                                        && c.tenantId().equals(callers.tenantId(actor)))
                                .orElse(false))
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
