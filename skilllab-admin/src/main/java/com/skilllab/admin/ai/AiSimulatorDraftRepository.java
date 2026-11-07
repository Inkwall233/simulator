package com.skilllab.admin.ai;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AiSimulatorDraftRepository extends JpaRepository<AiSimulatorDraftEntity, String> {
    List<AiSimulatorDraftEntity> findAllByProjectIdOrderByVersionNumberDesc(String projectId);
    Optional<AiSimulatorDraftEntity> findByIdAndProjectId(String id, String projectId);
    Optional<AiSimulatorDraftEntity> findFirstByProjectIdOrderByVersionNumberDesc(String projectId);
}
