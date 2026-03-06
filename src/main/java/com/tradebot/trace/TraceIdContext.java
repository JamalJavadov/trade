package com.tradebot.trace;

import jakarta.servlet.http.HttpServletRequest;

import java.util.UUID;

public final class TraceIdContext {

    public static final String TRACE_ID_ATTRIBUTE = "traceId";
    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    private TraceIdContext() {
    }

    public static String resolveOrCreate(HttpServletRequest request) {
        if (request == null) {
            return UUID.randomUUID().toString();
        }

        Object attribute = request.getAttribute(TRACE_ID_ATTRIBUTE);
        if (attribute instanceof String attr && !attr.isBlank()) {
            return attr;
        }

        String inboundHeader = request.getHeader(TRACE_ID_HEADER);
        if (inboundHeader != null && !inboundHeader.isBlank()) {
            return inboundHeader;
        }

        return UUID.randomUUID().toString();
    }
}
