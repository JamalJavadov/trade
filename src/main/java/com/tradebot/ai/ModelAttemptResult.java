package com.tradebot.ai;

import com.tradebot.exception.ErrorCode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelAttemptResult {
    private String modelRequested;
    private String modelUsed;
    private AiCallStatus status;
    private ErrorCode errorCode;
    private String errorMessage;
    private long latencyMs;
    private AiResponse response;
}
