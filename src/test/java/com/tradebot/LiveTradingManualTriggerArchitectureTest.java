package com.tradebot;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LiveTradingManualTriggerArchitectureTest {

    @Test
    void signedBinanceSubmissionMethodsAreOnlyUsedByManualExecutionService() throws IOException {
        assertEquals(Set.of(
                        "src/main/java/com/tradebot/client/BinanceClient.java",
                        "src/main/java/com/tradebot/service/LiveTradingExecutionService.java"),
                filesContaining("submitOrder("));
        assertEquals(Set.of(
                        "src/main/java/com/tradebot/client/BinanceClient.java",
                        "src/main/java/com/tradebot/service/LiveTradingExecutionService.java"),
                filesContaining("submitAlgoOrder("));
        assertEquals(Set.of(
                        "src/main/java/com/tradebot/client/BinanceClient.java",
                        "src/main/java/com/tradebot/service/LiveTradingReconciliationService.java"),
                filesContaining("getAlgoOrder("));
        assertEquals(Set.of(
                        "src/main/java/com/tradebot/client/BinanceClient.java",
                        "src/main/java/com/tradebot/service/LiveTradingReconciliationService.java"),
                filesContaining("getOpenAlgoOrders("));
        assertEquals(Set.of(
                        "src/main/java/com/tradebot/client/BinanceClient.java",
                        "src/main/java/com/tradebot/service/LiveTradingExecutionService.java",
                        "src/main/java/com/tradebot/service/LiveTradingReconciliationService.java"),
                filesContaining("getPositionRisk("));
        assertEquals(Set.of(
                        "src/main/java/com/tradebot/service/LiveTradingExecutionService.java"),
                filesContaining("binanceClient.setLeverage("));
        assertEquals(Set.of(
                        "src/main/java/com/tradebot/service/LiveTradingExecutionService.java"),
                filesContaining("binanceClient.ensureIsolatedMargin("));
        assertEquals(Set.of(
                        "src/main/java/com/tradebot/service/LiveTradingExecutionService.java"),
                filesContaining("binanceClient.ensureOneWayPositionMode("));
    }

    private Set<String> filesContaining(String token) throws IOException {
        try (var paths = Files.walk(Path.of("src/main/java/com/tradebot"))) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> contains(path, token))
                    .map(path -> path.toString().replace('\\', '/'))
                    .collect(Collectors.toSet());
        }
    }

    private boolean contains(Path path, String token) {
        try {
            return Files.readString(path).contains(token);
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }
}
