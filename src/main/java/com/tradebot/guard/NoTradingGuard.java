package com.tradebot.guard;

import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import reactor.core.publisher.Mono;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;

@Component
@Slf4j
public class NoTradingGuard {

    public ExchangeFilterFunction preventTradingFilter() {
        return ExchangeFilterFunction.ofRequestProcessor(request -> {
            String path = request.url().getPath().toLowerCase();
            HttpMethod method = request.method();

            if ((method == HttpMethod.POST || method == HttpMethod.PUT || method == HttpMethod.DELETE)
                    && (path.contains("order") || path.contains("leverage") || path.contains("margin"))) {

                log.error(
                        "SECURITY BREACH ATTEMPTED: Blocked a potentially destructive API call to path: {} with method: {}",
                        path, method);
                return Mono.error(new UnsupportedOperationException("Trading is disabled. Bot is in READ-ONLY mode."));
            }
            return Mono.just(request);
        });
    }
}
