package com.tradebot.sse;

import org.springframework.stereotype.Service;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

@Service
public class ScanStreamRegistry {

    private final ConcurrentHashMap<UUID, ScanEventStream> streams = new ConcurrentHashMap<>();
    private final ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "sse-heartbeat");
        t.setDaemon(true);
        return t;
    });

    private volatile UUID latestActiveStreamId = null;

    public ScanEventStream getOrCreate(UUID scanRunId) {
        return streams.computeIfAbsent(scanRunId, id -> {
            latestActiveStreamId = id;
            ScanEventStream stream = new ScanEventStream(id.toString());
            stream.scheduleHeartbeat(heartbeatExecutor);
            return stream;
        });
    }

    public Optional<ScanEventStream> get(UUID scanRunId) {
        return Optional.ofNullable(streams.get(scanRunId));
    }

    public Optional<UUID> getLatestActiveStreamId() {
        return Optional.ofNullable(latestActiveStreamId);
    }

    public void completeAndRemove(UUID scanRunId) {
        ScanEventStream stream = streams.remove(scanRunId);
        if (stream != null) {
            stream.complete();
        }
    }
}
