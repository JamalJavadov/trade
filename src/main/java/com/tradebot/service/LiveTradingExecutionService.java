package com.tradebot.service;

import com.tradebot.dto.LiveTradeExecutionDTO;
import com.tradebot.dto.LiveTradeExecutionRequestDTO;
import com.tradebot.entity.LiveTradeExecution;
import com.tradebot.entity.LiveTradeTriggerMode;
import com.tradebot.repository.LiveTradeExecutionEventRepository;
import com.tradebot.repository.LiveTradeExecutionRepository;
import com.tradebot.security.LocalMutationGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class LiveTradingExecutionService {

    private final LiveTradeExecutionRepository liveTradeExecutionRepository;
    private final LiveTradeExecutionEventRepository liveTradeExecutionEventRepository;
    private final LiveTradingMapper liveTradingMapper;
    private final LiveExecutionEngineService liveExecutionEngineService;

    @Transactional(readOnly = true)
    public LiveTradeExecutionDTO getExecution(UUID executionId) {
        LiveTradeExecution execution = liveTradeExecutionRepository.findById(executionId)
                .orElseThrow(() -> new NoSuchElementException("Live execution not found: " + executionId));
        return liveTradingMapper.toDetail(execution,
                liveTradeExecutionEventRepository.findByExecution_IdOrderByCreatedAtAsc(executionId));
    }

    @Transactional(readOnly = true)
    public List<LiveTradeExecutionDTO> listExecutions(UUID recommendationId, int limit) {
        int normalizedLimit = Math.max(1, Math.min(limit, 50));
        List<LiveTradeExecution> executions = recommendationId == null
                ? liveTradeExecutionRepository.findTop50ByOrderByCreatedAtDesc()
                : liveTradeExecutionRepository.findTop20ByRecommendation_IdOrderByCreatedAtDesc(recommendationId);

        return executions.stream()
                .limit(normalizedLimit)
                .map(execution -> liveTradingMapper.toDetail(execution,
                        liveTradeExecutionEventRepository.findByExecution_IdOrderByCreatedAtAsc(execution.getId())))
                .toList();
    }

    public LiveTradeExecutionDTO executeLive(UUID recommendationId,
            LiveTradeExecutionRequestDTO request,
            String operatorId,
            String traceId) {
        return executeLive(recommendationId, request, operatorId, traceId, null);
    }

    public LiveTradeExecutionDTO executeLive(UUID recommendationId,
            LiveTradeExecutionRequestDTO request,
            String operatorId,
            String traceId,
            LocalMutationGuard.LocalRequestCheck localRequestCheck) {
        ApprovedExecutionCommand command = new ApprovedExecutionCommand(
                recommendationId,
                null,
                null,
                requireClientRequestId(request),
                LiveTradeTriggerMode.MANUAL_BUTTON,
                operatorId,
                traceId,
                request != null ? request.getOperatorNote() : null,
                "Manual live execution requested from recommendation detail.");
        return liveExecutionEngineService.execute(command, localRequestCheck);
    }

    public LiveTradeExecutionDTO executeAutoSession(UUID recommendationId,
            UUID budgetTargetSessionId,
            BigDecimal allocatedBudgetSliceUsdt,
            String operatorId,
            String traceId) {
        ApprovedExecutionCommand command = new ApprovedExecutionCommand(
                recommendationId,
                budgetTargetSessionId,
                allocatedBudgetSliceUsdt,
                ApprovedExecutionCommand.deterministicKey("AUTO_SESSION|" + budgetTargetSessionId + "|" + recommendationId),
                LiveTradeTriggerMode.AUTO_SESSION,
                operatorId,
                traceId,
                "budget-target-auto-session",
                "Budget-target auto-execution submitted this recommendation.");
        return liveExecutionEngineService.execute(command, null);
    }

    public SafeCloseAttemptResult requestSafeClose(UUID executionId,
            String operatorId,
            String traceId,
            String closeReason) {
        return liveExecutionEngineService.requestSafeClose(executionId, operatorId, traceId, closeReason);
    }

    private UUID requireClientRequestId(LiveTradeExecutionRequestDTO request) {
        if (request == null || request.getClientRequestId() == null) {
            throw new IllegalArgumentException("clientRequestId is required");
        }
        return request.getClientRequestId();
    }
}
