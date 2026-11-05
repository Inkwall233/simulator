package com.skilllab.admin.ai;

import java.time.LocalDateTime;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

@Entity
@Table(name = "ai_exploration_session")
public class AiSessionEntity {

    @Id
    private String id;

    @Column(nullable = false, length = 36)
    private String projectId;

    @Column(nullable = false, length = 2048)
    private String targetUrl;

    @Column(nullable = false, length = 32)
    private String status;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private LocalDateTime closedAt;

    protected AiSessionEntity() {
    }

    public AiSessionEntity(String id, String projectId, String targetUrl) {
        this.id = id;
        this.projectId = projectId;
        this.targetUrl = targetUrl;
        this.status = "RUNNING";
        this.createdAt = LocalDateTime.now();
    }

    public String getId() { return id; }
    public String getProjectId() { return projectId; }
    public String getTargetUrl() { return targetUrl; }
    public String getStatus() { return status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getClosedAt() { return closedAt; }

    public void close() {
        this.status = "CLOSED";
        this.closedAt = LocalDateTime.now();
    }
}
