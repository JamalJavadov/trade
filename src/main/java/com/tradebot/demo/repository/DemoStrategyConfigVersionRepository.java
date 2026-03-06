package com.tradebot.demo.repository;

import com.tradebot.demo.entity.DemoStrategyConfigVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface DemoStrategyConfigVersionRepository extends JpaRepository<DemoStrategyConfigVersion, UUID> {
    Optional<DemoStrategyConfigVersion> findByActiveTrue();

    Optional<DemoStrategyConfigVersion> findFirstByOrderByVersionDesc();
}
