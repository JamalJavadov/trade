package com.tradebot.demo.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
@Slf4j
public class DemoTradeClosedEventListener {

    private final DemoAiSuggestionService aiSuggestionService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTradeClosed(DemoTradeClosedEvent event) {
        try {
            aiSuggestionService.generateBatchIfNeeded();
        } catch (Exception ex) {
            log.warn("Failed to evaluate demo AI trigger after trade close {}: {}", event.tradeId(), ex.getMessage());
        }
    }
}
