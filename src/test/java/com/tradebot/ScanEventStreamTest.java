package com.tradebot;

import com.tradebot.sse.ScanEvent;
import com.tradebot.sse.ScanEventStream;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ScanEventStreamTest {

    @Test
    public void testBufferingAndReplay() throws Exception {
        String scanRunId = UUID.randomUUID().toString();
        ScanEventStream stream = new ScanEventStream(scanRunId);

        // Publish 5 events
        for (int i = 1; i <= 5; i++) {
            stream.publish(new ScanEvent(i, "test.event", scanRunId, Map.of("val", i)));
        }

        // Test reconnect asking for everything after Last-Event-ID=2
        TestSseEmitter emitter = new TestSseEmitter();
        stream.registerEmitter(emitter, 2);

        List<SseEmitter.SseEventBuilder> sent = emitter.getSentEvents();
        assertEquals(3, sent.size(), "Should have replayed events 3, 4, and 5");
    }

    // Very simple spy
    static class TestSseEmitter extends SseEmitter {
        private final List<SseEventBuilder> sentEvents = new ArrayList<>();

        public TestSseEmitter() {
            super(0L);
        }

        @Override
        public void send(SseEventBuilder builder) {
            sentEvents.add(builder);
        }

        public List<SseEventBuilder> getSentEvents() {
            return sentEvents;
        }
    }
}
