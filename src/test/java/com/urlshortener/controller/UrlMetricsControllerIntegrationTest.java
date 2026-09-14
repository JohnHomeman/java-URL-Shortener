package com.urlshortener.controller;

import com.urlshortener.common.exception.BusinessException;
import com.urlshortener.common.exception.ErrorCode;
import com.urlshortener.dto.AccessLogPageResponseDto;
import com.urlshortener.dto.UrlMetricsResponseDto;
import com.urlshortener.service.AccessMetricsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class UrlMetricsControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AccessMetricsService accessMetricsService;

    @Test
    @DisplayName("GET /api/v1/urls/{shortKey}/metrics - 成功回傳統計指標 (Spec §5.1)")
    void testGetMetrics_Success() throws Exception {
        UrlMetricsResponseDto mockResponse = UrlMetricsResponseDto.builder()
                .shortKey("mdn-302")
                .originalUrl("https://developer.mozilla.org/302")
                .totalClicks(1582L)
                .status(1)
                .createdAt(Instant.parse("2026-09-07T10:00:00Z"))
                .lastAccessedAt(Instant.parse("2026-09-09T14:30:00Z"))
                .build();

        when(accessMetricsService.getUrlMetrics("mdn-302")).thenReturn(mockResponse);

        mockMvc.perform(get("/api/v1/urls/mdn-302/metrics")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.data.short_key").value("mdn-302"))
                .andExpect(jsonPath("$.data.original_url").value("https://developer.mozilla.org/302"))
                .andExpect(jsonPath("$.data.total_clicks").value(1582))
                .andExpect(jsonPath("$.data.status").value(1));
    }

    @Test
    @DisplayName("GET /api/v1/urls/{shortKey}/metrics - 短碼不存在時回傳 404 (Spec §5.1)")
    void testGetMetrics_NotFound() throws Exception {
        when(accessMetricsService.getUrlMetrics("notExist123"))
                .thenThrow(new BusinessException(ErrorCode.SHORT_URL_NOT_FOUND, "notExist123"));

        mockMvc.perform(get("/api/v1/urls/notExist123/metrics")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40401))
                .andExpect(jsonPath("$.message").value("Short URL not found: notExist123"));
    }

    @Test
    @DisplayName("GET /api/v1/urls/{shortKey}/logs - 成功分頁回傳存取日誌 (Spec §5.2)")
    void testGetAccessLogs_Success() throws Exception {
        AccessLogPageResponseDto.LogItem item = AccessLogPageResponseDto.LogItem.builder()
                .id(89521L)
                .shortKey("mdn-302")
                .ip("203.0.113.195")
                .userAgent("Mozilla/5.0")
                .referer("https://google.com")
                .accessedAt(Instant.parse("2026-09-09T14:30:00Z"))
                .build();

        AccessLogPageResponseDto pageResponse = AccessLogPageResponseDto.builder()
                .items(List.of(item))
                .page(1)
                .size(20)
                .totalElements(1580L)
                .totalPages(79)
                .build();

        when(accessMetricsService.getAccessLogs(eq("mdn-302"), eq(1), eq(20), any(), any()))
                .thenReturn(pageResponse);

        mockMvc.perform(get("/api/v1/urls/mdn-302/logs?page=1&size=20")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.items[0].id").value(89521))
                .andExpect(jsonPath("$.data.items[0].ip").value("203.0.113.195"))
                .andExpect(jsonPath("$.data.total_elements").value(1580))
                .andExpect(jsonPath("$.data.total_pages").value(79));
    }

    @Test
    @DisplayName("GET /api/v1/urls/{shortKey}/logs - 無效時間區間回傳 400 (Spec §5.2)")
    void testGetAccessLogs_InvalidTimeRange() throws Exception {
        when(accessMetricsService.getAccessLogs(eq("mdn-302"), any(), any(), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.INVALID_TIME_RANGE));

        mockMvc.perform(get("/api/v1/urls/mdn-302/logs?start_time=2026-09-10T00:00:00&end_time=2026-09-01T00:00:00")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40003));
    }

    @Test
    @DisplayName("GET /api/v1/urls/{shortKey}/logs - 無效分頁參數回傳 400 (Spec §5.2)")
    void testGetAccessLogs_InvalidPagination() throws Exception {
        when(accessMetricsService.getAccessLogs(eq("mdn-302"), eq(0), any(), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.INVALID_PAGINATION_PARAMS));

        mockMvc.perform(get("/api/v1/urls/mdn-302/logs?page=0&size=20")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40004));
    }
}
