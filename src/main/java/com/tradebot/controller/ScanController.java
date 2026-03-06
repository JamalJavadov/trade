package com.tradebot.controller;

import com.tradebot.dto.AutoScanStateDTO;
import com.tradebot.dto.ExplanationDTO;
import com.tradebot.dto.ScanChartsDTO;
import com.tradebot.dto.ScanReplayDTO;
import com.tradebot.dto.ScanRunStartResponseDTO;
import com.tradebot.dto.ScanSummaryDTO;
import com.tradebot.dto.SymbolEvaluationDetailDTO;
import com.tradebot.dto.SymbolEvaluationRowDTO;
import com.tradebot.operator.RequiresPermission;
import com.tradebot.service.ScanOrchestrator;
import com.tradebot.service.AutoScanStateService;
import com.tradebot.service.ScanQueryService;
import com.tradebot.trace.TraceIdContext;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.UUID;

import com.tradebot.sse.ScanStreamRegistry;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/scans")
@RequiredArgsConstructor
public class ScanController {

    private final ScanOrchestrator scanOrchestrator;
    private final AutoScanStateService autoScanStateService;
    private final ScanQueryService scanQueryService;
    private final ScanStreamRegistry scanStreamRegistry;

    @GetMapping
    public Page<ScanSummaryDTO> getHistoricalScans(
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return scanQueryService.getHistoricalScans(limit, offset);
    }

    @GetMapping("/{scanRunId}/replay")
    public ScanReplayDTO getReplay(@PathVariable UUID scanRunId) {
        return scanQueryService.getReplay(scanRunId);
    }

    @PostMapping("/run-once")
    @RequiresPermission("scan.run_once")
    public ScanRunStartResponseDTO runOnce(HttpServletRequest request) {
        String traceId = TraceIdContext.resolveOrCreate(request);
        ScanOrchestrator.ScanStartResult started = scanOrchestrator.runOnce(traceId);
        ScanRunStartResponseDTO dto = new ScanRunStartResponseDTO();
        dto.setScanRunId(started.scanRunId());
        dto.setStatus(started.status());
        return dto;
    }

    @GetMapping("/autoscan/state")
    public AutoScanStateDTO getAutoScanState() {
        return autoScanStateService.buildState();
    }

    @GetMapping("/latest")
    public ResponseEntity<ScanSummaryDTO> getLatest() {
        return scanQueryService.findLatestScanSummary()
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/{scanRunId}")
    public ScanSummaryDTO getById(@PathVariable UUID scanRunId) {
        return scanQueryService.getScanSummary(scanRunId);
    }

    @GetMapping("/{scanRunId}/evaluations")
    public Page<SymbolEvaluationRowDTO> getEvaluations(
            @PathVariable UUID scanRunId,
            @RequestParam(defaultValue = "ALL") String decision,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "finalScore") String sort,
            @RequestParam(defaultValue = "300") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return scanQueryService.getEvaluations(scanRunId, decision, q, sort, limit, offset);
    }

    @GetMapping("/{scanRunId}/evaluations/{symbol}")
    public SymbolEvaluationDetailDTO getEvaluationDetail(
            @PathVariable UUID scanRunId,
            @PathVariable String symbol) {
        return scanQueryService.getEvaluationDetail(scanRunId, symbol);
    }

    @GetMapping("/{scanRunId}/evaluations/{symbol}/explain")
    public ExplanationDTO getExplanation(
            @PathVariable UUID scanRunId,
            @PathVariable String symbol) {
        return scanQueryService.getExplanation(scanRunId, symbol);
    }

    @GetMapping("/{scanRunId}/charts")
    public ScanChartsDTO getCharts(@PathVariable UUID scanRunId) {
        return scanQueryService.getCharts(scanRunId);
    }

    @GetMapping(value = "/{scanRunId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @RequiresPermission("scan.stream.view")
    public SseEmitter streamScan(
            @PathVariable UUID scanRunId,
            @RequestHeader(value = "Last-Event-ID", required = false, defaultValue = "0") long lastEventId) {
        return scanStreamRegistry.get(scanRunId).map(stream -> {
            SseEmitter emitter = new SseEmitter(0L);
            stream.registerEmitter(emitter, lastEventId);
            return emitter;
        }).orElseGet(() -> {
            SseEmitter emitter = new SseEmitter(0L);
            try {
                emitter.send(SseEmitter.event()
                        .name("resync.required")
                        .data(Map.of("scanRunId", scanRunId.toString(), "message",
                                "Event history expired; reload via REST endpoints.")));
                emitter.complete();
            } catch (Exception e) {
                // Ignore disconnect exceptions
            }
            return emitter;
        });
    }

    @GetMapping(value = "/latest/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @RequiresPermission("scan.stream.view")
    public SseEmitter streamLatestScan(
            @RequestHeader(value = "Last-Event-ID", required = false, defaultValue = "0") long lastEventId) {
        return scanStreamRegistry.getLatestActiveStreamId()
                .map(id -> streamScan(id, lastEventId))
                .orElseGet(() -> {
                    SseEmitter emitter = new SseEmitter(0L);
                    try {
                        emitter.send(SseEmitter.event()
                                .name("resync.required")
                                .data(Map.of("message", "No active scan found, wait for a new scan to start.")));
                        emitter.complete();
                    } catch (Exception e) {
                        // Ignore disconnect exceptions
                    }
                    return emitter;
                });
    }
}
