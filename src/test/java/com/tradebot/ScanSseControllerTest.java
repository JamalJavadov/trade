package com.tradebot;

import com.tradebot.controller.ScanController;
import com.tradebot.service.AutoScanStateService;
import com.tradebot.service.ScanOrchestrator;
import com.tradebot.service.ScanQueryService;
import com.tradebot.sse.ScanEvent;
import com.tradebot.sse.ScanEventStream;
import com.tradebot.sse.ScanStreamRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ScanController.class)
public class ScanSseControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ScanOrchestrator scanOrchestrator;

    @MockBean
    private ScanQueryService scanQueryService;

    @MockBean
    private AutoScanStateService autoScanStateService;

    @MockBean
    private ScanStreamRegistry scanStreamRegistry;

    @Test
    public void testStreamEndpointWithReplay() throws Exception {
        UUID scanRunId = UUID.randomUUID();

        ScanEventStream stream = new ScanEventStream(scanRunId.toString());
        stream.publish(new ScanEvent(1, "scan.started", scanRunId.toString(), Map.of("foo", "bar")));
        stream.publish(new ScanEvent(2, "phase.started", scanRunId.toString(), Map.of("phase", "EXCHANGE_INFO")));

        Mockito.when(scanStreamRegistry.get(eq(scanRunId)))
                .thenReturn(Optional.of(stream));

        MvcResult result = mockMvc.perform(get("/api/v1/scans/{id}/stream", scanRunId)
                .header("Last-Event-ID", "1")
                .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andReturn();

        String content = result.getResponse().getContentAsString();

        // It should ONLY contain event 2 because Last-Event-ID=1
        assertTrue(content.contains("id:2"));
        assertTrue(content.contains("event:phase.started"));
        assertTrue(!content.contains("scan.started"));
    }

    @Test
    public void latestEndpointReturnsNoContentWhenNoRuns() throws Exception {
        Mockito.when(scanQueryService.findLatestScanSummary()).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/scans/latest"))
                .andExpect(status().isNoContent());
    }
}
