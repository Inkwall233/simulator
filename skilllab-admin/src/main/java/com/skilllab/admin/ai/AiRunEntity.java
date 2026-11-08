package com.skilllab.admin.ai;

import java.time.Instant;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

@Entity
@Table(name = "ai_simulator_run")
public class AiRunEntity {
    @Id private String id;
    @Column(nullable = false, length = 36) private String versionId;
    @Column(nullable = false, length = 36) private String projectId;
    @Column(nullable = false, length = 160) private String ownerId;
    @Column(nullable = false) private Instant startedAt;
    @Column(nullable = false) private Instant leaseExpiresAt;
    @Column(nullable = false) private Instant maxExpiresAt;
    @Column private Instant endedAt;

    protected AiRunEntity() { }
    public AiRunEntity(String id, String versionId, String projectId, String ownerId,
            Instant startedAt, Instant leaseExpiresAt, Instant maxExpiresAt) {
        this.id = id; this.versionId = versionId; this.projectId = projectId; this.ownerId = ownerId;
        this.startedAt = startedAt; this.leaseExpiresAt = leaseExpiresAt; this.maxExpiresAt = maxExpiresAt;
    }
    public void renew(Instant expiry) { if (endedAt != null) throw new SecurityException("Run has ended"); leaseExpiresAt = expiry; }
    public void end() { endedAt = Instant.now(); }
    public String getId() { return id; }
    public String getVersionId() { return versionId; }
    public String getProjectId() { return projectId; }
    public String getOwnerId() { return ownerId; }
    public Instant getLeaseExpiresAt() { return leaseExpiresAt; }
    public Instant getMaxExpiresAt() { return maxExpiresAt; }
    public Instant getEndedAt() { return endedAt; }
}
