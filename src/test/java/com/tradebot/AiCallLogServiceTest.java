package com.tradebot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradebot.ai.AiCallLogService;
import com.tradebot.ai.AiCallStatus;
import com.tradebot.ai.AiMode;
import com.tradebot.ai.AiRequest;
import com.tradebot.ai.AiRoutingResolver;
import com.tradebot.ai.AiTaskType;
import com.tradebot.ai.ModelAttemptResult;
import com.tradebot.config.AiProperties;
import com.tradebot.demo.repository.DemoAiCallLogRepository;
import com.tradebot.repository.AiCallLogRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiCallLogServiceTest {

    @Test
    void demoModeWritesOnlyDemoCallLogTable() {
        AiCallLogRepository liveRepository = mock(AiCallLogRepository.class);
        DemoAiCallLogRepository demoRepository = mock(DemoAiCallLogRepository.class);
        AiRoutingResolver routingResolver = mock(AiRoutingResolver.class);
        when(routingResolver.safety()).thenReturn(new AiProperties.Safety());

        AiCallLogService service = new AiCallLogService(
                liveRepository,
                demoRepository,
                new ObjectMapper(),
                routingResolver,
                new SimpleMeterRegistry());

        AiRequest request = AiRequest.builder()
                .taskType(AiTaskType.SUGGESTION_BATCH)
                .userPrompt("demo prompt")
                .build();

        ModelAttemptResult attempt = ModelAttemptResult.builder()
                .modelRequested("model-1")
                .modelUsed("model-1")
                .status(AiCallStatus.SUCCESS)
                .latencyMs(123L)
                .build();

        service.logAttempt(AiMode.DEMO, request, attempt, "trace-demo-1");

        verify(demoRepository).save(any());
        verify(liveRepository, never()).save(any());
    }
}
