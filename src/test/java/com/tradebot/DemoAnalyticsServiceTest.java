package com.tradebot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradebot.demo.entity.DemoAccountEquityEvent;
import com.tradebot.demo.entity.DemoTrade;
import com.tradebot.demo.repository.DemoAccountEquityEventRepository;
import com.tradebot.demo.repository.DemoAnalyticsSnapshotRepository;
import com.tradebot.demo.repository.DemoTradeRepository;
import com.tradebot.demo.service.DemoAnalyticsService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DemoAnalyticsServiceTest {

    @Test
    void computesCoreMetricsFromFixture() {
        DemoTradeRepository tradeRepository = mock(DemoTradeRepository.class);
        DemoAccountEquityEventRepository equityEventRepository = mock(DemoAccountEquityEventRepository.class);
        DemoAnalyticsSnapshotRepository snapshotRepository = mock(DemoAnalyticsSnapshotRepository.class);

        DemoTrade t1 = closedTrade("BTCUSDT", "LONG", new BigDecimal("10"), new BigDecimal("2"), "TP1", 40);
        DemoTrade t2 = closedTrade("ETHUSDT", "SHORT", new BigDecimal("-5"), new BigDecimal("-1"), "SL", 60);
        DemoTrade t3 = closedTrade("SOLUSDT", "LONG", new BigDecimal("20"), new BigDecimal("4"), "TP2", 100);

        when(tradeRepository.findLastClosed(10)).thenReturn(List.of(t3, t2, t1));
        when(snapshotRepository.findFirstByLookbackNAndLastTradeId(10, t3.getId())).thenReturn(Optional.empty());

        DemoAccountEquityEvent e1 = equityEvent(t1.getId(), new BigDecimal("1010"));
        DemoAccountEquityEvent e2 = equityEvent(t2.getId(), new BigDecimal("1005"));
        DemoAccountEquityEvent e3 = equityEvent(t3.getId(), new BigDecimal("1025"));
        when(equityEventRepository.findAllByOrderByCreatedAtAsc()).thenReturn(List.of(e1, e2, e3));

        DemoAnalyticsService service = new DemoAnalyticsService(
                tradeRepository,
                equityEventRepository,
                snapshotRepository,
                new ObjectMapper());

        DemoAnalyticsService.AnalyticsResult result = service.getSummary(10);
        Map<String, Object> metrics = result.metrics();

        assertEquals(3, metrics.get("total"));
        assertEquals(2, metrics.get("wins"));
        assertEquals(1, metrics.get("losses"));
        assertTrue(new BigDecimal(String.valueOf(metrics.get("winRate"))).compareTo(new BigDecimal("0.66")) > 0);
        assertEquals(new BigDecimal("6.000000"), new BigDecimal(String.valueOf(metrics.get("profitFactor"))));
        assertTrue(new BigDecimal(String.valueOf(metrics.get("expectancyR"))).compareTo(new BigDecimal("1.6")) > 0);
        assertTrue(new BigDecimal(String.valueOf(metrics.get("maxDrawdownPct"))).compareTo(BigDecimal.ZERO) > 0);
    }

    private DemoTrade closedTrade(
            String symbol,
            String side,
            BigDecimal pnl,
            BigDecimal r,
            String closeReason,
            int holdMinutes) {
        DemoTrade trade = new DemoTrade();
        trade.setId(UUID.randomUUID());
        trade.setSymbol(symbol);
        trade.setSide(side);
        trade.setStatus("CLOSED");
        trade.setOpenedAt(Instant.parse("2026-01-01T00:00:00Z"));
        trade.setClosedAt(Instant.parse("2026-01-01T00:00:00Z").plusSeconds(holdMinutes * 60L));
        trade.setPnlUsdt(pnl);
        trade.setRMultiple(r);
        trade.setCloseReason(closeReason);
        trade.setSnapshotJson("{\"rrLive\":2.1,\"diagnostics\":{\"sweepDepthRatio\":0.2,\"reclaimStrength\":0.7,\"atrRatio\":1.0}}");
        return trade;
    }

    private DemoAccountEquityEvent equityEvent(UUID tradeId, BigDecimal equity) {
        DemoAccountEquityEvent event = new DemoAccountEquityEvent();
        event.setTradeId(tradeId);
        event.setCreatedAt(Instant.now());
        event.setBalanceUsdt(equity);
        event.setEquityUsdt(equity);
        event.setRealizedPnlUsdt(BigDecimal.ZERO);
        event.setEventType("TRADE_CLOSED");
        return event;
    }
}
