package com.skilllab.admin.ai;

import java.time.Instant;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Lob;
import javax.persistence.Table;

@Entity
@Table(name = "ai_device_baseline")
public class AiBaselineEntity {
    @Id private String id;
    @Column(nullable = false, length = 36) private String projectId;
    @Column(nullable = false, length = 36) private String packageId;
    @Column(nullable = false, length = 64) private String packageSha256;
    @Column(nullable = false, length = 36) private String apiDraftId;
    @Column(nullable = false, length = 36) private String sessionId;
    @Column(nullable = false, length = 160) private String creatorId;
    @Column(nullable = false, length = 64) private String snapshotSha256;
    @Lob @Column(nullable = false) private String snapshotJson;
    @Column(nullable = false) private Instant createdAt;

    protected AiBaselineEntity() { }

    public AiBaselineEntity(String id, String projectId, String packageId, String packageSha256,
            String apiDraftId, String sessionId, String creatorId, String snapshotSha256, String snapshotJson) {
        this.id = id; this.projectId = projectId; this.packageId = packageId;
        this.packageSha256 = packageSha256; this.apiDraftId = apiDraftId; this.sessionId = sessionId;
        this.creatorId = creatorId; this.snapshotSha256 = snapshotSha256; this.snapshotJson = snapshotJson;
        this.createdAt = Instant.now();
    }

    public String getId() { return id; }
    public String getProjectId() { return projectId; }
    public String getPackageId() { return packageId; }
    public String getPackageSha256() { return packageSha256; }
    public String getApiDraftId() { return apiDraftId; }
    public String getSessionId() { return sessionId; }
    public String getCreatorId() { return creatorId; }
    public String getSnapshotSha256() { return snapshotSha256; }
    public String getSnapshotJson() { return snapshotJson; }
    public Instant getCreatedAt() { return createdAt; }
}
