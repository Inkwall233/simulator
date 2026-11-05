package com.skilllab.admin.ai;

import java.time.LocalDateTime;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Lob;
import javax.persistence.Table;

@Entity
@Table(name = "ai_api_draft")
public class AiApiDraftEntity {

    @Id
    private String id;

    @Column(nullable = false, length = 36)
    private String projectId;

    @Column(nullable = false, length = 36)
    private String sessionId;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Lob
    @Column(nullable = false)
    private String payloadJson;

    protected AiApiDraftEntity() {
    }

    public AiApiDraftEntity(String id, String projectId, String sessionId, String payloadJson) {
        this.id = id;
        this.projectId = projectId;
        this.sessionId = sessionId;
        this.payloadJson = payloadJson;
        this.createdAt = LocalDateTime.now();
    }

    public String getId() { return id; }
    public String getProjectId() { return projectId; }
    public String getSessionId() { return sessionId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public String getPayloadJson() { return payloadJson; }
}
