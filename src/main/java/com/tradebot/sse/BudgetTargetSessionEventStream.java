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
public class BudgetTargetSessionEventStream {
    private static final int MAX_HISTORY = 1000;

    private final AtomicLong eventIdCounter = new AtomicLong(0);
    private final ConcurrentLinkedDeque<BudgetTargetSessionStreamEvent> history = new ConcurrentLinkedDeque<>();
    private final Set<SseEmitter> emitters = new CopyOnWriteArraySet<>();
    private final String sessionId;
    private ScheduledFuture<?> heartbeatTask;
    private boolean completed = false;

    public BudgetTargetSessionEventStream(String sessionId) {
        this.sessionId = sessionId;
    }

    public synchronized void registerEmitter(SseEmitter emitter, long lastEventId) {
        if (completed) {
            emitter.complete();
            return;
        }

        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(error -> emitters.remove(emitter));

        if (lastEventId > 0) {
            replaySince(lastEventId, emitter);
        }
    }

    public void publish(BudgetTargetSessionStreamEvent event) {
        if (completed) {
            return;
        }
        history.addLast(event);
        if (history.size() > MAX_HISTORY) {
            history.removeFirst();
        }
        for (SseEmitter emitter : emitters) {
            sendEvent(emitter, event);
        }
    }

    public long nextId() {
        return eventIdCounter.incrementAndGet();
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

    public void scheduleHeartbeat(ScheduledExecutorService executor) {
        this.heartbeatTask = executor.scheduleAtFixedRate(() -> {
            if (completed) {
                return;
            }
            long id = eventIdCounter.incrementAndGet();
            BudgetTargetSessionStreamEvent heartbeat = new BudgetTargetSessionStreamEvent(
                    id,
                    "heartbeat",
                    sessionId,
                    Map.of("ts", System.currentTimeMillis()));
            for (SseEmitter emitter : emitters) {
                sendEvent(emitter, heartbeat);
            }
        }, 12, 12, TimeUnit.SECONDS);
    }

    private void replaySince(long lastEventId, SseEmitter emitter) {
        long minId = history.isEmpty() ? 0 : history.peekFirst().eventId();
        if (lastEventId > 0 && lastEventId < minId - 1) {
            try {
                emitter.send(SseEmitter.event()
                        .name("resync.required")
                        .data(Map.of("sessionId", sessionId, "message", "Event history expired; reload via REST endpoints.")));
                emitter.complete();
            } catch (IOException ignored) {
                emitters.remove(emitter);
            }
            return;
        }
        for (BudgetTargetSessionStreamEvent event : history) {
            if (event.eventId() > lastEventId) {
                sendEvent(emitter, event);
            }
        }
    }

    private void sendEvent(SseEmitter emitter, BudgetTargetSessionStreamEvent event) {
        try {
            emitter.send(SseEmitter.event()
                    .id(String.valueOf(event.eventId()))
                    .name(event.type())
                    .data(event.payload()));
        } catch (IOException ignored) {
            emitters.remove(emitter);
        }
    }
}
