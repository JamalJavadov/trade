package com.tradebot;

import com.tradebot.config.WebConfig;
import com.tradebot.controller.ScanController;
import com.tradebot.exception.GlobalExceptionHandler;
import com.tradebot.operator.ForbiddenPermissionException;
import com.tradebot.operator.OperatorPermissionInterceptor;
import com.tradebot.operator.OperatorPermissionService;
import com.tradebot.service.AutoScanStateService;
import com.tradebot.service.ScanOrchestrator;
import com.tradebot.service.ScanQueryService;
import com.tradebot.sse.ScanStreamRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ScanController.class)
@Import({ GlobalExceptionHandler.class, WebConfig.class, OperatorPermissionInterceptor.class })
class OperatorPermissionEnforcementWebMvcTest {

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

    @MockBean
    private OperatorPermissionService operatorPermissionService;

    @Test
    void disabledPermissionBlocksEndpointWithForbiddenPermissionError() throws Exception {
        doThrow(new ForbiddenPermissionException("scan.run_once", "Run Scan Once"))
                .when(operatorPermissionService)
                .requirePermissionEnabled("scan.run_once");

        mockMvc.perform(post("/api/v1/scans/run-once"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN_PERMISSION"))
                .andExpect(jsonPath("$.message").value("Action blocked by operator permission"))
                .andExpect(jsonPath("$.details.permissionKey").value("scan.run_once"))
                .andExpect(jsonPath("$.details.title").value("Run Scan Once"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }
}
