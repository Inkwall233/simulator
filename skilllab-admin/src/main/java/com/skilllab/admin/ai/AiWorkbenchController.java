package com.skilllab.admin.ai;

import java.util.List;
import java.util.Map;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/ai")
public class AiWorkbenchController {

    private final AiWorkbenchService service;
    private final AiWorkerClient worker;
    private final AiApiDraftService drafts;

    public AiWorkbenchController(AiWorkbenchService service, AiWorkerClient worker, AiApiDraftService drafts) {
        this.service = service;
        this.worker = worker;
        this.drafts = drafts;
    }

    @GetMapping("/device-projects")
    public List<Map<String, Object>> listProjects() {
        return service.listProjects();
    }

    @PostMapping("/device-projects")
    public Map<String, Object> createProject(@RequestBody Map<String, Object> request) {
        return service.createProject(request);
    }

    @GetMapping("/device-projects/{projectId}")
    public Map<String, Object> getProject(@PathVariable String projectId) {
        return service.getProject(projectId);
    }

    @PostMapping(value = "/device-projects/{projectId}/packages", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> uploadPackage(
            @PathVariable String projectId,
            @RequestParam("file") MultipartFile file) {
        return service.uploadPackage(projectId, file);
    }

    @PostMapping("/device-projects/{projectId}/sessions")
    public Map<String, Object> createSession(
            @PathVariable String projectId,
            @RequestBody Map<String, Object> request) {
        return service.createSession(projectId, request);
    }

    @GetMapping(value = "/sessions/{sessionId}/screenshot", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> screenshot(@PathVariable String sessionId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.IMAGE_PNG);
        headers.setCacheControl(CacheControl.noStore());
        return new ResponseEntity<byte[]>(service.screenshot(sessionId), headers, HttpStatus.OK);
    }

    @GetMapping("/sessions/{sessionId}/network")
    public Map<String, Object> network(@PathVariable String sessionId) {
        return service.workerJson(sessionId, "/network");
    }

    @PostMapping("/sessions/{sessionId}/drafts")
    public Map<String, Object> createApiDraft(@PathVariable String sessionId) {
        service.requireActiveSession(sessionId);
        return drafts.createDraft(sessionId);
    }

    @GetMapping("/sessions/{sessionId}/drafts/latest")
    public Map<String, Object> latestApiDraft(@PathVariable String sessionId) {
        return drafts.latestDraft(sessionId);
    }

    @GetMapping("/sessions/{sessionId}/pending-approval")
    public Map<String, Object> pendingApproval(@PathVariable String sessionId) {
        return service.workerJson(sessionId, "/pending-approval");
    }

    @PostMapping("/sessions/{sessionId}/actions")
    public Map<String, Object> action(
            @PathVariable String sessionId,
            @RequestBody Map<String, Object> request) {
        return service.workerPost(sessionId, "/actions", request);
    }

    @PostMapping("/sessions/{sessionId}/commands")
    public Map<String, Object> command(
            @PathVariable String sessionId,
            @RequestBody Map<String, Object> request) {
        return service.workerPost(sessionId, "/commands", request);
    }

    @PostMapping("/sessions/{sessionId}/approvals")
    public Map<String, Object> approve(
            @PathVariable String sessionId,
            @RequestBody Map<String, Object> request) {
        return service.workerPost(sessionId, "/approvals", request);
    }

    @PostMapping("/sessions/{sessionId}/close")
    public Map<String, Object> closeSession(@PathVariable String sessionId) {
        return service.closeSession(sessionId);
    }

    @ExceptionHandler(AiWorkbenchService.AiNotFoundException.class)
    public ResponseEntity<Map<String, String>> notFound(RuntimeException exception) {
        return error(HttpStatus.NOT_FOUND, exception.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> invalidRequest(IllegalArgumentException exception) {
        return error(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    @ExceptionHandler(AiWorkerClient.AiWorkerException.class)
    public ResponseEntity<Map<String, String>> workerFailure(AiWorkerClient.AiWorkerException exception) {
        HttpStatus status = HttpStatus.resolve(exception.getStatus());
        return error(status == null ? HttpStatus.BAD_GATEWAY : status, exception.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> internalFailure(Exception exception) {
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "AI workbench request failed");
    }

    private ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
        Map<String, String> body = new java.util.LinkedHashMap<String, String>();
        body.put("error", message == null ? status.getReasonPhrase() : message);
        return new ResponseEntity<Map<String, String>>(body, status);
    }
}
