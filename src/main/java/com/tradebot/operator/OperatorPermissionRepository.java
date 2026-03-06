package com.tradebot.operator;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OperatorPermissionRepository extends JpaRepository<OperatorPermissionEntity, String> {
    List<OperatorPermissionEntity> findAllByOrderByGroupNameAscKeyAsc();
}
