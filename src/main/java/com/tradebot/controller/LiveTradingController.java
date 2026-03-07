package com.tradebot.controller;

import com.tradebot.dto.LiveTradeExecutionDTO;
import com.tradebot.dto.LiveTradingPreflightDTO;
import com.tradebot.security.LocalMutationGuard;
import com.tradebot.service.LiveTradingExecutionService;
import com.tradebot.service.LiveTradingPreflightService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import jakarta.servlet.http.HttpServletRequest;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/live-trading")
@RequiredArgsConstructor
public class LiveTradingController {

    private final LiveTradingExecutionService liveTradingExecutionService;
    private final LiveTradingPreflightService liveTradingPreflightService;
    private final LocalMutationGuard localMutationGuard;

    @GetMapping("/executions")
    public ResponseEntity<List<LiveTradeExecutionDTO>> listExecutions(
            @RequestParam(name = "recommendationId", required = false) UUID recommendationId,
            @RequestParam(name = "limit", required = false, defaultValue = "20") int limit) {
        return ResponseEntity.ok(liveTradingExecutionService.listExecutions(recommendationId, limit));
    }

    @GetMapping("/executions/{id}")
    public ResponseEntity<LiveTradeExecutionDTO> getExecution(@PathVariable UUID id) {
        return ResponseEntity.ok(liveTradingExecutionService.getExecution(id));
    }

    @GetMapping("/health")
    public ResponseEntity<LiveTradingPreflightDTO> getHealth(
            @RequestParam(name = "symbol", required = false) String symbol,
            HttpServletRequest httpServletRequest) {
        return ResponseEntity.ok(
                liveTradingPreflightService.evaluateHealth(symbol, localMutationGuard.evaluate(httpServletRequest)));
    }
}
