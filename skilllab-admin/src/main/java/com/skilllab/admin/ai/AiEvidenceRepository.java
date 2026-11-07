package com.skilllab.admin.ai;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiEvidenceRepository extends JpaRepository<AiEvidenceEntity, String> {
    List<AiEvidenceEntity> findAllByProjectIdAndSessionIdOrderBySequenceAsc(String projectId, String sessionId);
    Optional<AiEvidenceEntity> findByIdAndProjectIdAndSessionId(String id, String projectId, String sessionId);
}
