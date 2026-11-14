package com.skilllab.admin.ai;

import java.time.Instant;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import javax.persistence.Version;

@Entity
@Table(name = "ai_simulator_version")
public class AiSimulatorVersionEntity {
    @Id private String id;
    @Column(nullable = false, unique = true, length = 36) private String draftId;
    @Column(nullable = false, length = 36) private String projectId;
    @Column(nullable = false, length = 160) private String creatorId;
    @Column(nullable = false, length = 24) private String status;
    @Column(nullable = false, length = 64) private String packageSha256;
    @Column(nullable = false, length = 64) private String behaviorSha256;
    @Column(nullable = false) private Instant createdAt;
    @Column private Instant enabledAt;
    @Column(length = 36) private String validationReportId;
    @Column(length = 64) private String runtimeSha256;
    @Version private Long revision;

    protected AiSimulatorVersionEntity() { }

    public AiSimulatorVersionEntity(String id, String draftId, String projectId, String creatorId, String status) {
        this(id, draftId, projectId, creatorId, status, "", "");
    }

    public AiSimulatorVersionEntity(String id, String draftId, String projectId, String creatorId,
            String status, String packageSha256, String behaviorSha256) {
        this.id = id; this.draftId = draftId; this.projectId = projectId; this.creatorId = creatorId;
        this.status = status; this.packageSha256 = packageSha256; this.behaviorSha256 = behaviorSha256;
        this.createdAt = Instant.now();
    }

    public void enable(String reportId, String runtimeHash) {
        if (!"CANDIDATE".equals(status)) throw new IllegalStateException("Version is not a candidate");
        validationReportId = reportId; runtimeSha256 = runtimeHash; status = "ENABLED"; enabledAt = Instant.now();
    }
    public void enableDemo(String reportId, String runtimeHash) {
        if (!"CANDIDATE".equals(status)) throw new IllegalStateException("Version is not a candidate");
        validationReportId = reportId; runtimeSha256 = runtimeHash; status = "DEMO_ENABLED"; enabledAt = Instant.now();
    }
    public void disable() { status = "DISABLED"; }
    public String getId() { return id; }
    public String getDraftId() { return draftId; }
    public String getProjectId() { return projectId; }
    public String getCreatorId() { return creatorId; }
    public String getStatus() { return status; }
    public String getPackageSha256() { return packageSha256; }
    public String getBehaviorSha256() { return behaviorSha256; }
    public String getValidationReportId() { return validationReportId; }
    public String getRuntimeSha256() { return runtimeSha256; }
}
