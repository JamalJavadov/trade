package com.tradebot.controller;

import com.tradebot.entity.Recommendation;
import com.tradebot.operator.RequiresPermission;
import com.tradebot.repository.RecommendationRepository;
import com.tradebot.service.StrategyConfigProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/export")
@RequiredArgsConstructor
public class ExportController {

    private final RecommendationRepository recommendationRepository;
    private final StrategyConfigProvider strategyConfigProvider;

    @GetMapping(value = "/journal", produces = "text/csv")
    @RequiresPermission("exports.download")
    public ResponseEntity<String> exportJournalCsv() {
        List<Recommendation> recs = recommendationRepository.findAll();
        StringBuilder csv = new StringBuilder("ID,CreatedAt,Symbol,Side,Status,Confidence,Rationale\n");
        for (Recommendation r : recs) {
            csv.append(String.format("%s,%s,%s,%s,%s,%s,\"%s\"\n",
                    r.getId(),
                    r.getCreatedAt(),
                    r.getSymbol(),
                    r.getSide(),
                    r.getStatus(),
                    r.getConfidenceScore(),
                    r.getRationaleText().replace("\"", "\"\"")));
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"journal_export.csv\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(csv.toString());
    }

    @GetMapping("/analytics")
    @RequiresPermission("exports.download")
    public ResponseEntity<Map<String, Object>> exportAnalytics() {
        Map<String, Object> data = new HashMap<>();
        data.put("activeStrategyConfig", strategyConfigProvider.getActiveConfig());
        data.put("totalRecommendations", recommendationRepository.count());
        long closedTrades = recommendationRepository.findAll().stream().filter(r -> "CLOSED".equals(r.getStatus())).count();
        data.put("totalClosedTrades", closedTrades);
        return ResponseEntity.ok(data);
    }
}
