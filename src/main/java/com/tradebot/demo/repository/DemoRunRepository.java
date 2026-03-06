package com.tradebot.demo.repository;

import com.tradebot.demo.entity.DemoRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface DemoRunRepository extends JpaRepository<DemoRun, UUID> {
    Optional<DemoRun> findFirstByOrderByStartedAtDesc();
    long countByStatus(String status);
}
