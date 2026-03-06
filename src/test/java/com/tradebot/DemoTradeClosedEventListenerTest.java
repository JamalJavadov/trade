package com.tradebot;

import com.tradebot.demo.service.DemoAiSuggestionService;
import com.tradebot.demo.service.DemoTradeClosedEvent;
import com.tradebot.demo.service.DemoTradeClosedEventListener;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class DemoTradeClosedEventListenerTest {

    @Test
    void listenerSwallowsAiErrorsSoLifecycleCannotBreak() {
        DemoAiSuggestionService aiSuggestionService = mock(DemoAiSuggestionService.class);
        DemoTradeClosedEventListener listener = new DemoTradeClosedEventListener(aiSuggestionService);
        doThrow(new IllegalStateException("router failed"))
                .when(aiSuggestionService)
                .generateBatchIfNeeded();

        assertDoesNotThrow(() -> listener.onTradeClosed(new DemoTradeClosedEvent(UUID.randomUUID())));
        verify(aiSuggestionService).generateBatchIfNeeded();
    }
}
