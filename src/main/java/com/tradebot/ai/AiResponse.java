package com.tradebot.ai;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiResponse {
    private String modelUsed;
    private String text;
    private String rawJson;
    private Integer tokensIn;
    private Integer tokensOut;
    private long latencyMs;
    private String providerRequestId;
    private String traceId;
}
