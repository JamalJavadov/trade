package com.tradebot.controller;

import com.tradebot.dto.StatusResponseDTO;
import com.tradebot.entity.ScanRun;
import com.tradebot.entity.Recommendation;
import com.tradebot.repository.ScanRunRepository;
import com.tradebot.repository.RecommendationRepository;
import com.tradebot.service.AutoScanStateService;
import com.tradebot.service.ScanOrchestrator;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/status")
@RequiredArgsConstructor
public class StatusController {

    private final ScanRunRepository scanRunRepository;
    private final RecommendationRepository recommendationRepository;
    private final ScanOrchestrator scanOrchestrator;
    private final AutoScanStateService autoScanStateService;
    private final Instant bootTime = Instant.now();

    @GetMapping
    public StatusResponseDTO getStatus() {
        Instant now = Instant.now();
        Optional<ScanRun> latestRun = scanRunRepository.findFirstByOrderByStartedAtDesc();
        Optional<Recommendation> latestRecommendation = recommendationRepository.findFirstByOrderByCreatedAtDesc();
        Instant nextRunAt = autoScanStateService.buildState().getNextRunAt();

        StatusResponseDTO dto = new StatusResponseDTO();
        dto.setVersion("1.0.0");
        dto.setServerTime(now);
        dto.setBotTime(now.toString());
        dto.setUptimeSeconds(Duration.between(bootTime, now).toSeconds());
        dto.setScanRunning(scanOrchestrator.isScanRunning());
        dto.setLatestRecommendationId(latestRecommendation.map(Recommendation::getId).orElse(null));

        dto.setLastScanStatus(latestRun.map(ScanRun::getStatus).orElse("IDLE"));
        dto.setLastScanTime(
                latestRun.map(run -> run.getFinishedAt() != null ? run.getFinishedAt() : run.getStartedAt()).orElse(null));

        dto.setNextScanTime(nextRunAt);

        return dto;
    }
}
