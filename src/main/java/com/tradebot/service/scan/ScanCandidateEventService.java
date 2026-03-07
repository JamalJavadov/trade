package com.tradebot.service.scan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradebot.entity.ScanCandidateEvent;
import com.tradebot.repository.ScanCandidateEventRepository;
import com.tradebot.sse.ScanEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@RequiredArgsConstructor
@Slf4j
public class ScanCandidateEventService {

    private final ScanCandidateEventRepository scanCandidateEventRepository;
    private final ScanEventPublisher scanEventPublisher;
    private final ObjectMapper objectMapper;

    public Recorder recorder(UUID scanRunId, String symbol) {
        return new Recorder(scanRunId, symbol);
    }

    public class Recorder {
        private final UUID scanRunId;
        private final String symbol;
        private final AtomicInteger sequence = new AtomicInteger(0);

        private Recorder(UUID scanRunId, String symbol) {
            this.scanRunId = scanRunId;
            this.symbol = symbol;
        }

        public void record(DeepScanStage stage, String status, Map<String, Object> payload) {
            Instant ts = Instant.now();
            int seq = sequence.incrementAndGet();
            Map<String, Object> safePayload = payload == null ? Map.of() : payload;

            ScanCandidateEvent event = new ScanCandidateEvent();
            event.setScanRunId(scanRunId);
            event.setSymbol(symbol);
            event.setStage(stage.name());
            event.setSeq(seq);
            event.setStatus(status);
            event.setTs(ts);
            event.setPayloadJson(writeJson(safePayload));
            scanCandidateEventRepository.save(event);

            Map<String, Object> eventPayload = new LinkedHashMap<>();
            eventPayload.put("scanRunId", scanRunId.toString());
            eventPayload.put("symbol", symbol);
            eventPayload.put("stage", stage.name());
            eventPayload.put("status", status);
            eventPayload.put("seq", seq);
            eventPayload.put("ts", ts.toString());
            eventPayload.put("payload", safePayload);
            scanEventPublisher.publish(scanRunId, "candidate.stage", eventPayload);
            if (stage == DeepScanStage.AI_COMPARATIVE_REVIEW && !"STARTED".equalsIgnoreCase(status)) {
                scanEventPublisher.publish(scanRunId, "candidate.ai-reviewed", eventPayload);
            }
            if (stage == DeepScanStage.FINAL_GATE && !"STARTED".equalsIgnoreCase(status)) {
                scanEventPublisher.publish(scanRunId, "candidate.gated", eventPayload);
            }
        }

        private String writeJson(Object value) {
            try {
                return objectMapper.writeValueAsString(value);
            } catch (Exception ex) {
                log.warn("Failed to serialize scan candidate event payload for {}: {}", symbol, ex.getMessage());
                return "{}";
            }
        }
    }
}
