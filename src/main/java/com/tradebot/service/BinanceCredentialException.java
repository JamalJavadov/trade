package com.tradebot.service;

public class BinanceCredentialException extends IllegalStateException {

    private final String failureCode;
    private final String credentialSource;
    private final String authMode;

    public BinanceCredentialException(String failureCode, String message, String credentialSource, String authMode) {
        super(message);
        this.failureCode = failureCode;
        this.credentialSource = credentialSource;
        this.authMode = authMode;
    }

    public String getFailureCode() {
        return failureCode;
    }

    public String getCredentialSource() {
        return credentialSource;
    }

    public String getAuthMode() {
        return authMode;
    }
}
