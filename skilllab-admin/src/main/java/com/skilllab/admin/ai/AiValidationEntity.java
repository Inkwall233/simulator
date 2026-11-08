package com.skilllab.admin.ai;

import java.time.Instant;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Lob;
import javax.persistence.Table;

@Entity
@Table(name = "ai_simulator_validation")
public class AiValidationEntity {
    @Id private String id;
    @Column(nullable = false, length = 36) private String versionId;
    @Column(nullable = false, length = 36) private String projectId;
    @Column(nullable = false, length = 160) private String creatorId;
    @Column(nullable = false, length = 64) private String packageSha256;
    @Column(nullable = false, length = 64) private String behaviorSha256;
    @Column(nullable = false, length = 64) private String runtimeSha256;
    @Column(nullable = false, length = 24) private String status;
    @Lob @Column(nullable = false) private String reportJson;
    @Column(nullable = false) private Instant createdAt;

    protected AiValidationEntity() { }
    public AiValidationEntity(String id, String versionId, String projectId, String creatorId,
            String packageSha256, String behaviorSha256, String runtimeSha256, String status, String reportJson) {
        this.id = id; this.versionId = versionId; this.projectId = projectId; this.creatorId = creatorId;
        this.packageSha256 = packageSha256; this.behaviorSha256 = behaviorSha256;
        this.runtimeSha256 = runtimeSha256; this.status = status; this.reportJson = reportJson;
        this.createdAt = Instant.now();
    }
    public String getId() { return id; }
    public String getVersionId() { return versionId; }
    public String getProjectId() { return projectId; }
    public String getCreatorId() { return creatorId; }
    public String getPackageSha256() { return packageSha256; }
    public String getBehaviorSha256() { return behaviorSha256; }
    public String getRuntimeSha256() { return runtimeSha256; }
    public String getStatus() { return status; }
    public String getReportJson() { return reportJson; }
}
