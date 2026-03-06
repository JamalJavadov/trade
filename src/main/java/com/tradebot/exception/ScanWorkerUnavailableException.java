package com.tradebot.exception;

public class ScanWorkerUnavailableException extends RuntimeException {
    public ScanWorkerUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
