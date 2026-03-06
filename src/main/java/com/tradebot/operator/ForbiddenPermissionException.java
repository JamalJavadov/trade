package com.tradebot.operator;

import lombok.Getter;

@Getter
public class ForbiddenPermissionException extends RuntimeException {

    private final String permissionKey;
    private final String title;

    public ForbiddenPermissionException(String permissionKey, String title) {
        super("Action blocked by operator permission");
        this.permissionKey = permissionKey;
        this.title = title;
    }
}
