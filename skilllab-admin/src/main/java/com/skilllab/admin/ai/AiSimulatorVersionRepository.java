package com.skilllab.admin.ai;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiSimulatorVersionRepository extends JpaRepository<AiSimulatorVersionEntity, String> {
    Optional<AiSimulatorVersionEntity> findByDraftId(String draftId);
}
