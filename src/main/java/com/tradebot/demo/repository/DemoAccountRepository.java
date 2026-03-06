package com.tradebot.demo.repository;

import com.tradebot.demo.entity.DemoAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface DemoAccountRepository extends JpaRepository<DemoAccount, UUID> {
    Optional<DemoAccount> findFirstByOrderByCreatedAtAsc();

    Optional<DemoAccount> findFirstByModeEnabledTrueOrderByCreatedAtAsc();
}
