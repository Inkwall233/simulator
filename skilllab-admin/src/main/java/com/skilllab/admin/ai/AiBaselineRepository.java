package com.skilllab.admin.ai;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiBaselineRepository extends JpaRepository<AiBaselineEntity, String> {
    Optional<AiBaselineEntity> findByIdAndProjectId(String id, String projectId);
    List<AiBaselineEntity> findAllByProjectIdOrderByCreatedAtDesc(String projectId);
}
