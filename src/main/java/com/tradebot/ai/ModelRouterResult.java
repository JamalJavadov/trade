package com.tradebot.ai;

import com.tradebot.exception.ErrorCode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelRouterResult {
    private boolean success;
    private AiResponse response;
    private ErrorCode errorCode;
    private String errorMessage;
    private String traceId;
    private AiTaskType taskType;
    private String provider;
    private String modelUsed;
    private Long latencyMs;

    @Builder.Default
    private List<ModelAttemptResult> attempts = new ArrayList<>();
}
