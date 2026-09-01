package org.casemgmt.rest.controller;

import org.casemgmt.engine.EngineCommand;
import org.casemgmt.error.NotFoundException;
import org.casemgmt.projection.ActiveBpmnCaseRepository;
import org.casemgmt.projection.RemotePollingCheckpointRepository;
import org.casemgmt.repo.EngineCommandRepository;
import org.casemgmt.rest.CallerResolver;
import org.casemgmt.rest.policy.ActionPolicy;
import org.casemgmt.service.Actor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Admin-only remote observation health and command dead-letter recovery. */
@RestController
@RequestMapping("/case-api/v2/orchestration")
public class OrchestrationOperationsController {

    private static final String POLLER = "operaton-history";
    private final RemotePollingCheckpointRepository checkpoints;
    private final ActiveBpmnCaseRepository activeCases;
    private final EngineCommandRepository commands;
    private final ActionPolicy policy;
    private final CallerResolver callers;

    public OrchestrationOperationsController(RemotePollingCheckpointRepository checkpoints,
                                             ActiveBpmnCaseRepository activeCases,
                                             EngineCommandRepository commands,
                                             ActionPolicy policy, CallerResolver callers) {
        this.checkpoints = checkpoints;
        this.activeCases = activeCases;
        this.commands = commands;
        this.policy = policy;
        this.callers = callers;
    }

    @GetMapping("/remote-status")
    public Map<String, Object> status(Authentication authentication) {
        authorize(authentication, "read-remote-orchestration-status");
        var checkpoint = checkpoints.find(POLLER);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("poller", POLLER);
        response.put("status", checkpoint.map(value -> value.status().name()).orElse("NEVER_POLLED"));
        response.put("watermark", checkpoint.map(
                RemotePollingCheckpointRepository.Checkpoint::watermark).orElse(null));
        response.put("lastSuccessAt", checkpoint.map(
                RemotePollingCheckpointRepository.Checkpoint::lastSuccessAt).orElse(null));
        response.put("lastError", checkpoint.map(
                RemotePollingCheckpointRepository.Checkpoint::lastError).orElse(null));
        response.put("secondsSinceSuccess", checkpoint.map(value -> value.lastSuccessAt() == null
                ? null : Math.max(0, Duration.between(value.lastSuccessAt(),
                        OffsetDateTime.now(ZoneOffset.UTC)).toSeconds())).orElse(null));
        response.put("activeBpmnCases", activeCases.findAll().size());
        return response;
    }

    @GetMapping("/commands/dead-letters")
    public List<Map<String, Object>> deadLetters(
            @RequestParam(defaultValue = "50") int limit, Authentication authentication) {
        authorize(authentication, "read-orchestration-dead-letters");
        return commands.findDead(limit).stream().map(this::deadLetter).toList();
    }

    @PostMapping("/commands/dead-letters/{commandId}/retry")
    public ResponseEntity<Void> retry(@PathVariable String commandId,
                                      Authentication authentication) {
        authorize(authentication, "retry-orchestration-command");
        if (!commands.retryDead(commandId)) {
            throw new NotFoundException("DeadEngineCommand", commandId);
        }
        return ResponseEntity.accepted().build();
    }

    private Map<String, Object> deadLetter(EngineCommand command) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", command.id());
        response.put("caseId", "-".equals(command.caseId()) ? null : command.caseId());
        response.put("type", command.type().name());
        response.put("status", command.status());
        response.put("attempts", command.attempts());
        response.put("lastError", command.lastError());
        response.put("retryAction", "/case-api/v2/orchestration/commands/dead-letters/"
                + command.id() + "/retry");
        return response;
    }

    private void authorize(Authentication authentication, String action) {
        Actor actor = callers.actor(authentication);
        policy.assertMayAdminister(callers.groups(actor), action);
    }
}
