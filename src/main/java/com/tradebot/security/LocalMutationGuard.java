package com.tradebot.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;

@Component
public class LocalMutationGuard {

    public void assertLocal(HttpServletRequest request) {
        String remoteAddress = request.getRemoteAddr();
        String forwardedFor = request.getHeader("X-Forwarded-For");
        String origin = request.getHeader("Origin");

        boolean remoteLocal = isLoopback(remoteAddress);
        boolean forwardedLocal = forwardedFor == null || forwardedFor.isBlank() || isForwardedLoopback(forwardedFor);
        boolean originLocal = origin == null || origin.isBlank() || isLocalOrigin(origin);

        if (remoteLocal && forwardedLocal && originLocal) {
            return;
        }

        throw new ForbiddenNotLocalException(remoteAddress, origin);
    }

    private boolean isForwardedLoopback(String forwardedFor) {
        String firstHop = forwardedFor.split(",")[0].trim();
        return isLoopback(firstHop);
    }

    private boolean isLocalOrigin(String origin) {
        try {
            URI uri = URI.create(origin);
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                return false;
            }
            return "localhost".equalsIgnoreCase(host)
                    || "127.0.0.1".equals(host)
                    || "::1".equals(host);
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean isLoopback(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            String raw = value;
            if (raw.startsWith("[") && raw.endsWith("]")) {
                raw = raw.substring(1, raw.length() - 1);
            }
            InetAddress address = InetAddress.getByName(raw);
            return address.isLoopbackAddress();
        } catch (Exception ignored) {
            return false;
        }
    }
}
