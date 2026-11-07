package com.skilllab.admin.ai;

import java.time.LocalDateTime;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Lob;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;

@Entity
@Table(name = "ai_simulator_draft", uniqueConstraints = {
        @UniqueConstraint(name = "uk_ai_simulator_draft_project_version", columnNames = { "project_id", "version_number" })
})
public class AiSimulatorDraftEntity {

    @Id
    private String id;

    @Column(name = "project_id", nullable = false, length = 36)
    private String projectId;

    @Column(name = "package_id", nullable = false, length = 36)
    private String packageId;

    @Column(name = "source_api_draft_id", nullable = false, length = 36)
    private String sourceApiDraftId;

    @Column(name = "version_number", nullable = false)
    private int versionNumber;

    @Column(nullable = false, length = 32)
    private String status;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Lob
    @Column(nullable = false)
    private String payloadJson;

    protected AiSimulatorDraftEntity() {
    }

    public AiSimulatorDraftEntity(String id, String projectId, String packageId,
            String sourceApiDraftId, int versionNumber, String status, String payloadJson) {
        this.id = id;
        this.projectId = projectId;
        this.packageId = packageId;
        this.sourceApiDraftId = sourceApiDraftId;
        this.versionNumber = versionNumber;
        this.status = status;
        this.payloadJson = payloadJson;
        this.createdAt = LocalDateTime.now();
    }

    public String getId() { return id; }
    public String getProjectId() { return projectId; }
    public String getPackageId() { return packageId; }
    public String getSourceApiDraftId() { return sourceApiDraftId; }
    public int getVersionNumber() { return versionNumber; }
    public String getStatus() { return status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public String getPayloadJson() { return payloadJson; }
}
