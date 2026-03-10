package com.tradebot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradebot.dto.BinanceOrderFieldsDTO;
import com.tradebot.dto.RecommendationDTO;
import com.tradebot.entity.OrderFields;
import com.tradebot.entity.Recommendation;
import com.tradebot.repository.RecommendationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class RecommendationQueryService {

    private static final String WARNING_MISSING_ORDER_FIELDS = "MISSING_ORDER_FIELDS";

    private final RecommendationRepository recommendationRepository;
    private final ObjectMapper objectMapper;

    public RecommendationDTO getLatestRecommendation() {
        Optional<Recommendation> latest = recommendationRepository.findFirstByOrderByCreatedAtDesc();
        return latest.map(this::toDtoSafe).orElse(null);
    }

    public RecommendationDTO getRecommendation(UUID id) {
        Recommendation recommendation = recommendationRepository.findDetailedById(id)
                .orElseThrow(() -> new NoSuchElementException("Recommendation not found: " + id));
        return toDtoSafe(recommendation);
    }

    private RecommendationDTO toDtoSafe(Recommendation rec) {
        RecommendationDTO dto = new RecommendationDTO();
        dto.setId(rec.getId());
        dto.setScanRunId(resolveScanRunId(rec));
        dto.setSymbol(rec.getSymbol());
        dto.setSide(rec.getSide());
        dto.setRationaleText(rec.getRationaleText());
        dto.setConfidenceScore(rec.getConfidenceScore());
        dto.setCreatedAt(rec.getCreatedAt());
        dto.setStatus(rec.getStatus());

        boolean incompleteOrderFields = false;
        OrderFields fields = rec.getOrderFields();
        if (fields == null) {
            incompleteOrderFields = true;
        } else {
            ParseResult entryResult = parseOrderFieldsJson(fields.getEntryOrderJson(), "entryOrderJson");
            dto.setEntryOrder(entryResult.dto());
            incompleteOrderFields = incompleteOrderFields || entryResult.incomplete();

            ParseResult slResult = parseOrderFieldsJson(fields.getSlOrderJson(), "slOrderJson");
            dto.setSlOrder(slResult.dto());
            incompleteOrderFields = incompleteOrderFields || slResult.incomplete();

            ParseResult tpResult = parseOrderFieldsJson(fields.getTpOrderJson(), "tpOrderJson");
            dto.setTpOrder(tpResult.dto());
            incompleteOrderFields = incompleteOrderFields || tpResult.incomplete();

            dto.setLeverageRecommendation(fields.getLeverageRecommendation());
            dto.setPositionMode(fields.getPositionMode());
            dto.setMarginMode(fields.getMarginMode());
        }

        if (incompleteOrderFields) {
            dto.setWarning(WARNING_MISSING_ORDER_FIELDS);
        }
        return dto;
    }

    private UUID resolveScanRunId(Recommendation rec) {
        if (rec == null || rec.getScanRun() == null) {
            return null;
        }
        return rec.getScanRun().getId();
    }

    private ParseResult parseOrderFieldsJson(String orderJson, String fieldName) {
        if (orderJson == null || orderJson.isBlank()) {
            return ParseResult.missing();
        }
        try {
            BinanceOrderFieldsDTO parsed = objectMapper.readValue(orderJson, BinanceOrderFieldsDTO.class);
            return ParseResult.complete(parsed);
        } catch (Exception ex) {
            log.warn("Failed to parse {} for latest recommendation: {}", fieldName, ex.getMessage());
            return ParseResult.missing();
        }
    }

    private record ParseResult(BinanceOrderFieldsDTO dto, boolean incomplete) {
        private static ParseResult complete(BinanceOrderFieldsDTO dto) {
            return new ParseResult(dto, false);
        }

        private static ParseResult missing() {
            return new ParseResult(null, true);
        }
    }
}
