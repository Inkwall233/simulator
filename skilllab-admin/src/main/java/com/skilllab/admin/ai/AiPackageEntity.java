package com.skilllab.admin.ai;

import java.time.LocalDateTime;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

@Entity
@Table(name = "ai_package_version")
public class AiPackageEntity {

    @Id
    private String id;

    @Column(nullable = false, length = 36)
    private String projectId;

    @Column(nullable = false, length = 255)
    private String filename;

    @Column(nullable = false, length = 64)
    private String sha256;

    @Column(nullable = false)
    private long sizeBytes;

    @Column(nullable = false, length = 1024)
    private String storedPath;

    @Column(nullable = false)
    private LocalDateTime uploadedAt;

    protected AiPackageEntity() {
    }

    public AiPackageEntity(String id, String projectId, String filename, String sha256,
            long sizeBytes, String storedPath) {
        this.id = id;
        this.projectId = projectId;
        this.filename = filename;
        this.sha256 = sha256;
        this.sizeBytes = sizeBytes;
        this.storedPath = storedPath;
        this.uploadedAt = LocalDateTime.now();
    }

    public String getId() { return id; }
    public String getProjectId() { return projectId; }
    public String getFilename() { return filename; }
    public String getSha256() { return sha256; }
    public long getSizeBytes() { return sizeBytes; }
    public String getStoredPath() { return storedPath; }
    public LocalDateTime getUploadedAt() { return uploadedAt; }
}
