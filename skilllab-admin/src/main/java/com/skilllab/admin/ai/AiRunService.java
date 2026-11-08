package com.skilllab.admin.ai;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AiRunService {
    private final AiSimulatorVersionRepository versions;
    private final AiRunRepository runs;
    private final AiWorkerClient worker;
    private final AiSimulatorDraftRepository drafts;
    private final AiPackageRepository packages;
    private final AiPreviewService previews;
    private final ObjectMapper mapper;
    private final String runOriginTemplate;
    private final long heartbeatMs;
    private final long idleMs;
    private final long maximumMs;

    public AiRunService(AiSimulatorVersionRepository versions, AiRunRepository runs, AiWorkerClient worker,
            AiSimulatorDraftRepository drafts,
            AiPackageRepository packages, AiPreviewService previews,
            ObjectMapper mapper, @Value("${skilllab.ai.run-origin-template:http://{runId}.localhost:3001}") String runOriginTemplate,
            @Value("${skilllab.ai.run-heartbeat-ms:30000}") long heartbeatMs,
            @Value("${skilllab.ai.run-idle-ms:600000}") long idleMs,
            @Value("${skilllab.ai.run-maximum-ms:28800000}") long maximumMs) {
        this.versions = versions; this.runs = runs; this.worker = worker; this.drafts = drafts;
        this.packages = packages; this.previews = previews; this.mapper = mapper;
        this.runOriginTemplate = runOriginTemplate; this.heartbeatMs = heartbeatMs; this.idleMs = idleMs; this.maximumMs = maximumMs;
    }

    @Transactional
    public Map<String, Object> registerCandidate(String projectId, String draftId, String creatorId) {
        AiSimulatorDraftEntity draft = drafts.findByIdAndProjectId(draftId, projectId)
                .orElseThrow(() -> new AiWorkbenchService.AiNotFoundException("Simulator draft not found"));
        AiSimulatorVersionEntity version = versions.findByDraftId(draftId).orElseGet(() -> {
            try {
                @SuppressWarnings("unchecked") Map<String, Object> payload = mapper.readValue(draft.getPayloadJson(), Map.class);
                if (!"skilllab.simulator-behavior.v2".equals(payload.get("schemaVersion")))
                    throw new IllegalArgumentException("Only a v2 behavior draft can become a candidate");
                Object packageVersion = payload.get("packageVersion");
                if (!(packageVersion instanceof Map)) throw new IllegalArgumentException("Package reference is missing");
                Map<?, ?> reference = (Map<?, ?>) packageVersion;
                AiPackageEntity archive = packages.findById(draft.getPackageId())
                        .orElseThrow(() -> new AiWorkbenchService.AiNotFoundException("Package version not found"));
                if (!projectId.equals(archive.getProjectId()) || !archive.getId().equals(reference.get("id"))
                        || !archive.getSha256().equals(reference.get("sha256")))
                    throw new IllegalArgumentException("Package reference does not match the uploaded archive");
                String packageHash = archive.getSha256();
                String behaviorHash = sha256(draft.getPayloadJson());
                String id = UUID.randomUUID().toString();
                return versions.save(new AiSimulatorVersionEntity(id, draftId, projectId, creatorId,
                        "CANDIDATE", packageHash, behaviorHash));
            } catch (Exception exception) { throw new IllegalArgumentException("Simulator draft is invalid", exception); }
        });
        if (!creatorId.equals(version.getCreatorId())) throw new SecurityException("Version belongs to another creator");
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("versionId", version.getId()); result.put("draftId", draftId); result.put("projectId", projectId);
        result.put("status", version.getStatus()); result.put("packageSha256", version.getPackageSha256());
        result.put("behaviorSha256", version.getBehaviorSha256()); result.put("validationReportId", version.getValidationReportId());
        result.put("runtimeSha256", version.getRuntimeSha256()); return result;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getCandidate(String projectId, String versionId, String creatorId) {
        AiSimulatorVersionEntity version = versions.findById(versionId)
                .orElseThrow(() -> new AiWorkbenchService.AiNotFoundException("Simulator version not found"));
        if (!projectId.equals(version.getProjectId())) throw new AiWorkbenchService.AiNotFoundException("Simulator version not found");
        if (!creatorId.equals(version.getCreatorId())) throw new SecurityException("Version belongs to another creator");
        Map<String, Object> result = new LinkedHashMap<String, Object>(); result.put("versionId", versionId);
        result.put("draftId", version.getDraftId()); result.put("projectId", projectId); result.put("status", version.getStatus());
        result.put("packageSha256", version.getPackageSha256()); result.put("behaviorSha256", version.getBehaviorSha256());
        result.put("validationReportId", version.getValidationReportId()); result.put("runtimeSha256", version.getRuntimeSha256());
        return result;
    }

    @Transactional
    public Map<String, Object> disableVersion(String versionId, String creatorId) {
        AiSimulatorVersionEntity version = versions.findById(versionId)
                .orElseThrow(() -> new AiWorkbenchService.AiNotFoundException("Simulator version not found"));
        if (!creatorId.equals(version.getCreatorId())) throw new SecurityException("Version belongs to another creator");
        version.disable(); versions.save(version);
        Map<String, Object> result = new LinkedHashMap<String, Object>(); result.put("versionId", versionId);
        result.put("status", version.getStatus()); return result;
    }

    @Transactional
    public Map<String, Object> startRun(String versionId, String principal) {
        AiSimulatorVersionEntity version = versions.findById(versionId)
                .orElseThrow(() -> new AiWorkbenchService.AiNotFoundException("Simulator version not found"));
        if (!principal.equals(version.getCreatorId())) throw new SecurityException("Version belongs to another creator");
        if (!"ENABLED".equals(version.getStatus())) throw new IllegalStateException("Only enabled versions can start normally");
        if (version.getRuntimeSha256() == null || !version.getRuntimeSha256().equals(
                worker.getJson("/internal/validator-runtime").get("runtimeHash")))
            throw new IllegalStateException("Validated runtime has changed");
        Instant now = Instant.now();
        String runId = UUID.randomUUID().toString().replace("-", "");
        Instant expiry = now.plusMillis(Math.min(idleMs, maximumMs));
        AiRunEntity run = runs.save(new AiRunEntity(runId, versionId, version.getProjectId(), principal,
                now, expiry, now.plusMillis(maximumMs)));
        Map<String, Object> draft = draftPayload(version.getDraftId());
        String packageId = String.valueOf(((Map<?, ?>) draft.get("packageVersion")).get("id"));
        try {
            String sourceDirectory = previews.createRunPackage(runId, packageId).toString();
            Map<String, Object> registration = new LinkedHashMap<String, Object>();
            registration.put("runId", runId); registration.put("versionId", versionId);
            registration.put("projectId", version.getProjectId()); registration.put("ownerId", principal);
            registration.put("host", runHost(runId)); registration.put("expiresAt", expiry.toEpochMilli());
            registration.put("maxExpiresAt", run.getMaxExpiresAt().toEpochMilli());
            registration.put("draft", draft);
            registration.put("sourceDirectory", sourceDirectory);
            worker.post("/internal/runs", registration);
        }
        catch (RuntimeException exception) { previews.deleteRunPackage(runId); throw exception; }
        return runView(run, version);
    }

    @Transactional
    public Map<String, Object> heartbeat(String runId, String principal, Instant now) {
        AiRunEntity run = ownedActiveRun(runId, principal, now);
        Instant expiry = min(now.plusMillis(idleMs), run.getMaxExpiresAt());
        Map<String, Object> request = new LinkedHashMap<String, Object>();
        request.put("runId", runId); request.put("expiresAt", expiry.toEpochMilli());
        worker.post("/internal/runs/" + runId + "/heartbeat", request);
        run.renew(expiry); runs.save(run);
        Map<String, Object> result = new LinkedHashMap<String, Object>(); result.put("expiresAt", expiry.toString());
        result.put("heartbeatMs", heartbeatMs); return result;
    }

    @Transactional
    public void endRun(String runId, String principal) {
        AiRunEntity run = runs.findById(runId).orElseThrow(() -> new AiWorkbenchService.AiNotFoundException("Run not found"));
        if (!principal.equals(run.getOwnerId())) throw new SecurityException("Run belongs to another creator");
        if (run.getEndedAt() != null) return;
        worker.post("/internal/runs/" + runId + "/end", java.util.Collections.singletonMap("runId", runId));
        run.end(); runs.save(run);
        previews.deleteRunPackage(runId);
    }

    private AiRunEntity ownedActiveRun(String id, String principal, Instant now) {
        AiRunEntity run = runs.findById(id).orElseThrow(() -> new AiWorkbenchService.AiNotFoundException("Run not found"));
        if (!principal.equals(run.getOwnerId())) throw new SecurityException("Run belongs to another creator");
        if (run.getEndedAt() != null || !run.getLeaseExpiresAt().isAfter(now) || !run.getMaxExpiresAt().isAfter(now))
            throw new SecurityException("Run lease has expired");
        return run;
    }

    private Map<String, Object> draftPayload(String draftId) {
        AiSimulatorDraftEntity entity = drafts.findById(draftId)
                .orElseThrow(() -> new AiWorkbenchService.AiNotFoundException("Simulator draft not found"));
        try { return mapper.readValue(entity.getPayloadJson(), Map.class); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("Simulator draft is invalid", exception); }
    }
    private String runHost(String id) { return runOriginTemplate.replace("{runId}", id).replaceFirst("^https?://", ""); }
    private Map<String, Object> runView(AiRunEntity run, AiSimulatorVersionEntity version) {
        Map<String, Object> view = new LinkedHashMap<String, Object>(); view.put("runId", run.getId());
        view.put("versionId", version.getId()); view.put("runUrl", runOriginTemplate.replace("{runId}", run.getId()) + "/__skilllab/start");
        view.put("expiresAt", run.getLeaseExpiresAt().toString()); view.put("heartbeatMs", heartbeatMs); return view;
    }
    private Instant min(Instant left, Instant right) { return left.isBefore(right) ? left : right; }
    private String sha256(String value) throws NoSuchAlgorithmException {
        byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder(); for (byte item : bytes) result.append(String.format("%02x", item & 0xff));
        return result.toString();
    }
}
