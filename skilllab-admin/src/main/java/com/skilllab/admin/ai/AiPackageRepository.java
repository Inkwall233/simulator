package com.skilllab.admin.ai;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AiPackageRepository extends JpaRepository<AiPackageEntity, String> {
    List<AiPackageEntity> findAllByProjectIdOrderByUploadedAtDesc(String projectId);
}
