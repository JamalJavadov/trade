package com.tradebot;

import com.tradebot.exception.GlobalExceptionHandler;
import com.tradebot.operator.OperatorPermissionController;
import com.tradebot.operator.OperatorPermissionItemDTO;
import com.tradebot.operator.OperatorPermissionService;
import com.tradebot.security.ForbiddenNotLocalException;
import com.tradebot.security.LocalMutationGuard;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = OperatorPermissionController.class)
@Import(GlobalExceptionHandler.class)
class OperatorPermissionControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private OperatorPermissionService operatorPermissionService;

    @MockBean
    private LocalMutationGuard localMutationGuard;

    @Test
    void getPermissionsReturnsArrayShapeWithItems() throws Exception {
        when(operatorPermissionService.listPermissions()).thenReturn(List.of(
                new OperatorPermissionItemDTO(
                        "scan.run_once",
                        "Run Scan Once",
                        "Allows one manual scan cycle.",
                        "SCAN",
                        "MED",
                        true,
                        null,
                        "bootstrap")));

        mockMvc.perform(get("/api/v1/operator/permissions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].key").value("scan.run_once"));
    }

    @Test
    void localhostPermissionUpdateSucceedsWithoutAdminToken() throws Exception {
        when(operatorPermissionService.updatePermissions(any(), eq(null))).thenReturn(List.of(
                new OperatorPermissionItemDTO(
                        "scan.run_once",
                        "Run Scan Once",
                        "Allows one manual scan cycle.",
                        "SCAN",
                        "MED",
                        true,
                        null,
                        "local-operator")));

        mockMvc.perform(post("/api/v1/operator/permissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "updates": [
                                    { "key": "scan.run_once", "enabled": true }
                                  ]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].key").value("scan.run_once"))
                .andExpect(jsonPath("$[0].enabled").value(true));
    }

    @Test
    void nonLocalPermissionUpdateIsBlocked() throws Exception {
        doThrow(new ForbiddenNotLocalException("10.0.0.5", "http://evil.com"))
                .when(localMutationGuard)
                .assertLocal(any());

        mockMvc.perform(post("/api/v1/operator/permissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "updates": [
                                    { "key": "scan.run_once", "enabled": false }
                                  ]
                                }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN_NOT_LOCAL"))
                .andExpect(jsonPath("$.message").value("Mutation blocked: endpoint is local-only"));
    }
}
