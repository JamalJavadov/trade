package com.tradebot.sse;

import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

@Service
public class BudgetTargetSessionStreamRegistry {

    private final ConcurrentHashMap<UUID, BudgetTargetSessionEventStream> streams = new ConcurrentHashMap<>();
    private final ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "budget-target-session-sse-heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    public BudgetTargetSessionEventStream getOrCreate(UUID sessionId) {
        return streams.computeIfAbsent(sessionId, id -> {
            BudgetTargetSessionEventStream stream = new BudgetTargetSessionEventStream(id.toString());
            stream.scheduleHeartbeat(heartbeatExecutor);
            return stream;
        });
    }

    public Optional<BudgetTargetSessionEventStream> get(UUID sessionId) {
        return Optional.ofNullable(streams.get(sessionId));
    }

    public void completeAndRemove(UUID sessionId) {
        BudgetTargetSessionEventStream stream = streams.remove(sessionId);
        if (stream != null) {
            stream.complete();
        }
    }
}
