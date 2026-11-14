package com.skilllab.admin.ai;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AiValidationService {
    private final AiSimulatorVersionRepository versions;
    private final AiSimulatorDraftRepository drafts;
    private final AiPackageRepository packages;
    private final AiValidationRepository reports;
    private final AiPreviewService previews;
    private final AiWorkerClient worker;
    private final ObjectMapper mapper;
    private AiBaselineService baselines;

    @org.springframework.beans.factory.annotation.Autowired
    public void setBaselines(AiBaselineService baselines) { this.baselines = baselines; }

    public AiValidationService(AiSimulatorVersionRepository versions, AiSimulatorDraftRepository drafts,
            AiPackageRepository packages, AiValidationRepository reports, AiPreviewService previews,
            AiWorkerClient worker, ObjectMapper mapper) {
        this.versions = versions; this.drafts = drafts; this.packages = packages;
        this.reports = reports; this.previews = previews; this.worker = worker; this.mapper = mapper;
    }

    public Map<String, Object> validate(String projectId, String versionId, String creatorId) {
        return validate(projectId, versionId, creatorId, null);
    }

    public Map<String, Object> validate(String projectId, String versionId, String creatorId, String baselineId) {
        AiSimulatorVersionEntity version = ownedVersion(projectId, versionId, creatorId);
        if (!"CANDIDATE".equals(version.getStatus())) throw new IllegalStateException("Only candidates can be validated");
        assertCurrentHashes(version);
        Map<String, Object> preview = previews.createPreview(projectId, version.getDraftId());
        String previewId = String.valueOf(preview.get("previewId"));
        try {
            Map<String, Object> input = new LinkedHashMap<String, Object>();
            if (baselineId != null && !baselineId.isEmpty()) {
                if (baselines == null) throw new IllegalStateException("Baselines unavailable");
                Map<String, Object> baseline = baselines.getBaseline(projectId, baselineId, creatorId);
                AiSimulatorDraftEntity source = drafts.findById(version.getDraftId()).orElseThrow(() -> new IllegalStateException("Draft missing"));
                if (!source.getPackageId().equals(baseline.get("packageId")) || !source.getSourceApiDraftId().equals(baseline.get("apiDraftId"))
                        || !version.getPackageSha256().equals(baseline.get("packageSha256"))) throw new IllegalArgumentException("Baseline does not match candidate");
                input.put("baseline", baseline);
            }
            Map<String, Object> result = worker.post("/internal/previews/" + previewId + "/validate", input);
            String status = String.valueOf(result.get("status"));
            String runtimeHash = String.valueOf(result.get("runtimeHash"));
            if (!("PASSED".equals(status) || "BLOCKED".equals(status)) || !runtimeHash.matches("[a-f0-9]{64}"))
                throw new IllegalStateException("Worker validation report is invalid");
            if (!runtimeHash.equals(worker.getJson("/internal/validator-runtime").get("runtimeHash")))
                throw new IllegalStateException("Validation runtime changed during replay");
            try {
                AiValidationEntity saved = reports.save(new AiValidationEntity(UUID.randomUUID().toString(),
                        versionId, projectId, creatorId, version.getPackageSha256(), version.getBehaviorSha256(),
                        runtimeHash, status, mapper.writeValueAsString(result)));
                return reportView(saved);
            } catch (JsonProcessingException exception) { throw new IllegalStateException("Could not store validation report", exception); }
        } finally {
            try { worker.post("/internal/previews/" + previewId + "/close", java.util.Collections.singletonMap("previewId", previewId)); }
            catch (RuntimeException ignored) { }
        }
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getReport(String projectId, String versionId, String reportId, String creatorId) {
        ownedVersion(projectId, versionId, creatorId);
        AiValidationEntity report = reports.findById(reportId)
                .orElseThrow(() -> new AiWorkbenchService.AiNotFoundException("Validation report not found"));
        if (!versionId.equals(report.getVersionId()) || !projectId.equals(report.getProjectId())
                || !creatorId.equals(report.getCreatorId())) throw new SecurityException("Validation report is not accessible");
        return reportView(report);
    }

    @Transactional
    public Map<String, Object> enable(String projectId, String versionId, String reportId,
            String creatorId, boolean acknowledgeWarnings) {
        AiSimulatorVersionEntity version = ownedVersion(projectId, versionId, creatorId);
        AiValidationEntity report = reports.findById(reportId)
                .orElseThrow(() -> new AiWorkbenchService.AiNotFoundException("Validation report not found"));
        if (!"CANDIDATE".equals(version.getStatus()) || !"PASSED".equals(report.getStatus())
                || !versionId.equals(report.getVersionId()) || !projectId.equals(report.getProjectId())
                || !creatorId.equals(report.getCreatorId())
                || !version.getPackageSha256().equals(report.getPackageSha256())
                || !version.getBehaviorSha256().equals(report.getBehaviorSha256())) {
            throw new IllegalStateException("Validation report does not authorize this candidate");
        }
        assertCurrentHashes(version);
        if (!report.getRuntimeSha256().equals(worker.getJson("/internal/validator-runtime").get("runtimeHash")))
            throw new IllegalStateException("Validation runtime has changed");
        try {
            @SuppressWarnings("unchecked") Map<String, Object> result = mapper.readValue(report.getReportJson(), Map.class);
            if (!"PASSED".equals(result.get("status")) || !report.getRuntimeSha256().equals(result.get("runtimeHash"))
                    || !(result.get("blockers") instanceof java.util.List) || !((java.util.List<?>) result.get("blockers")).isEmpty()
                    || !(result.get("warnings") instanceof java.util.List)
                    || (!((java.util.List<?>) result.get("warnings")).isEmpty() && !acknowledgeWarnings))
                throw new IllegalStateException("Validation findings must be reviewed before enabling");
            Object consistency = result.get("consistency");
            if (!(consistency instanceof Map) || !java.util.Arrays.asList("NOT_TESTED", "PARTIAL").contains(((Map<?, ?>) consistency).get("status")))
                throw new IllegalStateException("Only demo-level consistency can be enabled in this phase");
        } catch (JsonProcessingException exception) { throw new IllegalStateException("Validation report is invalid", exception); }
        version.enableDemo(reportId, report.getRuntimeSha256()); versions.save(version);
        Map<String, Object> view = new LinkedHashMap<String, Object>();
        view.put("versionId", versionId); view.put("status", version.getStatus()); view.put("reportId", reportId);
        return view;
    }

    private AiSimulatorVersionEntity ownedVersion(String projectId, String versionId, String creatorId) {
        AiSimulatorVersionEntity version = versions.findById(versionId)
                .orElseThrow(() -> new AiWorkbenchService.AiNotFoundException("Simulator version not found"));
        if (!projectId.equals(version.getProjectId())) throw new AiWorkbenchService.AiNotFoundException("Simulator version not found");
        if (!creatorId.equals(version.getCreatorId())) throw new SecurityException("Version belongs to another creator");
        return version;
    }

    private void assertCurrentHashes(AiSimulatorVersionEntity version) {
        AiSimulatorDraftEntity draft = drafts.findById(version.getDraftId())
                .orElseThrow(() -> new AiWorkbenchService.AiNotFoundException("Simulator draft not found"));
        AiPackageEntity archive = packages.findById(draft.getPackageId())
                .orElseThrow(() -> new AiWorkbenchService.AiNotFoundException("Package version not found"));
        if (!version.getProjectId().equals(draft.getProjectId()) || !version.getProjectId().equals(archive.getProjectId())
                || !version.getPackageSha256().equals(archive.getSha256())
                || !version.getBehaviorSha256().equals(sha256(draft.getPayloadJson())))
            throw new IllegalStateException("Candidate inputs changed; create a new version");
        previews.verifyArchive(archive);
    }

    private String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hash = new StringBuilder();
            for (byte item : bytes) hash.append(String.format("%02x", item & 0xff));
            return hash.toString();
        } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    private Map<String, Object> reportView(AiValidationEntity report) {
        try {
            @SuppressWarnings("unchecked") Map<String, Object> result = mapper.readValue(report.getReportJson(), Map.class);
            Map<String, Object> view = new LinkedHashMap<String, Object>(result);
            view.put("reportId", report.getId()); view.put("versionId", report.getVersionId());
            view.put("packageSha256", report.getPackageSha256()); view.put("behaviorSha256", report.getBehaviorSha256());
            return view;
        } catch (JsonProcessingException exception) { throw new IllegalStateException("Validation report is invalid", exception); }
    }
}
