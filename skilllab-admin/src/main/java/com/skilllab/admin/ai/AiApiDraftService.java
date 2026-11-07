package com.skilllab.admin.ai;

import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AiApiDraftService {

    private final AiSessionRepository sessions;
    private final AiApiDraftRepository drafts;
    private final AiWorkerClient worker;
    private final ObjectMapper objectMapper;

    public AiApiDraftService(
            AiSessionRepository sessions,
            AiApiDraftRepository drafts,
            AiWorkerClient worker,
            ObjectMapper objectMapper) {
        this.sessions = sessions;
        this.drafts = drafts;
        this.worker = worker;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Map<String, Object> createDraft(String sessionId) {
        AiSessionEntity session = requireActiveSession(sessionId);
        Map<String, Object> networkResult = worker.getJson("/internal/sessions/" + sessionId + "/network");
        Object rawItems = networkResult == null ? null : networkResult.get("items");
        if (!(rawItems instanceof List)) {
            throw new IllegalStateException("Browser worker returned an invalid network list");
        }

        Map<String, Map<String, Object>> grouped = new LinkedHashMap<String, Map<String, Object>>();
        for (Object rawItem : (List<?>) rawItems) {
            if (!(rawItem instanceof Map)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> item = (Map<String, Object>) rawItem;
            addObservation(grouped, item);
        }
        if (grouped.isEmpty()) {
            throw new IllegalArgumentException("No API-like requests captured yet");
        }

        List<Map<String, Object>> endpoints = new ArrayList<Map<String, Object>>(grouped.values());
        List<Map<String, Object>> mockRules = makeMockRules(endpoints);
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("schemaVersion", "skilllab.device-api-draft.v1");
        payload.put("projectId", session.getProjectId());
        payload.put("sessionId", sessionId);
        payload.put("createdAt", LocalDateTime.now().toString());
        payload.put("target", withoutQuery(session.getTargetUrl()));
        payload.put("endpoints", endpoints);
        payload.put("mockRules", mockRules);

        try {
            AiApiDraftEntity draft = drafts.save(new AiApiDraftEntity(
                    UUID.randomUUID().toString(),
                    session.getProjectId(),
                    sessionId,
                    objectMapper.writeValueAsString(payload)));
            return draftView(draft, payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize API draft", exception);
        }
    }

    @Transactional(readOnly = true)
    public Map<String, Object> latestDraft(String sessionId) {
        if (!sessions.existsById(sessionId)) {
            throw new AiWorkbenchService.AiNotFoundException("Exploration session not found");
        }
        AiApiDraftEntity draft = drafts.findFirstBySessionIdOrderByCreatedAtDesc(sessionId)
                .orElseThrow(() -> new AiWorkbenchService.AiNotFoundException("No API draft exists for this session"));
        try {
            Map<String, Object> payload = objectMapper.readValue(draft.getPayloadJson(), Map.class);
            return draftView(draft, payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored API draft is invalid", exception);
        }
    }

    private void addObservation(Map<String, Map<String, Object>> grouped, Map<String, Object> item) {
        String method = stringValue(item.get("method")).toUpperCase();
        String requestUrl = stringValue(item.get("url"));
        if (method.isEmpty() || requestUrl.isEmpty() || "WS".equals(method)) return;

        URL url;
        try {
            url = new URL(requestUrl);
        } catch (MalformedURLException exception) {
            return;
        }
        String path = url.getPath();
        if (path == null || path.isEmpty()) path = "/";
        String resourceType = stringValue(item.get("resourceType"));
        String contentType = stringValue(item.get("contentType"));
        boolean apiLike = "fetch".equals(resourceType)
                || "xhr".equals(resourceType)
                || contentType.toLowerCase().contains("json")
                || !isReadMethod(method);
        if (!apiLike) return;

        String key = method + " " + path;
        Map<String, Object> endpoint = grouped.get(key);
        if (endpoint == null) {
            endpoint = new LinkedHashMap<String, Object>();
            endpoint.put("id", stableId(key));
            endpoint.put("method", method);
            endpoint.put("path", path);
            endpoint.put("queryKeys", queryKeys(url.getQuery()));
            endpoint.put("observations", 0);
            endpoint.put("observationIds", new ArrayList<String>());
            endpoint.put("responseStatusCodes", new LinkedHashSet<Integer>());
            endpoint.put("requestBodyObserved", false);
            endpoint.put("responseContentType", null);
            endpoint.put("responseSample", null);
            endpoint.put("confidence", "PARTIAL");
            grouped.put(key, endpoint);
        }
        endpoint.put("observations", ((Integer) endpoint.get("observations")) + 1);
        if (item.get("id") != null) {
            @SuppressWarnings("unchecked") List<String> observationIds = (List<String>) endpoint.get("observationIds");
            observationIds.add(item.get("id").toString());
        }
        if (Boolean.TRUE.equals(item.get("hasBody"))) endpoint.put("requestBodyObserved", true);
        Object status = item.get("status");
        if (status instanceof Number) {
            @SuppressWarnings("unchecked")
            Set<Integer> codes = (Set<Integer>) endpoint.get("responseStatusCodes");
            codes.add(((Number) status).intValue());
            endpoint.put("confidence", "OBSERVED");
        }
        if (!contentType.isEmpty()) endpoint.put("responseContentType", contentType);
        if (item.get("responseSample") != null) endpoint.put("responseSample", item.get("responseSample"));
    }

    private List<Map<String, Object>> makeMockRules(List<Map<String, Object>> endpoints) {
        List<Map<String, Object>> rules = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> endpoint : endpoints) {
            Map<String, Object> matcher = new LinkedHashMap<String, Object>();
            matcher.put("method", endpoint.get("method"));
            matcher.put("path", endpoint.get("path"));

            Map<String, Object> rule = new LinkedHashMap<String, Object>();
            rule.put("id", endpoint.get("id"));
            rule.put("match", matcher);
            rule.put("status", firstStatus(endpoint));
            rule.put("contentType", endpoint.get("responseContentType"));
            rule.put("body", endpoint.get("responseSample"));
            rule.put("requiresReview", true);
            rule.put("observationIds", endpoint.get("observationIds"));
            rules.add(rule);
        }
        return rules;
    }

    private AiSessionEntity requireActiveSession(String sessionId) {
        AiSessionEntity session = sessions.findById(sessionId)
                .orElseThrow(() -> new AiWorkbenchService.AiNotFoundException("Exploration session not found"));
        if (!"RUNNING".equals(session.getStatus())) {
            throw new AiWorkbenchService.AiNotFoundException("Exploration session is closed");
        }
        return session;
    }

    private Map<String, Object> draftView(AiApiDraftEntity draft, Map<String, Object> payload) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("id", draft.getId());
        result.put("projectId", draft.getProjectId());
        result.put("sessionId", draft.getSessionId());
        result.put("createdAt", draft.getCreatedAt().toString());
        result.put("endpointCount", ((List<?>) payload.get("endpoints")).size());
        result.put("payload", payload);
        return result;
    }

    private String stableId(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (int index = 0; index < 8; index++) result.append(String.format("%02x", digest[index] & 0xff));
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private List<String> queryKeys(String query) {
        List<String> keys = new ArrayList<String>();
        if (query == null || query.isEmpty()) return keys;
        for (String parameter : query.split("&")) {
            String key = parameter.split("=", 2)[0];
            if (!key.isEmpty() && !keys.contains(key)) keys.add(key);
        }
        return keys;
    }

    private Object firstStatus(Map<String, Object> endpoint) {
        Object values = endpoint.get("responseStatusCodes");
        if (values instanceof Set && !((Set<?>) values).isEmpty()) return ((Set<?>) values).iterator().next();
        return 200;
    }

    private boolean isReadMethod(String method) {
        return "GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method);
    }

    private String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }

    private String withoutQuery(String value) {
        try {
            URL url = new URL(value);
            return url.getProtocol() + "://" + url.getAuthority() + url.getPath();
        } catch (MalformedURLException exception) {
            return "";
        }
    }
}
