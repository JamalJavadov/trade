package com.tradebot;

import com.tradebot.config.TraceIdFilter;
import com.tradebot.config.WebConfig;
import com.tradebot.controller.BudgetTargetAutoExecutionController;
import com.tradebot.dto.BudgetTargetAutoExecutionStateDTO;
import com.tradebot.dto.BudgetTargetEventTimelineResponseDTO;
import com.tradebot.dto.BudgetTargetSessionAuditReplayDTO;
import com.tradebot.dto.BudgetTargetSessionDetailDTO;
import com.tradebot.dto.BudgetTargetSessionSummaryDTO;
import com.tradebot.dto.BudgetTargetTradeDetailDTO;
import com.tradebot.dto.BudgetTargetTradeHistoryResponseDTO;
import com.tradebot.exception.GlobalExceptionHandler;
import com.tradebot.operator.OperatorPermissionInterceptor;
import com.tradebot.operator.OperatorPermissionService;
import com.tradebot.security.LocalMutationGuard;
import com.tradebot.service.BudgetTargetAutoExecutionAuditQueryService;
import com.tradebot.service.BudgetTargetAutoExecutionLifecycleService;
import com.tradebot.service.BudgetTargetAutoExecutionQueryService;
import com.tradebot.sse.BudgetTargetSessionEventStream;
import com.tradebot.sse.BudgetTargetSessionStreamEvent;
import com.tradebot.sse.BudgetTargetSessionStreamRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = BudgetTargetAutoExecutionController.class)
@Import({ GlobalExceptionHandler.class, TraceIdFilter.class, WebConfig.class, OperatorPermissionInterceptor.class })
class BudgetTargetAutoExecutionControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private BudgetTargetAutoExecutionQueryService queryService;

    @MockBean
    private BudgetTargetAutoExecutionAuditQueryService auditQueryService;

    @MockBean
    private BudgetTargetAutoExecutionLifecycleService lifecycleService;

    @MockBean
    private BudgetTargetSessionStreamRegistry sessionStreamRegistry;

    @MockBean
    private LocalMutationGuard localMutationGuard;

    @MockBean
    private OperatorPermissionService operatorPermissionService;

    @BeforeEach
    void setUpPermissions() {
        doNothing().when(operatorPermissionService).requirePermissionEnabled(anyString());
    }

    @Test
    void getStateReturnsCurrentSnapshot() throws Exception {
        BudgetTargetAutoExecutionStateDTO state = new BudgetTargetAutoExecutionStateDTO();
        state.getConfig().setEnabled(true);

        when(queryService.getState()).thenReturn(state);

        mockMvc.perform(get("/api/v1/budget-target-auto-execution/state"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.config.enabled").value(true));
    }

    @Test
    void getStateIncludesStructuredStopAndFailureFields() throws Exception {
        BudgetTargetAutoExecutionStateDTO state = new BudgetTargetAutoExecutionStateDTO();
        state.getConfig().setEnabled(true);
        state.setActiveSession(new com.tradebot.dto.BudgetTargetSessionDTO());
        state.getActiveSession().setId(UUID.randomUUID());
        state.getActiveSession().setStatus("STOPPING");
        state.getActiveSession().setStopReason("READ_ONLY_ENABLED");
        state.getActiveSession().setStopReasonMessage("Live execution entered READ-ONLY mode while the session was active.");
        state.getActiveSession().setFailureReasonCode("UPSTREAM_TIMEOUT");
        state.getActiveSession().setFailureReasonMessage("Close-all submission timed out.");
        state.getActiveSession().setExecutionFailureCount(2);

        when(queryService.getState()).thenReturn(state);

        mockMvc.perform(get("/api/v1/budget-target-auto-execution/state"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeSession.stopReason").value("READ_ONLY_ENABLED"))
                .andExpect(jsonPath("$.activeSession.stopReasonMessage")
                        .value("Live execution entered READ-ONLY mode while the session was active."))
                .andExpect(jsonPath("$.activeSession.failureReasonCode").value("UPSTREAM_TIMEOUT"))
                .andExpect(jsonPath("$.activeSession.failureReasonMessage").value("Close-all submission timed out."))
                .andExpect(jsonPath("$.activeSession.executionFailureCount").value(2));
    }

    @Test
    void getSessionsReturnsAuditSummaries() throws Exception {
        BudgetTargetSessionSummaryDTO summary = summary("RUNNING");
        when(auditQueryService.listSessions(10, 0)).thenReturn(List.of(summary));

        mockMvc.perform(get("/api/v1/budget-target-auto-execution/sessions")
                        .param("limit", "10")
                        .param("offset", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(summary.getId().toString()))
                .andExpect(jsonPath("$[0].startReason").value("operator-start"))
                .andExpect(jsonPath("$[0].activeTradeCount").value(1));
    }

    @Test
    void getSessionDetailReturnsAuditReport() throws Exception {
        UUID sessionId = UUID.randomUUID();
        BudgetTargetSessionDetailDTO detail = new BudgetTargetSessionDetailDTO();
        detail.setSummary(summary("STOPPED"));
        detail.setTraceId("trace-session");
        detail.setTimelineEventCount(7);
        detail.setTradeCount(2);
        when(auditQueryService.getSessionDetail(sessionId)).thenReturn(detail);

        mockMvc.perform(get("/api/v1/budget-target-auto-execution/sessions/{sessionId}", sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.traceId").value("trace-session"))
                .andExpect(jsonPath("$.timelineEventCount").value(7))
                .andExpect(jsonPath("$.summary.status").value("STOPPED"));
    }

    @Test
    void getTimelineTradesTradeDetailAndReplayExposeAuditPayloads() throws Exception {
        UUID sessionId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();

        BudgetTargetEventTimelineResponseDTO timeline = new BudgetTargetEventTimelineResponseDTO();
        timeline.setTotal(3);
        timeline.setFilteredCount(1);
        when(auditQueryService.getTimeline(sessionId, 25, 5, "EXCHANGE", "ERROR", executionId)).thenReturn(timeline);

        BudgetTargetTradeHistoryResponseDTO trades = new BudgetTargetTradeHistoryResponseDTO();
        trades.setTotal(2);
        trades.setActiveCount(1);
        trades.setCompletedCount(1);
        when(auditQueryService.getTradeHistory(sessionId, 50, 0, "ACTIVE")).thenReturn(trades);

        BudgetTargetTradeDetailDTO tradeDetail = new BudgetTargetTradeDetailDTO();
        tradeDetail.setGeneratedAt(Instant.parse("2026-03-09T00:10:00Z"));
        when(auditQueryService.getTradeDetail(sessionId, executionId)).thenReturn(tradeDetail);

        BudgetTargetSessionAuditReplayDTO replay = new BudgetTargetSessionAuditReplayDTO();
        replay.setGeneratedAt(Instant.parse("2026-03-09T00:11:00Z"));
        when(auditQueryService.getAuditReplay(sessionId)).thenReturn(replay);

        mockMvc.perform(get("/api/v1/budget-target-auto-execution/sessions/{sessionId}/timeline", sessionId)
                        .param("limit", "25")
                        .param("offset", "5")
                        .param("category", "EXCHANGE")
                        .param("severity", "ERROR")
                        .param("executionId", executionId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.filteredCount").value(1));

        mockMvc.perform(get("/api/v1/budget-target-auto-execution/sessions/{sessionId}/trades", sessionId)
                        .param("state", "ACTIVE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeCount").value(1))
                .andExpect(jsonPath("$.completedCount").value(1));

        mockMvc.perform(get("/api/v1/budget-target-auto-execution/sessions/{sessionId}/trades/{executionId}", sessionId, executionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.generatedAt").value("2026-03-09T00:10:00Z"));

        mockMvc.perform(get("/api/v1/budget-target-auto-execution/sessions/{sessionId}/audit/replay", sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.generatedAt").value("2026-03-09T00:11:00Z"));
    }

    @Test
    void streamEndpointReplaysEventsUsingBrowserFriendlyLastEventIdQueryParam() throws Exception {
        UUID sessionId = UUID.randomUUID();
        when(auditQueryService.getSessionSummary(sessionId)).thenReturn(summary("RUNNING"));

        BudgetTargetSessionEventStream stream = new BudgetTargetSessionEventStream(sessionId.toString());
        stream.publish(new BudgetTargetSessionStreamEvent(1, "session.update", sessionId.toString(), Map.of(
                "eventId", 1,
                "sessionId", sessionId.toString(),
                "summary", Map.of("status", "RUNNING"))));
        stream.publish(new BudgetTargetSessionStreamEvent(2, "session.update", sessionId.toString(), Map.of(
                "eventId", 2,
                "sessionId", sessionId.toString(),
                "summary", Map.of("status", "STOPPING"))));
        when(sessionStreamRegistry.getOrCreate(sessionId)).thenReturn(stream);

        MvcResult result = mockMvc.perform(get("/api/v1/budget-target-auto-execution/sessions/{sessionId}/stream", sessionId)
                        .param("lastEventId", "1")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andReturn();

        String content = result.getResponse().getContentAsString();
        assertTrue(content.contains("id:2"));
        assertTrue(content.contains("event:session.update"));
        assertTrue(!content.contains("id:1"));
    }

    @Test
    void streamEndpointReturnsResyncForHistoricalSessions() throws Exception {
        UUID sessionId = UUID.randomUUID();
        when(auditQueryService.getSessionSummary(sessionId)).thenReturn(summary("STOPPED"));

        MvcResult result = mockMvc.perform(get("/api/v1/budget-target-auto-execution/sessions/{sessionId}/stream", sessionId)
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isOk())
                .andReturn();

        String content = result.getResponse().getContentAsString();
        assertTrue(content.contains("event:resync.required"));
        assertTrue(content.contains("Historical sessions do not expose a live stream"));
    }

    @Test
    void postStatePassesSessionBudgetAndTargetToLifecycleService() throws Exception {
        BudgetTargetAutoExecutionStateDTO state = new BudgetTargetAutoExecutionStateDTO();
        state.getConfig().setEnabled(true);

        doNothing().when(localMutationGuard).assertLocal(any());
        when(queryService.getState()).thenReturn(state);

        mockMvc.perform(post("/api/v1/budget-target-auto-execution/state")
                        .header("X-Operator-Id", "operator-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "command": "TURN_ON",
                                  "budgetAmountUsdt": 125.5,
                                  "targetProfitUsdt": 18.25
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.config.enabled").value(true));

        ArgumentCaptor<com.tradebot.dto.BudgetTargetAutoExecutionStateRequestDTO> requestCaptor =
                ArgumentCaptor.forClass(com.tradebot.dto.BudgetTargetAutoExecutionStateRequestDTO.class);
        verify(lifecycleService).applyCommand(requestCaptor.capture(), eq("operator-1"));
        assertEquals(new BigDecimal("125.5"), requestCaptor.getValue().getBudgetAmountUsdt());
        assertEquals(new BigDecimal("18.25"), requestCaptor.getValue().getTargetProfitUsdt());
    }

    private BudgetTargetSessionSummaryDTO summary(String status) {
        BudgetTargetSessionSummaryDTO summary = new BudgetTargetSessionSummaryDTO();
        summary.setId(UUID.randomUUID());
        summary.setStatus(status);
        summary.setStartedAt(Instant.parse("2026-03-09T00:00:00Z"));
        summary.setStartedBy("operator-1");
        summary.setStartReason("operator-start");
        summary.setBudgetAmountUsdt(new BigDecimal("50"));
        summary.setTargetProfitUsdt(new BigDecimal("10"));
        summary.setRealizedNetPnlUsdt(new BigDecimal("4.25"));
        summary.setTotalGrossPnlUsdt(new BigDecimal("4.60"));
        summary.setFeeTotalUsdt(new BigDecimal("0.35"));
        summary.setWinCount(1);
        summary.setLossCount(0);
        summary.setActiveTradeCount(1);
        summary.setCompletedTradeCount(1);
        summary.setStopReason(status.equals("STOPPED") ? "TARGET_REACHED" : null);
        return summary;
    }
}
