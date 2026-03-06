package com.tradebot.config;

import com.tradebot.trace.TraceIdContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String traceId = TraceIdContext.resolveOrCreate(request);
        request.setAttribute(TraceIdContext.TRACE_ID_ATTRIBUTE, traceId);
        response.setHeader(TraceIdContext.TRACE_ID_HEADER, traceId);
        MDC.put(TraceIdContext.TRACE_ID_ATTRIBUTE, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            response.setHeader(TraceIdContext.TRACE_ID_HEADER, traceId);
            MDC.remove(TraceIdContext.TRACE_ID_ATTRIBUTE);
        }
    }
}
