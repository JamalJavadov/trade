package com.tradebot.service;

import com.tradebot.entity.TradeExecutionFeedback;
import com.tradebot.repository.TradeExecutionFeedbackRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
@RequiredArgsConstructor
@Slf4j
public class TradeAnalyticsService {

    private final TradeExecutionFeedbackRepository feedbackRepository;

    public record AnalyticsSnapshot(
            int totalTrades,
            double winRate,
            double avgRMultiple,
            double expectancy) {
    }

    public AnalyticsSnapshot getAnalytics(int lastN) {
        List<TradeExecutionFeedback> trades = feedbackRepository.findLastN(lastN);
        if (trades.isEmpty()) {
            return new AnalyticsSnapshot(0, 0, 0, 0);
        }

        int wins = 0;
        double sumR = 0;
        int validRCount = 0;

        for (TradeExecutionFeedback f : trades) {
            if ("WIN".equalsIgnoreCase(f.getUserLabel())) {
                wins++;
            }
            if (f.getRMultiple() != null) {
                sumR += f.getRMultiple().doubleValue();
                validRCount++;
            }
        }

        double winRate = (double) wins / trades.size();
        double avgR = validRCount > 0 ? sumR / validRCount : 0;

        // expectancy approx = winRate * avgR - (1 - winRate) * 1.0
        double expectancy = (winRate * avgR) - ((1 - winRate) * 1.0);

        return new AnalyticsSnapshot(trades.size(), winRate, avgR, expectancy);
    }
}
