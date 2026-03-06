package com.tradebot.security;

import lombok.Getter;

@Getter
public class ForbiddenNotLocalException extends RuntimeException {

    private final String remoteAddress;
    private final String origin;

    public ForbiddenNotLocalException(String remoteAddress, String origin) {
        super("Control-center mutation is allowed only from localhost");
        this.remoteAddress = remoteAddress;
        this.origin = origin;
    }
}
