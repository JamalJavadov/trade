package com.tradebot.repository;

import com.tradebot.entity.StrategyConfigVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface StrategyConfigVersionRepository extends JpaRepository<StrategyConfigVersion, UUID> {
    Optional<StrategyConfigVersion> findByActiveTrue();

    Optional<StrategyConfigVersion> findFirstByOrderByVersionDesc();
}
