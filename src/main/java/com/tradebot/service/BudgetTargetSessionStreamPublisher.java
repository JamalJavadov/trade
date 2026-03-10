package com.tradebot.service;

import com.tradebot.dto.BudgetTargetSessionStreamEventDTO;
import com.tradebot.entity.BudgetTargetSessionEvent;
import com.tradebot.entity.BudgetTargetSessionStatus;
import com.tradebot.entity.LiveTradeExecutionEvent;
import com.tradebot.entity.SessionSymbolDecisionAudit;
import com.tradebot.sse.BudgetTargetSessionStreamEvent;
import com.tradebot.sse.BudgetTargetSessionStreamRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class BudgetTargetSessionStreamPublisher {

    private final BudgetTargetSessionStreamRegistry streamRegistry;
    private final BudgetTargetAutoExecutionAuditQueryService auditQueryService;

    public void publish(BudgetTargetSessionEvent event) {
        if (event == null || event.getSession() == null || event.getSession().getId() == null) {
            return;
        }
        UUID sessionId = event.getSession().getId();
        try {
            publish(sessionId, "session.update", auditQueryService.toTimelineItem(event));
        } catch (Exception ex) {
            log.debug("Unable to publish budget-target session event for session {}: {}", sessionId, ex.getMessage());
        }
        if (event.getSession().getStatus().isTerminal()) {
            streamRegistry.completeAndRemove(sessionId);
        }
    }

    public void publish(SessionSymbolDecisionAudit event) {
        if (event == null || event.getSession() == null || event.getSession().getId() == null) {
            return;
        }
        UUID sessionId = event.getSession().getId();
        try {
            publish(sessionId, "session.update", auditQueryService.toTimelineItem(event));
        } catch (Exception ex) {
            log.debug("Unable to publish budget-target decision event for session {}: {}", sessionId, ex.getMessage());
        }
    }

    public void publish(LiveTradeExecutionEvent event) {
        if (event == null
                || event.getExecution() == null
                || event.getExecution().getSession() == null
                || event.getExecution().getSession().getId() == null) {
            return;
        }
        UUID sessionId = event.getExecution().getSession().getId();
        try {
            publish(sessionId, "session.update", auditQueryService.toTimelineItem(event));
        } catch (Exception ex) {
            log.debug("Unable to publish budget-target execution event for session {}: {}", sessionId, ex.getMessage());
        }
        if (event.getExecution().getSession().getStatus() == BudgetTargetSessionStatus.STOPPED
                || event.getExecution().getSession().getStatus() == BudgetTargetSessionStatus.CANCELLED
                || event.getExecution().getSession().getStatus() == BudgetTargetSessionStatus.FAILED) {
            streamRegistry.completeAndRemove(sessionId);
        }
    }

    private void publish(UUID sessionId, String type, com.tradebot.dto.BudgetTargetEventTimelineItemDTO timelineItem) {
        try {
            long eventId = streamRegistry.getOrCreate(sessionId).nextId();
            BudgetTargetSessionStreamEventDTO payload = new BudgetTargetSessionStreamEventDTO();
            payload.setEventId(eventId);
            payload.setSessionId(sessionId.toString());
            payload.setSummary(auditQueryService.getSessionSummary(sessionId));
            payload.setTimelineItem(timelineItem);
            streamRegistry.getOrCreate(sessionId).publish(new BudgetTargetSessionStreamEvent(
                    eventId,
                    type,
                    sessionId.toString(),
                    payload));
        } catch (Exception ex) {
            log.debug("Unable to publish budget-target session SSE event for session {}: {}", sessionId, ex.getMessage());
        }
    }
}
