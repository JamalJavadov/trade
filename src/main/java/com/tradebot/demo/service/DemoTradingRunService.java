package com.tradebot.demo.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DemoTradingRunService {

    private final DemoOrchestrator demoOrchestrator;

    public DemoOrchestrator.CycleResult executeOneCycle(String trigger) {
        return demoOrchestrator.runCycle(trigger);
    }
}
