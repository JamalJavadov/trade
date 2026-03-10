package com.tradebot.service;

import com.tradebot.entity.Recommendation;
import com.tradebot.repository.RecommendationRepository;
import com.tradebot.repository.SessionSymbolDecisionAuditRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AutoSessionRecommendationIntakeService {

    private static final List<String> INTAKE_EVENT_TYPES = List.of("INTAKE_ACCEPTED", "INTAKE_REJECTED");
    private static final int PAGE_SIZE = 25;

    private final RecommendationRepository recommendationRepository;
    private final SessionSymbolDecisionAuditRepository sessionSymbolDecisionAuditRepository;

    public Optional<Recommendation> findNewestUnauditedRecommendation(UUID sessionId) {
        int pageNumber = 0;
        while (true) {
            Page<Recommendation> page = recommendationRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(pageNumber, PAGE_SIZE));
            Optional<Recommendation> candidate = page.getContent().stream()
                    .filter(recommendation -> !alreadyEvaluated(sessionId, recommendation.getId()))
                    .findFirst();
            if (candidate.isPresent() || !page.hasNext()) {
                return candidate;
            }
            pageNumber++;
        }
    }

    public boolean alreadyEvaluated(UUID sessionId, UUID recommendationId) {
        if (sessionId == null || recommendationId == null) {
            return false;
        }
        return sessionSymbolDecisionAuditRepository.existsBySession_IdAndRecommendation_IdAndEventTypeIn(
                sessionId,
                recommendationId,
                INTAKE_EVENT_TYPES);
    }
}
