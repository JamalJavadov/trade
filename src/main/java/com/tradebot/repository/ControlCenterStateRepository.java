package com.tradebot.repository;

import com.tradebot.entity.ControlCenterStateEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ControlCenterStateRepository extends JpaRepository<ControlCenterStateEntity, Integer> {
}
