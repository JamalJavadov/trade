package com.tradebot.repository;

import com.tradebot.entity.BudgetTargetSession;
import com.tradebot.entity.BudgetTargetSessionStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BudgetTargetSessionRepository extends JpaRepository<BudgetTargetSession, UUID> {

    Optional<BudgetTargetSession> findFirstByOrderByCreatedAtDesc();

    Page<BudgetTargetSession> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Optional<BudgetTargetSession> findFirstByStatusInOrderByCreatedAtDesc(Collection<BudgetTargetSessionStatus> statuses);

    List<BudgetTargetSession> findTop10ByOrderByCreatedAtDesc();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select session from BudgetTargetSession session where session.id = :id")
    Optional<BudgetTargetSession> findByIdForUpdate(UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<BudgetTargetSession> findByStatusInOrderByCreatedAtDesc(Collection<BudgetTargetSessionStatus> statuses);

    default Optional<BudgetTargetSession> findActiveForUpdate(Collection<BudgetTargetSessionStatus> statuses) {
        return findByStatusInOrderByCreatedAtDesc(statuses).stream().findFirst();
    }
}
