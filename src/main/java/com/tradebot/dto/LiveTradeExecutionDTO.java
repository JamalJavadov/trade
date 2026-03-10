package com.tradebot.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Data
public class LiveTradeExecutionDTO {
    private UUID id;
    private UUID recommendationId;
    private UUID sessionId;
    private UUID budgetTargetSessionId;
    private String symbol;
    private String side;
    private String triggerMode;
    private String operatorId;
    private String traceId;
    private boolean dryRun;
    private String executionStatus;
    private String executionState;
    private String errorCode;
    private String errorMessage;
    private Map<String, Object> errorDetails;
    private boolean requiresIntervention;
    private CriticalIssue criticalIssue;
    private BigDecimal reservedMarginUsdt;
    private BigDecimal requestedBudgetSliceUsdt;
    private BigDecimal requestedQty;
    private BigDecimal actualFilledQty;
    private BigDecimal realizedGrossPnlUsdt;
    private BigDecimal realizedFeesUsdt;
    private BigDecimal realizedNetPnlUsdt;
    private String closeReason;
    private Integer positionSlot;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant submittedAt;
    private Instant completedAt;
    private Instant lastReconciledAt;
    private int reconcileCount;
    private BudgetTargetSyncHealthDTO syncHealth;
    private OrderRefs orderRefs = new OrderRefs();
    private Map<String, Object> payloadSnapshot;
    private Map<String, Object> preflight;
    private Map<String, Object> entryResponse;
    private Map<String, Object> protectionResponse;
    private Map<String, Object> exchangeResponse;
    private List<LiveTradeExecutionEventDTO> events = new ArrayList<>();

    @Data
    public static class CriticalIssue {
        private String code;
        private String message;
        private Map<String, Object> details = Map.of();
        private Instant raisedAt;
    }

    @Data
    public static class OrderRefs {
        private String entryClientOrderId;
        private String slClientOrderId;
        private String tpClientOrderId;
        private String emergencyCloseClientOrderId;
        private Long entryOrderId;
        private Long slOrderId;
        private Long tpOrderId;
        private Long emergencyCloseOrderId;
    }
}
