package com.tradebot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradebot.dto.ExplanationDTO;
import com.tradebot.entity.SymbolEvaluation;
import com.tradebot.service.ExplainabilityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ExplainabilityServiceTest {

    private ExplainabilityService service;

    @BeforeEach
    void setUp() {
        service = new ExplainabilityService(new ObjectMapper());
    }

    @Test
    void testExplainValidSetup() {
        SymbolEvaluation ev = new SymbolEvaluation();
        ev.setDecision("VALID");
        ev.setMetricsJson("{\"bias\":\"UPTREND\",\"rr_tp1\":2.5,\"confidence_score\":4.0}");

        ExplanationDTO result = service.explain(ev);

        assertEquals("Valid setup aligned with bias", result.getHeadline());
        assertTrue(result.getTags().contains("VALID"));
        assertEquals(3, result.getBullets().size());
        assertTrue(result.getBullets().get(0).contains("UPTREND"));
        assertTrue(result.getBullets().get(1).contains("2.50"));
        assertTrue(result.getBullets().get(2).contains("4.00"));
    }

    @Test
    void testExplainNoTradeRRLow() {
        SymbolEvaluation ev = new SymbolEvaluation();
        ev.setDecision("NO_TRADE");
        ev.setSkipReasonCode("RR_TOO_LOW");
        ev.setMetricsJson("{\"rr_tp1\":1.5,\"min_rr\":2.0}");

        ExplanationDTO result = service.explain(ev);

        assertTrue(result.getHeadline().contains("Risk/Reward below minimum"));
        assertTrue(result.getTags().contains("RR_TOO_LOW"));
        assertTrue(result.getTags().contains("NO_TRADE"));
        assertEquals(2, result.getBullets().size());
        assertTrue(result.getBullets().get(0).contains("1.50"));
        assertTrue(result.getBullets().get(0).contains("2.00"));
    }

    @Test
    void testExplainNoTradeNoBias() {
        SymbolEvaluation ev = new SymbolEvaluation();
        ev.setDecision("NO_TRADE");
        ev.setSkipReasonCode("NO_BIAS");
        ev.setMetricsJson("{}");

        ExplanationDTO result = service.explain(ev);

        assertTrue(result.getHeadline().contains("lacks clear direction"));
        assertTrue(result.getTags().contains("NO_BIAS"));
        assertEquals(2, result.getBullets().size());
    }
}
