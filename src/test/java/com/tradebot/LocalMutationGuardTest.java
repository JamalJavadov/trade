package com.tradebot;

import com.tradebot.security.ForbiddenNotLocalException;
import com.tradebot.security.LocalMutationGuard;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LocalMutationGuardTest {

    private final LocalMutationGuard guard = new LocalMutationGuard();

    @Test
    void allowsLocalFrontendOriginOnLoopbackRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("Origin", "http://localhost:5173");

        assertDoesNotThrow(() -> guard.assertLocal(request));
    }

    @Test
    void rejectsRemoteOriginOnLoopbackRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("Origin", "https://example.com");

        assertThrows(ForbiddenNotLocalException.class, () -> guard.assertLocal(request));
    }
}
