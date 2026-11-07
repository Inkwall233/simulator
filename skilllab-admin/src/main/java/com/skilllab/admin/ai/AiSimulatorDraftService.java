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
    private static final String BEHAVIOR_V2 = "skilllab.simulator-behavior.v2";

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
        if (request.containsKey("rules")) {
            Object configured = request.get("rules");
            if (!(configured instanceof List) || ((List<?>) configured).isEmpty()) {
                throw new IllegalArgumentException("Select at least one captured rule");
            }
            java.util.Set<String> capturedIds = new java.util.HashSet<String>();
            for (Map<String, Object> rule : rules) {
                capturedIds.add(stringValue(((Map<?, ?>) rule.get("source")).get("endpointId")));
            }
            rules = new ArrayList<Map<String, Object>>();
            for (Object item : (List<?>) configured) {
                validateMockRule(item, apiDraftId);
                @SuppressWarnings("unchecked")
                Map<String, Object> rule = (Map<String, Object>) item;
                String endpointId = stringValue(((Map<?, ?>) rule.get("source")).get("endpointId"));
                if (!capturedIds.contains(endpointId)) throw new IllegalArgumentException("Rule must reference a captured endpoint");
                for (Object raw : (List<?>) apiPayload.get("mockRules")) {
                    Map<?, ?> captured = (Map<?, ?>) raw;
                    if (endpointId.equals(captured.get("id")) && !captured.get("match").equals(rule.get("match"))) {
                        throw new IllegalArgumentException("Rule matcher must match its captured endpoint");
                    }
                }
                rules.add(rule);
            }
        }
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
        if (request.containsKey("stateModel")) {
            if (!(request.get("stateModel") instanceof Map)) throw new IllegalArgumentException("Invalid state model");
            @SuppressWarnings("unchecked")
            Map<String, Object> configuredState = (Map<String, Object>) request.get("stateModel");
            stateModel = configuredState;
        }

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
            if (SCHEMA_VERSION.equals(payload.get("schemaVersion"))) {
                validatePayload(payload, projectId, entity.getPackageId(), entity.getVersionNumber(),
                        entity.getSourceApiDraftId());
            } else if (BEHAVIOR_V2.equals(payload.get("schemaVersion"))) {
                validateBehaviorV2(payload, entity.getPackageId());
            } else {
                throw new IllegalArgumentException("Unsupported simulator draft schema");
            }
            return draftView(entity, payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored Simulator draft is invalid", exception);
        }
    }

    @Transactional
    public Map<String, Object> createDraftV2(String projectId, Map<String, Object> request) {
        projects.findByIdForUpdate(projectId)
                .orElseThrow(() -> new AiWorkbenchService.AiNotFoundException("Device project not found"));
        Object raw = request.get("behavior");
        if (!(raw instanceof Map)) throw new IllegalArgumentException("Behavior is required");
        @SuppressWarnings("unchecked")
        Map<String, Object> behavior = (Map<String, Object>) raw;
        Object rawPackage = behavior.get("packageVersion");
        if (!(rawPackage instanceof Map)) throw new IllegalArgumentException("Package reference is required");
        String packageId = requiredText(((Map<?, ?>) rawPackage).get("id"), "Package id is required");
        AiPackageEntity packageVersion = packages.findById(packageId)
                .orElseThrow(() -> new AiWorkbenchService.AiNotFoundException("Package version not found"));
        if (!projectId.equals(packageVersion.getProjectId())
                || !packageVersion.getSha256().equalsIgnoreCase(stringValue(((Map<?, ?>) rawPackage).get("sha256")))) {
            throw new IllegalArgumentException("Package version does not match this project");
        }
        validateBehaviorV2(behavior, packageId);
        String sourceId = requiredText(request.get("sourceApiDraftId"), "Source API draft is required");
        AiApiDraftEntity source = apiDrafts.findById(sourceId)
                .orElseThrow(() -> new AiWorkbenchService.AiNotFoundException("API draft not found"));
        if (!projectId.equals(source.getProjectId())) throw new IllegalArgumentException("API draft does not belong to this project");
        int version = simulatorDrafts.findFirstByProjectIdOrderByVersionNumberDesc(projectId)
                .map(item -> item.getVersionNumber() + 1).orElse(1);
        try {
            AiSimulatorDraftEntity entity = simulatorDrafts.save(new AiSimulatorDraftEntity(UUID.randomUUID().toString(),
                    projectId, packageId, sourceId, version, "DRAFT", objectMapper.writeValueAsString(behavior)));
            return draftView(entity, behavior);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize v2 behavior", exception);
        }
    }

    @Transactional
    public Map<String, Object> importV1(String projectId, String draftId) {
        Map<String, Object> source = getDraft(projectId, draftId);
        @SuppressWarnings("unchecked")
        Map<String, Object> v1 = (Map<String, Object>) source.get("payload");
        if (!SCHEMA_VERSION.equals(v1.get("schemaVersion"))) throw new IllegalArgumentException("Source must be v1");
        Map<String, Object> behavior = new LinkedHashMap<String, Object>();
        behavior.put("schemaVersion", BEHAVIOR_V2);
        Map<?, ?> packageSource = (Map<?, ?>) v1.get("packageVersion");
        Map<String, Object> packageReference = new LinkedHashMap<String, Object>();
        packageReference.put("id", packageSource.get("id"));
        packageReference.put("sha256", packageSource.get("sha256"));
        behavior.put("packageVersion", packageReference);
        Map<String, Object> scope = new LinkedHashMap<String, Object>();
        scope.put("name", "Imported v1 capture");
        scope.put("criticalRuleIds", new ArrayList<String>());
        behavior.put("scope", scope);
        Map<?, ?> stateModel = (Map<?, ?>) v1.get("stateModel");
        behavior.put("initialState", stateModel.get("initial"));
        List<Map<String, Object>> rules = new ArrayList<Map<String, Object>>();
        for (Object raw : (List<?>) ((Map<?, ?>) v1.get("apiMocks")).get("rules")) {
            Map<?, ?> old = (Map<?, ?>) raw;
            Map<String, Object> rule = new LinkedHashMap<String, Object>();
            rule.put("id", old.get("id"));
            rule.put("priority", 0);
            rule.put("match", old.get("match"));
            Map<String, Object> response = new LinkedHashMap<String, Object>();
            response.put("status", old.get("status"));
            response.put("contentType", old.get("contentType") == null ? "application/json" : old.get("contentType"));
            response.put("body", old.get("body"));
            rule.put("response", response);
            rule.put("operations", new ArrayList<Object>());
            Map<String, Object> evidence = new LinkedHashMap<String, Object>();
            evidence.put("type", "INFERRED_PENDING");
            evidence.put("observationIds", new ArrayList<String>());
            evidence.put("note", "Imported sample; state transitions require review");
            rule.put("evidence", evidence);
            rules.add(rule);
        }
        behavior.put("rules", rules);
        behavior.put("verificationCases", new ArrayList<Object>());
        Map<String, Object> request = new LinkedHashMap<String, Object>();
        request.put("behavior", behavior);
        request.put("sourceApiDraftId", source.get("sourceApiDraftId"));
        return createDraftV2(projectId, request);
    }

    private void validateBehaviorV2(Map<String, Object> behavior, String packageId) {
        onlyBehaviorKeys(behavior, "schemaVersion", "packageVersion", "scope", "initialState", "rules", "verificationCases");
        if (!BEHAVIOR_V2.equals(behavior.get("schemaVersion"))
                || !(behavior.get("packageVersion") instanceof Map)
                || !(behavior.get("initialState") instanceof Map)
                || !(behavior.get("scope") instanceof Map)
                || !(behavior.get("verificationCases") instanceof List)
                || !(behavior.get("rules") instanceof List)
                || ((List<?>) behavior.get("rules")).isEmpty()
                || ((List<?>) behavior.get("rules")).size() > 200
                || !packageId.equals(((Map<?, ?>) behavior.get("packageVersion")).get("id"))) {
            throw new IllegalArgumentException("Invalid v2 behavior");
        }
        try {
            if (objectMapper.writeValueAsBytes(behavior).length > 1024 * 1024) throw new IllegalArgumentException("Behavior size limit exceeded");
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid behavior JSON", exception);
        }
        validateBehaviorNode(behavior, 0);
        onlyBehaviorKeys((Map<?, ?>) behavior.get("packageVersion"), "id", "sha256");
        if (!stringValue(((Map<?, ?>) behavior.get("packageVersion")).get("sha256")).matches("(?i)^[a-f0-9]{64}$")) {
            throw new IllegalArgumentException("Invalid package digest");
        }
        onlyBehaviorKeys((Map<?, ?>) behavior.get("scope"), "name", "criticalRuleIds");
        java.util.Set<String> keys = new java.util.HashSet<String>();
        for (Object raw : (List<?>) behavior.get("rules")) {
            if (!(raw instanceof Map)) throw new IllegalArgumentException("Invalid v2 rule");
            Map<?, ?> rule = (Map<?, ?>) raw;
            onlyBehaviorKeys(rule, "id", "priority", "match", "when", "response", "operations", "evidence");
            if (!(rule.get("priority") instanceof Integer) || !(rule.get("match") instanceof Map)
                    || !(rule.get("response") instanceof Map) || !(rule.get("operations") instanceof List)) {
                throw new IllegalArgumentException("Invalid v2 rule");
            }
            Map<?, ?> match = (Map<?, ?>) rule.get("match");
            onlyBehaviorKeys(match, "method", "path");
            String path = stringValue(match.get("path"));
            if (!stringValue(match.get("method")).matches("^[A-Z]+$") || !path.startsWith("/")
                    || path.contains("%") || path.contains("\\") || path.contains("?")
                    || path.contains("#") || java.util.Arrays.asList(path.split("/")).contains("..")
                    || java.util.Arrays.asList(path.split("/")).contains(".")) {
                throw new IllegalArgumentException("Invalid request path");
            }
            String key = rule.get("priority") + " " + match.get("method") + " " + path;
            if (!keys.add(key)) throw new IllegalArgumentException("Ambiguous v2 rules");
            Map<?, ?> response = (Map<?, ?>) rule.get("response");
            onlyBehaviorKeys(response, "status", "contentType", "body", "headers");
            if (!(response.get("status") instanceof Integer) || ((Integer) response.get("status")) < 200
                    || ((Integer) response.get("status")) > 599 || !response.containsKey("body")
                    || (java.util.Arrays.asList(204, 205, 304).contains(response.get("status")) && response.get("body") != null)) {
                throw new IllegalArgumentException("Invalid v2 response");
            }
            if (rule.containsKey("when")) validateCondition(rule.get("when"), 0);
            if (!(rule.get("evidence") instanceof Map)) throw new IllegalArgumentException("Invalid evidence");
            Map<?, ?> evidence = (Map<?, ?>) rule.get("evidence");
            onlyBehaviorKeys(evidence, "type", "observationIds", "note");
            if (!java.util.Arrays.asList("CAPTURED", "INFERRED_PENDING", "INFERRED_CONFIRMED").contains(evidence.get("type"))
                    || !(evidence.get("observationIds") instanceof List)
                    || ("CAPTURED".equals(evidence.get("type")) && ((List<?>) evidence.get("observationIds")).isEmpty())
                    || (!"CAPTURED".equals(evidence.get("type")) && !hasText(evidence.get("note")))) {
                throw new IllegalArgumentException("Invalid evidence");
            }
            for (Object rawOp : (List<?>) rule.get("operations")) {
                if (!(rawOp instanceof Map)) throw new IllegalArgumentException("Invalid state operation");
                Map<?, ?> op = (Map<?, ?>) rawOp;
                onlyBehaviorKeys(op, "operation", "path", "requestPath", "value");
                if (!java.util.Arrays.asList("SET", "SET_FROM_REQUEST", "MERGE", "INCREMENT").contains(op.get("operation"))) {
                    throw new IllegalArgumentException("Invalid state operation");
                }
                validateStatePath(op.get("path"));
                if ("SET_FROM_REQUEST".equals(op.get("operation"))) validateStatePath(op.get("requestPath"));
            }
        }
    }

    private void onlyBehaviorKeys(Map<?, ?> map, String... allowed) {
        java.util.Set<String> names = new java.util.HashSet<String>(java.util.Arrays.asList(allowed));
        for (Object key : map.keySet()) {
            if (!names.contains(key)) throw new IllegalArgumentException("Invalid v2 field: " + key);
        }
    }

    private void validateCondition(Object raw, int depth) {
        if (!(raw instanceof Map) || depth > 8) throw new IllegalArgumentException("Invalid condition");
        Map<?, ?> condition = (Map<?, ?>) raw;
        String op = stringValue(condition.get("op"));
        if ("all".equals(op) || "any".equals(op)) {
            onlyBehaviorKeys(condition, "op", "items");
            if (!(condition.get("items") instanceof List) || ((List<?>) condition.get("items")).isEmpty()) throw new IllegalArgumentException("Invalid condition");
            for (Object item : (List<?>) condition.get("items")) validateCondition(item, depth + 1);
        } else if ("not".equals(op)) {
            onlyBehaviorKeys(condition, "op", "item");
            validateCondition(condition.get("item"), depth + 1);
        } else if (java.util.Arrays.asList("equal", "exists", "type", "in", "range").contains(op)) {
            onlyBehaviorKeys(condition, "op", "left", "value");
            if (!(condition.get("left") instanceof Map)) throw new IllegalArgumentException("Invalid condition");
            Map<?, ?> left = (Map<?, ?>) condition.get("left");
            onlyBehaviorKeys(left, "source", "path");
            if (!java.util.Arrays.asList("state", "body", "query").contains(left.get("source"))) throw new IllegalArgumentException("Invalid condition source");
            validateStatePath(left.get("path"));
            if (!"exists".equals(op) && !condition.containsKey("value")) throw new IllegalArgumentException("Invalid condition value");
            if ("type".equals(op) && !java.util.Arrays.asList("string", "number", "boolean", "object", "array", "null").contains(condition.get("value"))) {
                throw new IllegalArgumentException("Invalid condition type");
            }
        } else throw new IllegalArgumentException("Unknown condition operator");
    }

    private void validateBehaviorNode(Object value, int depth) {
        if (depth > 16) throw new IllegalArgumentException("Behavior nesting depth exceeded");
        if (value instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (java.util.Arrays.asList("__proto__", "prototype", "constructor").contains(entry.getKey())) throw new IllegalArgumentException("Unsafe object key");
                validateBehaviorNode(entry.getValue(), depth + 1);
            }
        } else if (value instanceof List) {
            for (Object item : (List<?>) value) validateBehaviorNode(item, depth + 1);
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
            if (status < 200 || status > 599 || !path.startsWith("/")) {
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
        java.util.Set<String> ruleIds = new java.util.HashSet<String>();
        for (Object rawRule : (List<?>) rulesValue) {
            if (!ruleIds.add(stringValue(((Map<?, ?>) rawRule).get("id")))) {
                throw new IllegalArgumentException("Duplicate Mock rule id");
            }
        }
        for (Object raw : (List<?>) stateModel.get("transitions")) {
            if (!(raw instanceof Map)) throw new IllegalArgumentException("Invalid state transition");
            Map<?, ?> transition = (Map<?, ?>) raw;
            String operation = stringValue(transition.get("operation"));
            if (!ruleIds.contains(stringValue(transition.get("onRuleId")))
                    || !java.util.Arrays.asList("SET", "MERGE", "INCREMENT", "SET_FROM_REQUEST").contains(operation)) {
                throw new IllegalArgumentException("Invalid state transition rule or operation");
            }
            validateStatePath(transition.get("path"));
            if ("SET_FROM_REQUEST".equals(operation)) validateStatePath(transition.get("requestPath"));
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
                || !(status instanceof Number) || ((Number) status).doubleValue() != ((Number) status).intValue()
                || ((Number) status).intValue() < 200
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

    private void validateStatePath(Object value) {
        String path = stringValue(value);
        if (!path.matches("^[A-Za-z0-9_-]+(\\.[A-Za-z0-9_-]+)*$")) throw new IllegalArgumentException("Invalid state path");
        for (String part : path.split("\\.")) {
            if (java.util.Arrays.asList("__proto__", "prototype", "constructor").contains(part)) {
                throw new IllegalArgumentException("Unsafe state path");
            }
        }
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
