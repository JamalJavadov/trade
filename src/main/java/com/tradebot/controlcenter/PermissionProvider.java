package com.tradebot.controlcenter;

public interface PermissionProvider {
    boolean can(String key);
}
