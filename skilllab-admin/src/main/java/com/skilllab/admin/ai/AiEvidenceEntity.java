package com.skilllab.admin.ai;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Lob;
import javax.persistence.Table;

@Entity
@Table(name = "ai_capture_evidence")
public class AiEvidenceEntity {
    @Id private String id;
    @Column(nullable = false, length = 36) private String projectId;
    @Column(nullable = false, length = 36) private String sessionId;
    @Column(nullable = false) private int sequence;
    @Lob @Column(nullable = false) private String payloadJson;

    protected AiEvidenceEntity() { }
    public AiEvidenceEntity(String id, String projectId, String sessionId, int sequence, String payloadJson) {
        this.id = id;
        this.projectId = projectId;
        this.sessionId = sessionId;
        this.sequence = sequence;
        this.payloadJson = payloadJson;
    }
    public String getId() { return id; }
    public String getProjectId() { return projectId; }
    public String getSessionId() { return sessionId; }
    public int getSequence() { return sequence; }
    public String getPayloadJson() { return payloadJson; }
}
