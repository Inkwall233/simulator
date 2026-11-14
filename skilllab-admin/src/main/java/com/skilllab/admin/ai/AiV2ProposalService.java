package com.skilllab.admin.ai;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

@Service
public class AiV2ProposalService {
    private final AiPackageRepository packages;
    private final AiApiDraftRepository drafts;
    private final AiBaselineService baselines;
    private final AiSimulatorDraftRepository revisions;
    private final AiWorkerClient worker;
    private final ObjectMapper mapper;

    public AiV2ProposalService(AiPackageRepository packages, AiApiDraftRepository drafts,
            AiBaselineService baselines, AiSimulatorDraftRepository revisions, AiWorkerClient worker, ObjectMapper mapper) {
        this.packages = packages; this.drafts = drafts; this.baselines = baselines;
        this.revisions = revisions; this.worker = worker; this.mapper = mapper;
    }

    public Map<String, Object> proposeV2(String projectId, String packageId, String apiDraftId,
            String baselineId, String currentDraftId, String goal, String creatorId) {
        if (goal == null || goal.trim().isEmpty() || goal.length() > 4000) throw new IllegalArgumentException("Goal is required");
        Map<String, Object> input = source(projectId, packageId, apiDraftId, baselineId, currentDraftId, creatorId);
        input.put("goal", goal.trim());
        Map<String, Object> proposal = worker.post("/internal/v2-proposals", input);
        if (!fingerprint(projectId, packageId, apiDraftId, baselineId, currentDraftId, creatorId)
                .equals(fingerprintOf(input))) throw new IllegalStateException("Proposal source changed");
        proposal.put("sourceFingerprint", fingerprintOf(input));
        proposal.put("apiDraftId", apiDraftId);
        proposal.put("baselineId", baselineId);
        return proposal;
    }

    public String fingerprint(String projectId, String packageId, String apiDraftId,
            String baselineId, String currentDraftId, String creatorId) {
        return fingerprintOf(source(projectId, packageId, apiDraftId, baselineId, currentDraftId, creatorId));
    }

    private Map<String, Object> source(String projectId, String packageId, String apiDraftId,
            String baselineId, String currentDraftId, String creatorId) {
        AiPackageEntity archive = packages.findById(packageId)
                .orElseThrow(() -> new AiWorkbenchService.AiNotFoundException("Package not found"));
        AiApiDraftEntity draft = drafts.findById(apiDraftId)
                .orElseThrow(() -> new AiWorkbenchService.AiNotFoundException("API draft not found"));
        if (!projectId.equals(archive.getProjectId()) || !projectId.equals(draft.getProjectId()))
            throw new SecurityException("Proposal source belongs to another project");
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("projectId", projectId);
        input.put("packageVersion", packageReference(archive));
        input.put("apiDraftId", apiDraftId);
        input.put("apiDraftHash", sha256(draft.getPayloadJson()));
        Map<String, Object> capture = new LinkedHashMap<>();
        List<Map<String, Object>> safeRules = new ArrayList<>();
        Object rawRules = parse(draft.getPayloadJson()).get("mockRules");
        if (!(rawRules instanceof List)) throw new IllegalArgumentException("API draft has no rules");
        for (Object raw : (List<?>) rawRules) {
            if (!(raw instanceof Map)) throw new IllegalArgumentException("Invalid API rule");
            Map<?, ?> rule = (Map<?, ?>) raw;
            Map<String, Object> safe = new LinkedHashMap<>();
            safe.put("id", rule.get("id")); safe.put("match", rule.get("match"));
            safeRules.add(safe);
        }
        capture.put("mockRules", safeRules);
        input.put("capture", capture);
        List<Map<String, Object>> observations = new ArrayList<>();
        if (baselineId != null && !baselineId.isEmpty()) {
            Map<String, Object> baseline = baselines.getBaseline(projectId, baselineId, creatorId);
            if (!packageId.equals(baseline.get("packageId")) || !apiDraftId.equals(baseline.get("apiDraftId"))
                    || !draft.getSessionId().equals(baseline.get("sessionId")))
                throw new IllegalArgumentException("Baseline does not match proposal source");
            input.put("baselineSha256", baseline.get("snapshotSha256"));
            Map<?, ?> scenario = (Map<?, ?>) baseline.get("scenario");
            for (Object raw : (List<?>) scenario.get("steps")) {
                Map<?, ?> step = (Map<?, ?>) raw;
                @SuppressWarnings("unchecked") Map<String, Object> item = new LinkedHashMap<>((Map<String, Object>) step.get("observation"));
                item.put("id", step.get("observationId"));
                observations.add(item);
            }
        }
        input.put("observations", observations);
        if (currentDraftId != null && !currentDraftId.isEmpty()) {
            AiSimulatorDraftEntity current = revisions.findByIdAndProjectId(currentDraftId, projectId)
                    .orElseThrow(() -> new AiWorkbenchService.AiNotFoundException("Current revision not found"));
            input.put("currentDraftHash", sha256(current.getPayloadJson()));
        }
        return input;
    }

    private Map<String, Object> packageReference(AiPackageEntity archive) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", archive.getId()); result.put("sha256", archive.getSha256());
        return result;
    }

    private String fingerprintOf(Map<String, Object> input) {
        try {
            Map<String, Object> source = new LinkedHashMap<>(input);
            source.remove("goal");
            return sha256(mapper.writeValueAsString(source));
        } catch (JsonProcessingException exception) { throw new IllegalStateException("Invalid proposal source", exception); }
    }

    private Map<String, Object> parse(String json) {
        try { return mapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { }); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("Invalid API draft", exception); }
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte item : digest) result.append(String.format("%02x", item & 0xff));
            return result.toString();
        } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}
