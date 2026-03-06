package com.tradebot.demo.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class DemoMetricsService {

    private final MeterRegistry meterRegistry;
    private final Counter demoTradesOpened;
    private final AtomicReference<Double> pnlGaugeValue = new AtomicReference<>(0.0);

    public DemoMetricsService(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.demoTradesOpened = Counter.builder("demo_trades_opened_total")
                .description("Total opened demo trades")
                .register(meterRegistry);

        Gauge.builder("demo_pnl_total", pnlGaugeValue, AtomicReference::get)
                .description("Total realized demo pnl")
                .register(meterRegistry);
    }

    public void incrementCycle(String status) {
        Counter.builder("demo_cycles_total")
                .tag("status", nullToUnknown(status))
                .register(meterRegistry)
                .increment();
    }

    public void incrementTradeOpened() {
        demoTradesOpened.increment();
    }

    public void incrementTradeClosed(String reason) {
        Counter.builder("demo_trades_closed_total")
                .tag("reason", nullToUnknown(reason))
                .register(meterRegistry)
                .increment();
    }

    public void updateTotalPnl(BigDecimal pnl) {
        pnlGaugeValue.set(pnl == null ? 0.0 : pnl.doubleValue());
    }

    private String nullToUnknown(String value) {
        return value == null || value.isBlank() ? "UNKNOWN" : value;
    }
}
