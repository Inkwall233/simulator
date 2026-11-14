package com.skilllab.admin.ai;

import java.time.Instant;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/simulator")
public class AiSimulatorRunController {
    private final AiCreatorAuth auth;
    private final AiRunService runs;

    public AiSimulatorRunController(AiCreatorAuth auth, AiRunService runs) {
        this.auth = auth; this.runs = runs;
    }

    @PostMapping("/auth/login")
    public Map<String, String> login(@RequestBody Map<String, String> credentials, HttpServletRequest request) {
        return auth.login(request, credentials.get("username"), credentials.get("password"));
    }

    @PostMapping("/auth/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        auth.logout(request); return ResponseEntity.noContent().build();
    }

    @GetMapping("/auth/me")
    public Map<String, String> me(HttpServletRequest request) {
        return java.util.Collections.singletonMap("userId", auth.requireUser(request));
    }

    @PostMapping("/versions/{versionId}/runs")
    public Map<String, Object> start(@PathVariable String versionId, HttpServletRequest request) {
        return runs.startRun(versionId, auth.requireUser(request));
    }

    @GetMapping("/runs/{runId}")
    public Map<String, Object> get(@PathVariable String runId, HttpServletRequest request) {
        return runs.getRun(runId, auth.requireUser(request));
    }

    @PostMapping("/runs/{runId}/heartbeat")
    public Map<String, Object> heartbeat(@PathVariable String runId, HttpServletRequest request) {
        return runs.heartbeat(runId, auth.requireUser(request), Instant.now());
    }

    @PostMapping("/runs/{runId}/end")
    public ResponseEntity<Void> end(@PathVariable String runId, HttpServletRequest request) {
        runs.endRun(runId, auth.requireUser(request));
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(AiWorkbenchService.AiNotFoundException.class)
    public ResponseEntity<Map<String, String>> notFound(RuntimeException exception) {
        return error(HttpStatus.NOT_FOUND, exception.getMessage());
    }

    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<Map<String, String>> forbidden(SecurityException exception) {
        return error(HttpStatus.FORBIDDEN, exception.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> conflict(IllegalStateException exception) {
        return error(HttpStatus.CONFLICT, exception.getMessage());
    }

    private ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
        return new ResponseEntity<Map<String, String>>(java.util.Collections.singletonMap("error", message), status);
    }
}
