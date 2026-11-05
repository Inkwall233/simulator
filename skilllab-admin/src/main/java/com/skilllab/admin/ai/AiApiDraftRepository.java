package com.skilllab.admin.ai;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AiApiDraftRepository extends JpaRepository<AiApiDraftEntity, String> {
    Optional<AiApiDraftEntity> findFirstBySessionIdOrderByCreatedAtDesc(String sessionId);
}
