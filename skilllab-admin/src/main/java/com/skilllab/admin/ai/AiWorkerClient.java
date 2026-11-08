package com.skilllab.admin.ai;

import java.util.Collections;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

@Component
public class AiWorkerClient {

    private final RestTemplate restTemplate;
    private final String workerUrl;
    private final String workerSecret;

    public AiWorkerClient(
            RestTemplate aiWorkerRestTemplate,
            @Value("${skilllab.ai.worker-url}") String workerUrl,
            @Value("${skilllab.ai.worker-secret:}") String workerSecret) {
        this.restTemplate = aiWorkerRestTemplate;
        this.workerUrl = workerUrl.replaceAll("/$", "");
        this.workerSecret = workerSecret;
    }

    public Map<String, Object> createSession(String sessionId, String targetUrl) {
        Map<String, Object> body = new java.util.LinkedHashMap<String, Object>();
        body.put("sessionId", sessionId);
        body.put("targetUrl", targetUrl);
        return post("/internal/sessions", body);
    }

    public Map<String, Object> post(String path, Map<String, ?> body) {
        HttpHeaders headers = headers();
        headers.setContentType(MediaType.APPLICATION_JSON);
        try {
            return restTemplate.postForObject(workerUrl + path, new HttpEntity<Map<String, ?>>(body, headers), Map.class);
        } catch (HttpStatusCodeException exception) {
            throw new AiWorkerException(exception.getStatusCode().value(), exception.getResponseBodyAsString());
        }
    }

    public Map<String, Object> getJson(String path) {
        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                    workerUrl + path, HttpMethod.GET, new HttpEntity<Void>(headers()), Map.class);
            return response.getBody();
        } catch (HttpStatusCodeException exception) {
            throw new AiWorkerException(exception.getStatusCode().value(), exception.getResponseBodyAsString());
        }
    }

    public Map<String, Object> getRun(String host, String runId) {
        HttpHeaders headers = headers();
        headers.set("X-SkillLab-Run-Host", host);
        try {
            return restTemplate.exchange(workerUrl + "/internal/runs/" + runId, HttpMethod.GET,
                    new HttpEntity<Void>(headers), Map.class).getBody();
        } catch (HttpStatusCodeException exception) {
            throw new AiWorkerException(exception.getStatusCode().value(), exception.getResponseBodyAsString());
        }
    }

    public byte[] getBytes(String path) {
        try {
            ResponseEntity<byte[]> response = restTemplate.exchange(
                    workerUrl + path, HttpMethod.GET, new HttpEntity<Void>(headers()), byte[].class);
            return response.getBody();
        } catch (HttpStatusCodeException exception) {
            throw new AiWorkerException(exception.getStatusCode().value(), exception.getResponseBodyAsString());
        }
    }

    private HttpHeaders headers() {
        if (workerSecret == null || workerSecret.trim().isEmpty()) {
            throw new AiWorkerException(503, "AI worker shared secret is not configured");
        }
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-SkillLab-Worker-Key", workerSecret);
        return headers;
    }

    public static class AiWorkerException extends RuntimeException {
        private final int status;

        public AiWorkerException(int status, String message) {
            super(message == null || message.isEmpty() ? "AI worker request failed" : message);
            this.status = status;
        }

        public int getStatus() {
            return status;
        }
    }
}
