package com.tradebot.exception;

import lombok.Getter;

@Getter
public class AiProviderException extends RuntimeException {
    private final ErrorCode errorCode;
    private final Integer httpStatus;
    private final String provider;
    private final String retryAfter;

    public AiProviderException(ErrorCode errorCode, Integer httpStatus, String provider, String message,
            String retryAfter, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
        this.provider = provider;
        this.retryAfter = retryAfter;
    }

    public AiProviderException(ErrorCode errorCode, Integer httpStatus, String provider, String message,
            String retryAfter) {
        this(errorCode, httpStatus, provider, message, retryAfter, null);
    }
}
