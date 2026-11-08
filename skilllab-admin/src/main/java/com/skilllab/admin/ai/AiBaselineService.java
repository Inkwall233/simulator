package com.skilllab.admin.ai;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AiBaselineService {
    private final AiDeviceProjectRepository projects;
    private final AiPackageRepository packages;
    private final AiApiDraftRepository drafts;
    private final AiEvidenceRepository evidence;
    private final AiBaselineRepository baselines;
    private final ObjectMapper mapper;

    public AiBaselineService(AiDeviceProjectRepository projects, AiPackageRepository packages,
            AiApiDraftRepository drafts, AiEvidenceRepository evidence, AiBaselineRepository baselines, ObjectMapper mapper) {
        this.projects = projects; this.packages = packages; this.drafts = drafts;
        this.evidence = evidence; this.baselines = baselines; this.mapper = mapper;
    }

    @Transactional
    public Map<String, Object> createBaseline(String projectId, String packageId, String apiDraftId,
            String sessionId, Map<String, Object> scenario, String creatorId) {
        if (!projects.existsById(projectId)) throw new AiWorkbenchService.AiNotFoundException("Project not found");
        AiPackageEntity archive = packages.findById(packageId)
                .orElseThrow(() -> new AiWorkbenchService.AiNotFoundException("Package not found"));
        AiApiDraftEntity draft = drafts.findById(apiDraftId)
                .orElseThrow(() -> new AiWorkbenchService.AiNotFoundException("API draft not found"));
        if (!projectId.equals(archive.getProjectId()) || !projectId.equals(draft.getProjectId())
                || !sessionId.equals(draft.getSessionId())) throw new IllegalArgumentException("Baseline sources do not match");
        Object rawSteps = scenario.get("steps");
        if (!(rawSteps instanceof List) || ((List<?>) rawSteps).isEmpty() || ((List<?>) rawSteps).size() > 50
                || scenario.keySet().stream().anyMatch(key -> !"steps".equals(key))) {
            throw new IllegalArgumentException("Baseline requires ordered steps");
        }
        List<Map<String, Object>> steps = new ArrayList<>();
        for (Object raw : (List<?>) rawSteps) {
            if (!(raw instanceof Map)) throw new IllegalArgumentException("Invalid baseline step");
            Map<?, ?> step = (Map<?, ?>) raw;
            onlyKeys(step, "observationId", "action", "checkpoint", "comparisons");
            String id = text(step.get("observationId"), 36);
            AiEvidenceEntity observation = evidence.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("Observation not found"));
            if (!projectId.equals(observation.getProjectId()) || !sessionId.equals(observation.getSessionId()))
                throw new IllegalArgumentException("Observation belongs to another capture");
            Map<String, Object> captured = parse(observation.getPayloadJson());
            if (Boolean.TRUE.equals(captured.get("truncated")) || Boolean.TRUE.equals(captured.get("redacted"))
                    || (!Arrays.asList("GET", "HEAD").contains(captured.get("method"))
                        && !Boolean.TRUE.equals(captured.get("bodyUsable"))))
                throw new IllegalArgumentException("Incomplete observation cannot be a baseline");
            Map<String, Object> frozen = new LinkedHashMap<>();
            frozen.put("observationId", id);
            frozen.put("sequence", observation.getSequence());
            frozen.put("observation", captured);
            frozen.put("action", text(step.get("action"), 200));
            frozen.put("checkpoint", checkpoint(step.get("checkpoint")));
            frozen.put("comparisons", comparisons(step.get("comparisons")));
            steps.add(frozen);
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("steps", steps);
        try {
            String json = mapper.writeValueAsString(snapshot);
            AiBaselineEntity saved = baselines.save(new AiBaselineEntity(UUID.randomUUID().toString(), projectId,
                    packageId, archive.getSha256(), apiDraftId, sessionId, creatorId, sha256(json), json));
            return view(saved);
        } catch (JsonProcessingException exception) { throw new IllegalStateException("Could not freeze baseline", exception); }
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getBaseline(String projectId, String baselineId, String creatorId) {
        AiBaselineEntity baseline = baselines.findByIdAndProjectId(baselineId, projectId)
                .orElseThrow(() -> new AiWorkbenchService.AiNotFoundException("Baseline not found"));
        if (!creatorId.equals(baseline.getCreatorId())) throw new SecurityException("Baseline belongs to another creator");
        return view(baseline);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listBaselines(String projectId, String creatorId) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (AiBaselineEntity baseline : baselines.findAllByProjectIdOrderByCreatedAtDesc(projectId)) {
            if (creatorId.equals(baseline.getCreatorId())) result.add(view(baseline));
        }
        return result;
    }

    private Map<String, Object> checkpoint(Object value) {
        if (value == null) return Collections.emptyMap();
        if (!(value instanceof Map)) throw new IllegalArgumentException("Invalid checkpoint");
        Map<?, ?> input = (Map<?, ?>) value;
        onlyKeys(input, "kind", "value");
        String kind = text(input.get("kind"), 32);
        if (!Arrays.asList("TEXT_CONTAINS", "URL_EQUALS", "ELEMENT_VISIBLE").contains(kind))
            throw new IllegalArgumentException("Unsupported checkpoint");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kind", kind);
        result.put("value", text(input.get("value"), 500));
        return result;
    }

    private List<Map<String, Object>> comparisons(Object value) {
        if (!(value instanceof List) || ((List<?>) value).size() > 50) throw new IllegalArgumentException("Invalid comparisons");
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object raw : (List<?>) value) {
            if (!(raw instanceof Map)) throw new IllegalArgumentException("Invalid comparison");
            Map<?, ?> input = (Map<?, ?>) raw;
            onlyKeys(input, "path", "policy", "expected", "reason", "minimum", "maximum");
            String path = text(input.get("path"), 100);
            if (!path.matches("[A-Za-z0-9_-]+(\\.[A-Za-z0-9_-]+)*")
                    || Arrays.asList("__proto__", "constructor", "prototype").stream().anyMatch(path::contains))
                throw new IllegalArgumentException("Unsafe comparison path");
            String policy = text(input.get("policy"), 20);
            if (!Arrays.asList("EXACT", "TYPE", "RANGE", "STABLE_MAP", "IGNORE").contains(policy))
                throw new IllegalArgumentException("Unsupported comparison policy");
            if ("IGNORE".equals(policy) && !input.containsKey("reason")) throw new IllegalArgumentException("Ignore requires confirmation reason");
            if ("RANGE".equals(policy) && (!(input.get("minimum") instanceof Number) || !(input.get("maximum") instanceof Number)))
                throw new IllegalArgumentException("Range bounds required");
            Map<String, Object> item = new LinkedHashMap<>();
            input.forEach((key, field) -> item.put(String.valueOf(key), field));
            result.add(item);
        }
        return result;
    }

    private Map<String, Object> view(AiBaselineEntity entity) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", entity.getId()); result.put("projectId", entity.getProjectId());
        result.put("packageId", entity.getPackageId()); result.put("packageSha256", entity.getPackageSha256());
        result.put("apiDraftId", entity.getApiDraftId()); result.put("sessionId", entity.getSessionId());
        result.put("snapshotSha256", entity.getSnapshotSha256()); result.put("createdAt", entity.getCreatedAt());
        result.put("scenario", parse(entity.getSnapshotJson()));
        return result;
    }

    private Map<String, Object> parse(String json) {
        try { return mapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { }); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("Stored baseline data is invalid", exception); }
    }
    private String text(Object value, int max) {
        if (!(value instanceof String) || ((String) value).trim().isEmpty() || ((String) value).length() > max)
            throw new IllegalArgumentException("Invalid baseline field");
        return (String) value;
    }
    private void onlyKeys(Map<?, ?> value, String... allowed) {
        if (value.keySet().stream().anyMatch(key -> !Arrays.asList(allowed).contains(key)))
            throw new IllegalArgumentException("Unknown baseline field");
    }
    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte item : digest) hex.append(String.format("%02x", item & 0xff));
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}
