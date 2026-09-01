package org.casemgmt.rest.controller;

import org.casemgmt.release.CaseDefinitionRelease;
import org.casemgmt.release.ReleaseKind;
import org.casemgmt.repo.CaseDefinitionReleaseRepository;
import org.casemgmt.rest.CallerResolver;
import org.casemgmt.rest.dto.BindingResponseFields;
import org.casemgmt.rest.policy.ActionPolicy;
import org.casemgmt.service.Actor;
import org.casemgmt.service.CaseDefinitionReleaseService;
import org.casemgmt.service.CaseDefinitionVersionService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/case-api/v2/case-definitions/{key}")
public class CaseDefinitionReleaseController {

    private static final int MAX_FAILURE_DETAIL = 2_000;

    private final CaseDefinitionReleaseService service;
    private final CaseDefinitionReleaseRepository repository;
    private final CaseDefinitionVersionService versions;
    private final ActionPolicy policy;
    private final CallerResolver callers;

    public CaseDefinitionReleaseController(CaseDefinitionReleaseService service,
                                           CaseDefinitionReleaseRepository repository,
                                           CaseDefinitionVersionService versions,
                                           ActionPolicy policy, CallerResolver callers) {
        this.service = service;
        this.repository = repository;
        this.versions = versions;
        this.policy = policy;
        this.callers = callers;
    }

    @PostMapping(value = "/orchestration-releases",
            consumes = {"application/zip", "application/xml", "application/bpmn+xml"})
    public ResponseEntity<Map<String, Object>> orchestration(
            @PathVariable String key, @RequestBody byte[] content,
            @RequestHeader(HttpHeaders.CONTENT_TYPE) String contentType,
            Authentication authentication) {
        return publish(key, ReleaseKind.ORCHESTRATION, contentType, content, authentication);
    }

    @PostMapping(value = "/contract-releases",
            consumes = {MediaType.APPLICATION_JSON_VALUE, "application/schema+json"})
    public ResponseEntity<Map<String, Object>> contract(
            @PathVariable String key, @RequestBody byte[] content,
            @RequestHeader(HttpHeaders.CONTENT_TYPE) String contentType,
            Authentication authentication) {
        return publish(key, ReleaseKind.CONTRACT, contentType, content, authentication);
    }

    @PostMapping(value = "/presentation-releases", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> presentation(
            @PathVariable String key, @RequestBody byte[] content,
            @RequestHeader(HttpHeaders.CONTENT_TYPE) String contentType,
            Authentication authentication) {
        return publish(key, ReleaseKind.PRESENTATION, contentType, content, authentication);
    }

    @GetMapping(value = "/presentation-releases/{releaseId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> presentation(
            @PathVariable String key, @PathVariable String releaseId,
            Authentication authentication) {
        Actor actor = callers.actor(authentication);
        String tenantId = callers.requireTenant(actor, null);
        CaseDefinitionRelease release = repository.require(releaseId, tenantId);
        if (release.kind() != ReleaseKind.PRESENTATION || !release.definitionKey().equals(key)) {
            throw new org.casemgmt.error.NotFoundException("PresentationRelease", releaseId);
        }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(release.content());
    }

    @GetMapping(value = "/contract-releases/{releaseId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> contract(
            @PathVariable String key, @PathVariable String releaseId,
            Authentication authentication) {
        Actor actor = callers.actor(authentication);
        String tenantId = callers.requireTenant(actor, null);
        CaseDefinitionRelease release = repository.require(releaseId, tenantId);
        if (release.kind() != ReleaseKind.CONTRACT || !release.definitionKey().equals(key)) {
            throw new org.casemgmt.error.NotFoundException("ContractRelease", releaseId);
        }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(release.content());
    }

    /**
     * Administrative lifecycle metadata for every immutable release kind. Content downloads keep
     * their existing kind-specific routes; publication Locations always target this representation.
     */
    @GetMapping(value = "/releases/{releaseId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> release(
            @PathVariable String key, @PathVariable String releaseId,
            Authentication authentication) {
        Actor actor = callers.actor(authentication);
        policy.assertMayAdminister(callers.groups(actor), "read-case-definition-release");
        String tenantId = callers.requireTenant(actor, null);
        CaseDefinitionRelease release = repository.require(releaseId, tenantId);
        if (!release.definitionKey().equals(key)) {
            throw new org.casemgmt.error.NotFoundException("CaseDefinitionRelease", releaseId);
        }
        return releaseBody(release);
    }

    @PostMapping(value = "/versions", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> bindVersion(
            @PathVariable String key, @RequestBody Map<String, Object> request,
            Authentication authentication) {
        Actor actor = callers.actor(authentication);
        policy.assertMayAdminister(callers.groups(actor), "bind-case-definition-version");
        String tenantId = callers.requireTenant(actor, null);
        var binding = versions.bind(key, tenantId,
                required(request, "orchestrationReleaseId"),
                required(request, "contractReleaseId"),
                required(request, "presentationReleaseId"), actor.userId());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("caseDefinitionId", binding.caseDefinitionId());
        body.put("orchestrationMode", "BPMN");
        BindingResponseFields.put(body, binding, true);
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    private ResponseEntity<Map<String, Object>> publish(
            String key, ReleaseKind kind, String contentType, byte[] content,
            Authentication authentication) {
        Actor actor = callers.actor(authentication);
        policy.assertMayAdminister(callers.groups(actor), "publish-" + kind.name().toLowerCase());
        String tenantId = callers.requireTenant(actor, null);
        CaseDefinitionRelease release = service.publish(
                key, tenantId, kind, contentType, content, actor.userId());
        Map<String, Object> body = releaseBody(release);
        return ResponseEntity.status(HttpStatus.CREATED)
                .location(URI.create("/case-api/v2/case-definitions/" + key
                        + "/releases/" + release.id()))
                .body(body);
    }

    private static Map<String, Object> releaseBody(CaseDefinitionRelease release) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", release.id());
        body.put("definitionKey", release.definitionKey());
        body.put("kind", release.kind().name());
        body.put("sha256", release.sha256());
        body.put("mediaType", release.mediaType());
        body.put("status", release.status().name());
        body.put("failureDetail", boundedFailure(release.failureDetail()));
        body.put("publishedAt", release.publishedAt());
        return body;
    }

    private static String boundedFailure(String detail) {
        if (detail == null || detail.length() <= MAX_FAILURE_DETAIL) return detail;
        return detail.substring(0, MAX_FAILURE_DETAIL - 3) + "...";
    }

    private static String required(Map<String, Object> request, String field) {
        Object value = request.get(field);
        if (value == null || value.toString().isBlank()) {
            throw new org.casemgmt.error.InvalidCaseDefinitionException("<unknown>",
                    "Version binding requires " + field);
        }
        return value.toString();
    }
}
