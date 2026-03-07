package com.tradebot.guard;

import com.tradebot.controlcenter.ControlCenterSettingsProvider;
import com.tradebot.exception.BotReadOnlyException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import reactor.core.publisher.Mono;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;

@Component
@Slf4j
@RequiredArgsConstructor
public class NoTradingGuard {

    private final ControlCenterSettingsProvider controlCenterSettingsProvider;

    public ExchangeFilterFunction preventTradingFilter() {
        return ExchangeFilterFunction.ofRequestProcessor(request -> {
            String path = request.url().getPath().toLowerCase();
            HttpMethod method = request.method();

            if (isTradingMutation(method, path) && controlCenterSettingsProvider.isLiveExecutionReadOnly()) {

                log.warn(
                        "Blocked trading mutation because live execution is in READ-ONLY mode: path={} method={}",
                        path, method);
                return Mono.error(new BotReadOnlyException());
            }
            return Mono.just(request);
        });
    }

    private boolean isTradingMutation(HttpMethod method, String path) {
        if (method != HttpMethod.POST && method != HttpMethod.PUT && method != HttpMethod.DELETE) {
            return false;
        }
        if (path == null || path.isBlank() || path.endsWith("/order/test")) {
            return false;
        }
        return path.contains("/order")
                || path.contains("/leverage")
                || path.contains("/margin")
                || path.contains("/positionside");
    }
}
