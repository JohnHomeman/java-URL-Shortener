package com.urlshortener.common.exception;

import com.urlshortener.controller.HealthController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.springframework.context.annotation.Import;

/**
 * 全域異常處理整合測試 (引用 Spec §7.2 情境 B, 情境 C)
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(GlobalExceptionHandlerTest.TestExceptionController.class)
class GlobalExceptionHandlerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("情境 B：未知路徑攔截 - GET /api/v1/non-existent-path 預期回傳 404, code=40401")
    void testNonExistentPath() throws Exception {
        mockMvc.perform(get("/api/v1/non-existent-path")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40401))
                .andExpect(jsonPath("$.message").value("Resource not found"))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.timestamp").isNumber());
    }

    @Test
    @DisplayName("情境 C：未處理例外攔截 - 預期回傳 500, code=50000, message=Internal server error")
    void testUnhandledException() throws Exception {
        mockMvc.perform(get("/test-error")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(50000))
                .andExpect(jsonPath("$.message").value("Internal server error"))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.timestamp").isNumber());
    }

    /**
     * 測試輔助控制器，用於觸發未處理之 RuntimeException
     */
    @RestController
    static class TestExceptionController {
        @GetMapping("/test-error")
        public void throwError() {
            throw new RuntimeException("Simulated unexpected crash");
        }
    }
}
