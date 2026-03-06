package com.tradebot.demo.repository;

import com.tradebot.demo.entity.DemoAccountEquityEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DemoAccountEquityEventRepository extends JpaRepository<DemoAccountEquityEvent, UUID> {
    List<DemoAccountEquityEvent> findAllByOrderByCreatedAtAsc();
}
