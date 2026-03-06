package com.tradebot;

import com.tradebot.repository.ScanPhaseEventRepository;
import com.tradebot.repository.SymbolEvaluationRepository;
import com.tradebot.service.ScanOrchestrator;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@Disabled("Integration test: requires running Postgres and real Binance connectivity. Run manually.")
@SpringBootTest
@ActiveProfiles("test")
class ScanTelemetryIntegrationTest {

    @Autowired
    private ScanOrchestrator scanOrchestrator;

    @Autowired
    private SymbolEvaluationRepository evaluationRepository;

    @Autowired
    private ScanPhaseEventRepository phaseEventRepository;

    @Test
    void scanRunPersistsEvaluationsAndPhaseEvents() {
        scanOrchestrator.runOnce();

        long evaluated = evaluationRepository.count();
        assertTrue(evaluated > 0, "At least one symbol_evaluation row must be persisted");

        long valid = evaluationRepository.countByScanRunIdAndDecision(latestScanRunId(), "VALID");
        long noTrade = evaluationRepository.countByScanRunIdAndDecision(latestScanRunId(), "NO_TRADE");
        assertEquals(evaluated, valid + noTrade, "valid + noTrade must equal total evaluated");

        long phaseEvents = phaseEventRepository.count();
        assertTrue(phaseEvents > 0, "At least one scan_phase_event must be persisted");
    }

    private UUID latestScanRunId() {
        return evaluationRepository.findAll().stream()
                .map(e -> e.getScanRunId())
                .findFirst()
                .orElseThrow();
    }
}
