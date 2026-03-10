package com.tradebot;

import com.tradebot.entity.BudgetTargetAuditEventCategory;
import com.tradebot.entity.BudgetTargetAuditSeverity;
import com.tradebot.entity.BudgetTargetSession;
import com.tradebot.entity.BudgetTargetSessionEvent;
import com.tradebot.entity.BudgetTargetSessionStatus;
import com.tradebot.service.BudgetTargetAutoExecutionAuditQueryService;
import com.tradebot.service.BudgetTargetSessionStreamPublisher;
import com.tradebot.sse.BudgetTargetSessionStreamRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BudgetTargetSessionStreamPublisherTest {

    @Test
    void sessionEventPublishDoesNotPropagateTimelineProjectionFailures() {
        BudgetTargetSessionStreamRegistry streamRegistry = mock(BudgetTargetSessionStreamRegistry.class);
        BudgetTargetAutoExecutionAuditQueryService auditQueryService = mock(BudgetTargetAutoExecutionAuditQueryService.class);
        BudgetTargetSessionStreamPublisher publisher = new BudgetTargetSessionStreamPublisher(streamRegistry, auditQueryService);

        BudgetTargetSession session = new BudgetTargetSession();
        session.setId(UUID.randomUUID());
        session.setStatus(BudgetTargetSessionStatus.STOPPING);

        BudgetTargetSessionEvent event = new BudgetTargetSessionEvent();
        event.setSession(session);
        event.setEventCategory(BudgetTargetAuditEventCategory.SESSION);
        event.setSeverity(BudgetTargetAuditSeverity.INFO);
        event.setEventTs(Instant.now());

        when(auditQueryService.toTimelineItem(event)).thenThrow(new NullPointerException("boom"));

        assertDoesNotThrow(() -> publisher.publish(event));
        verifyNoInteractions(streamRegistry);
    }
}
