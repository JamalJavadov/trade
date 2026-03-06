package com.tradebot.exception;

import lombok.Data;
import java.time.Instant;

@Data
public class ApiErrorResponse {
    private String timestamp;
    private String path;
    private String errorCode;
    private String message;
    private Object details;
    private String traceId;

    public ApiErrorResponse(String path, String errorCode, String message, Object details, String traceId) {
        this.timestamp = Instant.now().toString();
        this.path = path;
        this.errorCode = errorCode;
        this.message = message;
        this.details = details;
        this.traceId = traceId;
    }
}
