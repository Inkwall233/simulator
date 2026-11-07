package com.skilllab.admin.ai;

import java.util.Optional;

import javax.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AiDeviceProjectRepository extends JpaRepository<AiDeviceProjectEntity, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select project from AiDeviceProjectEntity project where project.id = :id")
    Optional<AiDeviceProjectEntity> findByIdForUpdate(@Param("id") String id);
}
