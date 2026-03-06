package com.tradebot.sse;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
public class ScanEventStream {
    private static final int MAX_HISTORY = 1000;

    private final AtomicLong eventIdCounter = new AtomicLong(0);
    private final ConcurrentLinkedDeque<ScanEvent> history = new ConcurrentLinkedDeque<>();
    private final Set<SseEmitter> emitters = new CopyOnWriteArraySet<>();
    private final String scanRunId;
    private ScheduledFuture<?> heartbeatTask;
    private boolean completed = false;

    public ScanEventStream(String scanRunId) {
        this.scanRunId = scanRunId;
    }

    public synchronized void registerEmitter(SseEmitter emitter, long lastEventId) {
        if (completed) {
            emitter.complete();
            return;
        }

        emitters.add(emitter);

        emitter.onCompletion(() -> removeEmitter(emitter));
        emitter.onTimeout(() -> removeEmitter(emitter));
        emitter.onError(e -> removeEmitter(emitter));

        if (lastEventId > 0) {
            replaySince(lastEventId, emitter);
        }
    }

    private void removeEmitter(SseEmitter emitter) {
        emitters.remove(emitter);
    }

    public void publish(ScanEvent event) {
        if (completed)
            return;

        history.addLast(event);
        if (history.size() > MAX_HISTORY) {
            history.removeFirst();
        }

        for (SseEmitter emitter : emitters) {
            sendEvent(emitter, event);
        }
    }

    private void replaySince(long lastEventId, SseEmitter emitter) {
        long minId = history.isEmpty() ? 0 : history.peekFirst().eventId();
        if (lastEventId > 0 && lastEventId < minId - 1) {
            // Client missed too much History
            try {
                emitter.send(SseEmitter.event()
                        .name("resync.required")
                        .data(Map.of("scanRunId", scanRunId,
                                "message", "Event history expired; reload via REST endpoints.")));
                emitter.complete();
            } catch (IOException e) {
                removeEmitter(emitter);
            }
            return;
        }

        for (ScanEvent event : history) {
            if (event.eventId() > lastEventId) {
                sendEvent(emitter, event);
            }
        }
    }

    private void sendEvent(SseEmitter emitter, ScanEvent event) {
        try {
            emitter.send(SseEmitter.event()
                    .id(String.valueOf(event.eventId()))
                    .name(event.type())
                    .data(event.payload()));
        } catch (IOException e) {
            removeEmitter(emitter);
        }
    }

    public void scheduleHeartbeat(ScheduledExecutorService executor) {
        this.heartbeatTask = executor.scheduleAtFixedRate(() -> {
            if (completed)
                return;
            long id = eventIdCounter.incrementAndGet();
            ScanEvent hb = new ScanEvent(id, "heartbeat", scanRunId, Map.of("ts", System.currentTimeMillis()));
            for (SseEmitter emitter : emitters) {
                sendEvent(emitter, hb);
            }
        }, 12, 12, TimeUnit.SECONDS);
    }

    public synchronized void complete() {
        this.completed = true;
        if (heartbeatTask != null) {
            heartbeatTask.cancel(false);
        }
        for (SseEmitter emitter : emitters) {
            emitter.complete();
        }
        emitters.clear();
    }

    public long nextId() {
        return eventIdCounter.incrementAndGet();
    }
}
