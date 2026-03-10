package com.tradebot.controller;

import com.tradebot.dto.BudgetTargetAutoExecutionStateDTO;
import com.tradebot.dto.BudgetTargetAutoExecutionStateRequestDTO;
import com.tradebot.dto.BudgetTargetEventTimelineResponseDTO;
import com.tradebot.dto.BudgetTargetSessionAuditReplayDTO;
import com.tradebot.dto.BudgetTargetSessionDetailDTO;
import com.tradebot.dto.BudgetTargetSessionSummaryDTO;
import com.tradebot.dto.BudgetTargetTradeDetailDTO;
import com.tradebot.dto.BudgetTargetTradeHistoryResponseDTO;
import com.tradebot.operator.RequiresPermission;
import com.tradebot.security.LocalMutationGuard;
import com.tradebot.service.BudgetTargetAutoExecutionAuditQueryService;
import com.tradebot.service.BudgetTargetAutoExecutionLifecycleService;
import com.tradebot.service.BudgetTargetAutoExecutionQueryService;
import com.tradebot.sse.BudgetTargetSessionStreamRegistry;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/budget-target-auto-execution")
@RequiredArgsConstructor
public class BudgetTargetAutoExecutionController {

    private final BudgetTargetAutoExecutionQueryService queryService;
    private final BudgetTargetAutoExecutionAuditQueryService auditQueryService;
    private final BudgetTargetAutoExecutionLifecycleService lifecycleService;
    private final BudgetTargetSessionStreamRegistry sessionStreamRegistry;
    private final LocalMutationGuard localMutationGuard;

    @GetMapping("/state")
    public ResponseEntity<BudgetTargetAutoExecutionStateDTO> getState() {
        return ResponseEntity.ok(queryService.getState());
    }

    @GetMapping("/sessions")
    @RequiresPermission("live.execution.auto_session.audit.view")
    public ResponseEntity<List<BudgetTargetSessionSummaryDTO>> listSessions(
            @RequestParam(name = "limit", required = false, defaultValue = "20") int limit,
            @RequestParam(name = "offset", required = false, defaultValue = "0") int offset) {
        return ResponseEntity.ok(auditQueryService.listSessions(limit, offset));
    }

    @GetMapping("/sessions/{sessionId}")
    @RequiresPermission("live.execution.auto_session.audit.view")
    public ResponseEntity<BudgetTargetSessionDetailDTO> getSessionDetail(@PathVariable UUID sessionId) {
        return ResponseEntity.ok(auditQueryService.getSessionDetail(sessionId));
    }

    @GetMapping("/sessions/{sessionId}/timeline")
    @RequiresPermission("live.execution.auto_session.audit.view")
    public ResponseEntity<BudgetTargetEventTimelineResponseDTO> getTimeline(
            @PathVariable UUID sessionId,
            @RequestParam(name = "limit", required = false, defaultValue = "100") int limit,
            @RequestParam(name = "offset", required = false, defaultValue = "0") int offset,
            @RequestParam(name = "category", required = false) String category,
            @RequestParam(name = "severity", required = false) String severity,
            @RequestParam(name = "executionId", required = false) UUID executionId) {
        return ResponseEntity.ok(auditQueryService.getTimeline(sessionId, limit, offset, category, severity, executionId));
    }

    @GetMapping("/sessions/{sessionId}/trades")
    @RequiresPermission("live.execution.auto_session.audit.view")
    public ResponseEntity<BudgetTargetTradeHistoryResponseDTO> getTradeHistory(
            @PathVariable UUID sessionId,
            @RequestParam(name = "limit", required = false, defaultValue = "50") int limit,
            @RequestParam(name = "offset", required = false, defaultValue = "0") int offset,
            @RequestParam(name = "state", required = false) String state) {
        return ResponseEntity.ok(auditQueryService.getTradeHistory(sessionId, limit, offset, state));
    }

    @GetMapping("/sessions/{sessionId}/trades/{executionId}")
    @RequiresPermission("live.execution.auto_session.audit.view")
    public ResponseEntity<BudgetTargetTradeDetailDTO> getTradeDetail(
            @PathVariable UUID sessionId,
            @PathVariable UUID executionId) {
        return ResponseEntity.ok(auditQueryService.getTradeDetail(sessionId, executionId));
    }

    @GetMapping("/sessions/{sessionId}/audit/replay")
    @RequiresPermission("live.execution.auto_session.audit.view")
    public ResponseEntity<BudgetTargetSessionAuditReplayDTO> getAuditReplay(@PathVariable UUID sessionId) {
        return ResponseEntity.ok(auditQueryService.getAuditReplay(sessionId));
    }

    @GetMapping(value = "/sessions/{sessionId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @RequiresPermission("live.execution.auto_session.audit.view")
    public SseEmitter streamSession(
            @PathVariable UUID sessionId,
            @RequestHeader(value = "Last-Event-ID", required = false, defaultValue = "0") long lastEventIdHeader,
            @RequestParam(name = "lastEventId", required = false) Long lastEventIdParam) {
        long lastEventId = lastEventIdParam != null ? lastEventIdParam : lastEventIdHeader;
        BudgetTargetSessionSummaryDTO summary = auditQueryService.getSessionSummary(sessionId);
        if (summary.getStatus() != null && List.of("STOPPED", "FAILED", "CANCELLED").contains(summary.getStatus())) {
            SseEmitter emitter = new SseEmitter(0L);
            try {
                emitter.send(SseEmitter.event()
                        .name("resync.required")
                        .data(Map.of("sessionId", sessionId.toString(), "message",
                                "Historical sessions do not expose a live stream; reload via REST endpoints.")));
                emitter.complete();
            } catch (Exception ignored) {
            }
            return emitter;
        }
        SseEmitter emitter = new SseEmitter(0L);
        sessionStreamRegistry.getOrCreate(sessionId).registerEmitter(emitter, lastEventId);
        return emitter;
    }

    @PostMapping("/state")
    @RequiresPermission("live.execution.auto_session.manage")
    public ResponseEntity<BudgetTargetAutoExecutionStateDTO> updateState(
            @RequestBody BudgetTargetAutoExecutionStateRequestDTO request,
            @RequestHeader(name = "X-Operator-Id", required = false) String operatorId,
            HttpServletRequest httpServletRequest) {
        localMutationGuard.assertLocal(httpServletRequest);
        lifecycleService.applyCommand(request, operatorId);
        return ResponseEntity.ok(queryService.getState());
    }
}
