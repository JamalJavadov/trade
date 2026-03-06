package com.tradebot.demo.repository;

import com.tradebot.demo.entity.DemoAiCallLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface DemoAiCallLogRepository extends JpaRepository<DemoAiCallLog, UUID> {
    Optional<DemoAiCallLog> findFirstByTaskTypeOrderByCreatedAtDesc(String taskType);
}
