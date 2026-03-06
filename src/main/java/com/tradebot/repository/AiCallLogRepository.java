package com.tradebot.repository;

import com.tradebot.entity.AiCallLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface AiCallLogRepository extends JpaRepository<AiCallLog, UUID> {
}
