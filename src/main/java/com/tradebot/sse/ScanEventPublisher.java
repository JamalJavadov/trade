package com.tradebot.sse;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ScanEventPublisher {

    private final ScanStreamRegistry registry;

    public void publish(UUID scanRunId, String type, Object payload) {
        ScanEventStream stream = registry.getOrCreate(scanRunId);
        long eventId = stream.nextId();
        ScanEvent event = new ScanEvent(eventId, type, scanRunId.toString(), payload);
        stream.publish(event);

        if ("scan.finished".equals(type) || "scan.failed".equals(type)) {
            registry.completeAndRemove(scanRunId);
        }
    }
}
