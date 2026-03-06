package com.tradebot.controlcenter;

public interface AiRoutingProvider {
    ControlCenterConfig.Mode getLiveRouting();
    ControlCenterConfig.Mode getDemoRouting();
}
