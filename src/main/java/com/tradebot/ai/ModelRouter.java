package com.tradebot.ai;

import com.tradebot.client.OpenRouterClient;
import com.tradebot.exception.AiProviderException;
import com.tradebot.exception.ErrorCode;
import com.tradebot.trace.TraceIdContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ModelRouter {

    private final OpenRouterClient openRouterClient;
    private final AiRoutingResolver routingResolver;
    private final AiCallLogService aiCallLogService;

    public ModelRouterResult runWithFallback(AiRequest request, AiMode mode) {
        AiRoutingResolver.TaskRoute route = routingResolver.resolveTaskRoute(request.getTaskType(), mode);
        return runWithFallback(request, route, mode);
    }

    public ModelRouterResult runWithFallback(AiRequest request, AiRoutingResolver.TaskRoute routingConfig, AiMode mode) {
        String traceId = currentTraceId();
        List<ModelAttemptResult> attempts = new ArrayList<>();
        String provider = "openrouter.ai";

        List<String> modelChain = routingConfig.modelChain();
        ErrorCode terminalCode = ErrorCode.OPENROUTER_INTERNAL;
        String terminalMessage = "No models configured for AI task.";

        for (String model : modelChain) {
            long startedAt = System.currentTimeMillis();
            try {
                AiResponse response = openRouterClient.generateCompletion(request, model, traceId);
                ModelAttemptResult attempt = ModelAttemptResult.builder()
                        .modelRequested(model)
                        .modelUsed(response.getModelUsed() != null ? response.getModelUsed() : model)
                        .status(AiCallStatus.SUCCESS)
                        .latencyMs(response.getLatencyMs())
                        .response(response)
                        .build();

                attempts.add(attempt);
                aiCallLogService.logAttempt(mode, request, attempt, traceId);

                return ModelRouterResult.builder()
                        .success(true)
                        .response(response)
                        .traceId(traceId)
                        .taskType(request.getTaskType())
                        .provider(provider)
                        .modelUsed(attempt.getModelUsed())
                        .latencyMs(attempt.getLatencyMs())
                        .attempts(attempts)
                        .build();
            } catch (AiProviderException ex) {
                long latencyMs = Math.max(0L, System.currentTimeMillis() - startedAt);
                terminalCode = ex.getErrorCode();
                terminalMessage = ex.getMessage();

                ModelAttemptResult attempt = ModelAttemptResult.builder()
                        .modelRequested(model)
                        .modelUsed(model)
                        .status(AiCallStatus.FAILED)
                        .errorCode(ex.getErrorCode())
                        .errorMessage(ex.getMessage())
                        .latencyMs(latencyMs)
                        .build();
                attempts.add(attempt);
                aiCallLogService.logAttempt(mode, request, attempt, traceId);

                if (!isRetryable(ex.getErrorCode())) {
                    break;
                }
            } catch (Exception ex) {
                long latencyMs = Math.max(0L, System.currentTimeMillis() - startedAt);
                terminalCode = ErrorCode.OPENROUTER_NETWORK;
                terminalMessage = ex.getMessage();

                ModelAttemptResult attempt = ModelAttemptResult.builder()
                        .modelRequested(model)
                        .modelUsed(model)
                        .status(AiCallStatus.FAILED)
                        .errorCode(ErrorCode.OPENROUTER_NETWORK)
                        .errorMessage(ex.getMessage())
                        .latencyMs(latencyMs)
                        .build();
                attempts.add(attempt);
                aiCallLogService.logAttempt(mode, request, attempt, traceId);
                break;
            }
        }

        log.warn("All AI model attempts failed for task={} mode={} traceId={} errorCode={}",
                request.getTaskType(), mode, traceId, terminalCode);

        return ModelRouterResult.builder()
                .success(false)
                .errorCode(terminalCode)
                .errorMessage(terminalMessage)
                .traceId(traceId)
                .taskType(request.getTaskType())
                .provider(provider)
                .modelUsed(lastModelUsed(attempts))
                .latencyMs(lastLatencyMs(attempts))
                .attempts(attempts)
                .build();
    }

    private boolean isRetryable(ErrorCode code) {
        return code == ErrorCode.OPENROUTER_RATE_LIMIT;
    }

    private String lastModelUsed(List<ModelAttemptResult> attempts) {
        if (attempts == null || attempts.isEmpty()) {
            return null;
        }
        ModelAttemptResult last = attempts.get(attempts.size() - 1);
        return last.getModelUsed() != null ? last.getModelUsed() : last.getModelRequested();
    }

    private Long lastLatencyMs(List<ModelAttemptResult> attempts) {
        if (attempts == null || attempts.isEmpty()) {
            return null;
        }
        return attempts.get(attempts.size() - 1).getLatencyMs();
    }

    private String currentTraceId() {
        String traceId = MDC.get(TraceIdContext.TRACE_ID_ATTRIBUTE);
        return traceId != null && !traceId.isBlank() ? traceId : UUID.randomUUID().toString();
    }
}
