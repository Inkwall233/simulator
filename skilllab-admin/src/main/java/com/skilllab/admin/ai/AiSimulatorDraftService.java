package com.skilllab.admin.ai;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AiSimulatorDraftService {

    private static final String SCHEMA_VERSION = "skilllab.simulator-draft.v1";

    private final AiDeviceProjectRepository projects;
    private final AiPackageRepository packages;
    private final AiApiDraftRepository apiDrafts;
    private final AiSimulatorDraftRepository simulatorDrafts;
    private final AiSessionRepository sessions;
    private final ObjectMapper objectMapper;

    public AiSimulatorDraftService(AiDeviceProjectRepository projects,
            AiPackageRepository packages,
            AiApiDraftRepository apiDrafts,
            AiSimulatorDraftRepository simulatorDrafts,
            AiSessionRepository sessions,
            ObjectMapper objectMapper) {
        this.projects = projects;
        this.packages = packages;
        this.apiDrafts = apiDrafts;
        this.simulatorDrafts = simulatorDrafts;
        this.sessions = sessions;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Map<String, Object> createDraft(String projectId, Map<String, Object> request) {
        AiDeviceProjectEntity project = projects.findByIdForUpdate(projectId)
                .orElseThrow(() -> new AiWorkbenchService.AiNotFoundException("Device project not found"));
        String packageId = requiredText(request.get("packageId"), "Package version is required");
        String apiDraftId = requiredText(request.get("apiDraftId"), "API draft is required");

        AiPackageEntity packageVersion = packages.findById(packageId)
                .orElseThrow(() -> new AiWorkbenchService.AiNotFoundException("Package version not found"));
        if (!projectId.equals(packageVersion.getProjectId())) {
            throw new IllegalArgumentException("Package version does not belong to this project");
        }

        AiApiDraftEntity sourceDraft = apiDrafts.findById(apiDraftId)
                .orElseThrow(() -> new AiWorkbenchService.AiNotFoundException("API draft not found"));
        if (!projectId.equals(sourceDraft.getProjectId())) {
            throw new IllegalArgumentException("API draft does not belong to this project");
        }
        AiSessionEntity sourceSession = sessions.findById(sourceDraft.getSessionId())
                .orElseThrow(() -> new AiWorkbenchService.AiNotFoundException("Source session not found"));
        if (!projectId.equals(sourceSession.getProjectId())) {
            throw new IllegalArgumentException("Source session does not belong to this project");
        }

        Map<String, Object> apiPayload = readApiDraft(sourceDraft);
        List<Map<String, Object>> rules = copyReviewedRules(apiPayload, apiDraftId);
        Optional<AiSimulatorDraftEntity> latest = simulatorDrafts.findFirstByProjectIdOrderByVersionNumberDesc(projectId);
        int versionNumber = latest.isPresent() ? latest.get().getVersionNumber() + 1 : 1;
        String draftId = UUID.randomUUID().toString();
        String createdAt = Instant.now().toString();

        Map<String, Object> packageView = new LinkedHashMap<String, Object>();
        packageView.put("id", packageVersion.getId());
        packageView.put("filename", packageVersion.getFilename());
        packageView.put("sha256", packageVersion.getSha256());
        packageView.put("sizeBytes", packageVersion.getSizeBytes());

        Map<String, Object> source = new LinkedHashMap<String, Object>();
        source.put("type", "CAPTURED_TRAFFIC");
        source.put("apiDraftId", apiDraftId);
        source.put("sessionId", sourceSession.getId());

        Map<String, Object> apiMocks = new LinkedHashMap<String, Object>();
        apiMocks.put("rules", rules);

        Map<String, Object> stateModel = new LinkedHashMap<String, Object>();
        stateModel.put("initial", new LinkedHashMap<String, Object>());
        stateModel.put("transitions", new ArrayList<Object>());

        Map<String, Object> review = new LinkedHashMap<String, Object>();
        review.put("status", "NEEDS_REVIEW");
        review.put("notes", new ArrayList<String>());

        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("schemaVersion", SCHEMA_VERSION);
        payload.put("draftId", draftId);
        payload.put("projectId", project.getId());
        payload.put("draftVersion", versionNumber);
        payload.put("createdAt", createdAt);
        payload.put("status", "DRAFT");
        payload.put("packageVersion", packageView);
        payload.put("source", source);
        payload.put("apiMocks", apiMocks);
        payload.put("stateModel", stateModel);
        payload.put("workflows", new ArrayList<Object>());
        payload.put("playbackAdapters", new ArrayList<Object>());
        payload.put("review", review);
        validatePayload(payload, projectId, packageVersion.getId(), versionNumber, apiDraftId);

        try {
            AiSimulatorDraftEntity entity = simulatorDrafts.save(new AiSimulatorDraftEntity(
                    draftId, projectId, packageVersion.getId(), apiDraftId, versionNumber,
                    "DRAFT", objectMapper.writeValueAsString(payload)));
            return draftView(entity, payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize Simulator draft", exception);
        }
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listDrafts(String projectId) {
        if (!projects.existsById(projectId)) {
            throw new AiWorkbenchService.AiNotFoundException("Device project not found");
        }
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (AiSimulatorDraftEntity entity : simulatorDrafts.findAllByProjectIdOrderByVersionNumberDesc(projectId)) {
            result.add(summaryView(entity));
        }
        return result;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getDraft(String projectId, String draftId) {
        AiSimulatorDraftEntity entity = simulatorDrafts.findByIdAndProjectId(draftId, projectId)
                .orElseThrow(() -> new AiWorkbenchService.AiNotFoundException("Simulator draft not found"));
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = objectMapper.readValue(entity.getPayloadJson(), Map.class);
            validatePayload(payload, projectId, entity.getPackageId(), entity.getVersionNumber(),
                    entity.getSourceApiDraftId());
            return draftView(entity, payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored Simulator draft is invalid", exception);
        }
    }

    private Map<String, Object> readApiDraft(AiApiDraftEntity entity) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = objectMapper.readValue(entity.getPayloadJson(), Map.class);
            if (!"skilllab.device-api-draft.v1".equals(payload.get("schemaVersion"))) {
                throw new IllegalArgumentException("Unsupported API draft schema version");
            }
            return payload;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Source API draft is invalid", exception);
        }
    }

    private List<Map<String, Object>> copyReviewedRules(Map<String, Object> apiPayload, String apiDraftId) {
        Object rawRules = apiPayload.get("mockRules");
        if (!(rawRules instanceof List) || ((List<?>) rawRules).isEmpty()) {
            throw new IllegalArgumentException("Source API draft contains no Mock rules");
        }
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (Object rawRule : (List<?>) rawRules) {
            if (!(rawRule instanceof Map)) {
                throw new IllegalArgumentException("Source API draft contains an invalid Mock rule");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> sourceRule = (Map<String, Object>) rawRule;
            Object rawMatch = sourceRule.get("match");
            if (!(rawMatch instanceof Map)) {
                throw new IllegalArgumentException("Source Mock rule is missing a request matcher");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> sourceMatch = (Map<String, Object>) rawMatch;
            String method = requiredText(sourceMatch.get("method"), "Mock rule method is required").toUpperCase();
            String path = requiredText(sourceMatch.get("path"), "Mock rule path is required");
            Object rawStatus = sourceRule.get("status");
            if (!(rawStatus instanceof Number)) {
                throw new IllegalArgumentException("Mock rule status must be numeric");
            }
            int status = ((Number) rawStatus).intValue();
            if (status < 100 || status > 599 || !path.startsWith("/")) {
                throw new IllegalArgumentException("Mock rule status or path is invalid");
            }

            Map<String, Object> match = new LinkedHashMap<String, Object>();
            match.put("method", method);
            match.put("path", path);
            Map<String, Object> source = new LinkedHashMap<String, Object>();
            source.put("apiDraftId", apiDraftId);
            source.put("endpointId", requiredText(sourceRule.get("id"), "Mock rule id is required"));

            Map<String, Object> rule = new LinkedHashMap<String, Object>();
            rule.put("id", UUID.randomUUID().toString());
            rule.put("match", match);
            rule.put("status", status);
            rule.put("contentType", sourceRule.get("contentType"));
            rule.put("body", sourceRule.get("body"));
            rule.put("requiresReview", true);
            rule.put("source", source);
            result.add(rule);
        }
        return result;
    }

    private void validatePayload(Map<String, Object> payload, String projectId, String packageId,
            int versionNumber, String apiDraftId) {
        if (!SCHEMA_VERSION.equals(payload.get("schemaVersion"))
                || !hasText(payload.get("draftId"))
                || !projectId.equals(payload.get("projectId"))
                || !Integer.valueOf(versionNumber).equals(payload.get("draftVersion"))
                || !"DRAFT".equals(payload.get("status"))) {
            throw new IllegalArgumentException("Simulator draft does not match its version record");
        }
        try {
            Instant.parse(requiredText(payload.get("createdAt"), "Simulator draft timestamp is required"));
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("Simulator draft timestamp is invalid", exception);
        }
        Object packageValue = payload.get("packageVersion");
        if (!(packageValue instanceof Map)) {
            throw new IllegalArgumentException("Simulator draft package reference is invalid");
        }
        Map<?, ?> packageMap = (Map<?, ?>) packageValue;
        if (!packageId.equals(packageMap.get("id"))
                || !hasText(packageMap.get("filename"))
                || !stringValue(packageMap.get("sha256")).matches("(?i)^[a-f0-9]{64}$")
                || !(packageMap.get("sizeBytes") instanceof Number)
                || ((Number) packageMap.get("sizeBytes")).longValue() < 1) {
            throw new IllegalArgumentException("Simulator draft package metadata is invalid");
        }
        Object sourceValue = payload.get("source");
        if (!(sourceValue instanceof Map)) {
            throw new IllegalArgumentException("Simulator draft source is invalid");
        }
        Map<?, ?> source = (Map<?, ?>) sourceValue;
        if (!"CAPTURED_TRAFFIC".equals(source.get("type"))
                || !apiDraftId.equals(source.get("apiDraftId"))
                || !hasText(source.get("sessionId"))) {
            throw new IllegalArgumentException("Simulator draft source reference is invalid");
        }
        Object apiMocksValue = payload.get("apiMocks");
        if (!(apiMocksValue instanceof Map)) {
            throw new IllegalArgumentException("Simulator draft Mock rules are invalid");
        }
        Object rulesValue = ((Map<?, ?>) apiMocksValue).get("rules");
        if (!(rulesValue instanceof List) || ((List<?>) rulesValue).isEmpty()) {
            throw new IllegalArgumentException("Simulator draft must contain at least one Mock rule");
        }
        for (Object rawRule : (List<?>) rulesValue) validateMockRule(rawRule, apiDraftId);
        Object workflowValue = payload.get("workflows");
        Object playbackValue = payload.get("playbackAdapters");
        Object stateValue = payload.get("stateModel");
        if (!(workflowValue instanceof List) || !(playbackValue instanceof List) || !(stateValue instanceof Map)) {
            throw new IllegalArgumentException("Simulator draft runtime sections are invalid");
        }
        Map<?, ?> stateModel = (Map<?, ?>) stateValue;
        if (!(stateModel.get("initial") instanceof Map) || !(stateModel.get("transitions") instanceof List)) {
            throw new IllegalArgumentException("Simulator draft state model is invalid");
        }
        Object reviewValue = payload.get("review");
        if (!(reviewValue instanceof Map)) throw new IllegalArgumentException("Simulator draft review state is invalid");
        Map<?, ?> review = (Map<?, ?>) reviewValue;
        if (!"NEEDS_REVIEW".equals(review.get("status")) || !(review.get("notes") instanceof List)) {
            throw new IllegalArgumentException("New Simulator drafts must remain unreviewed");
        }
    }

    private void validateMockRule(Object rawRule, String apiDraftId) {
        if (!(rawRule instanceof Map)) throw new IllegalArgumentException("Simulator draft contains an invalid Mock rule");
        Map<?, ?> rule = (Map<?, ?>) rawRule;
        Object matchValue = rule.get("match");
        Object sourceValue = rule.get("source");
        if (!(matchValue instanceof Map) || !(sourceValue instanceof Map)) {
            throw new IllegalArgumentException("Simulator draft Mock rule is missing match or source metadata");
        }
        Map<?, ?> match = (Map<?, ?>) matchValue;
        Map<?, ?> source = (Map<?, ?>) sourceValue;
        Object status = rule.get("status");
        String method = stringValue(match.get("method"));
        String path = stringValue(match.get("path"));
        if (!hasText(rule.get("id")) || !method.matches("^[A-Z]+$") || !path.startsWith("/")
                || !(status instanceof Number) || ((Number) status).intValue() < 100
                || ((Number) status).intValue() > 599 || !Boolean.TRUE.equals(rule.get("requiresReview"))
                || !apiDraftId.equals(source.get("apiDraftId")) || !hasText(source.get("endpointId"))
                || !rule.containsKey("body")
                || (rule.get("contentType") != null && !(rule.get("contentType") instanceof String))) {
            throw new IllegalArgumentException("Simulator draft Mock rule does not match the v1 contract");
        }
    }

    private boolean hasText(Object value) {
        return value != null && !value.toString().trim().isEmpty();
    }

    private String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }

    private Map<String, Object> draftView(AiSimulatorDraftEntity entity, Map<String, Object> payload) {
        Map<String, Object> result = summaryView(entity);
        result.put("payload", payload);
        return result;
    }

    private Map<String, Object> summaryView(AiSimulatorDraftEntity entity) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("id", entity.getId());
        result.put("projectId", entity.getProjectId());
        result.put("packageId", entity.getPackageId());
        result.put("sourceApiDraftId", entity.getSourceApiDraftId());
        result.put("versionNumber", entity.getVersionNumber());
        result.put("status", entity.getStatus());
        result.put("createdAt", entity.getCreatedAt().toString());
        return result;
    }

    private String requiredText(Object value, String message) {
        if (value == null || value.toString().trim().isEmpty()) throw new IllegalArgumentException(message);
        return value.toString().trim();
    }
}
