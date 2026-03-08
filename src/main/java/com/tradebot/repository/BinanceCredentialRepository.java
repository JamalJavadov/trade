package com.tradebot.repository;

import com.tradebot.entity.BinanceCredentialEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface BinanceCredentialRepository extends JpaRepository<BinanceCredentialEntity, Long> {
    Optional<BinanceCredentialEntity> findTopByOrderByIdDesc();
}
