package com.tradebot.demo.repository;

import com.tradebot.demo.entity.DemoTrade;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DemoTradeRepository extends JpaRepository<DemoTrade, UUID> {
    Page<DemoTrade> findAllByOrderByOpenedAtDesc(Pageable pageable);

    Optional<DemoTrade> findFirstByOrderByOpenedAtDesc();

    Optional<DemoTrade> findFirstByStatusOrderByOpenedAtDesc(String status);

    Optional<DemoTrade> findFirstByStatusOrderByClosedAtDesc(String status);

    long countByStatus(String status);

    List<DemoTrade> findByStatusOrderByOpenedAtAsc(String status);

    List<DemoTrade> findByStatusOrderByOpenedAtDesc(String status);

    @Query(value = "SELECT * FROM demo_trade WHERE status = 'CLOSED' ORDER BY closed_at DESC LIMIT :limit", nativeQuery = true)
    List<DemoTrade> findLastClosed(int limit);

    @Query(value = "SELECT COUNT(*) FROM demo_trade WHERE status = 'CLOSED'", nativeQuery = true)
    long countClosedTrades();

    @Query(value = "SELECT COUNT(*) FROM demo_trade WHERE status = 'CLOSED' AND pnl_usdt > 0", nativeQuery = true)
    long countWinningClosedTrades();
}
