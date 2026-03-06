package com.tradebot.repository;

import com.tradebot.entity.AiProviderAudit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface AiProviderAuditRepository extends JpaRepository<AiProviderAudit, UUID> {
}
