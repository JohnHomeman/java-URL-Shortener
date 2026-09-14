package com.urlshortener.controller;

import com.urlshortener.common.exception.BusinessException;
import com.urlshortener.common.exception.ErrorCode;
import com.urlshortener.common.response.ApiResponse;
import com.urlshortener.dto.AccessLogPageResponseDto;
import com.urlshortener.dto.UrlMetricsResponseDto;
import com.urlshortener.service.AccessMetricsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * 短網址統計指標與存取日誌查詢控制器 (引用 Spec §5.1, §5.2)
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/urls")
@RequiredArgsConstructor
public class UrlMetricsController {

    private final AccessMetricsService accessMetricsService;

    /**
     * 查詢短網址即時統計指標
     * GET /api/v1/urls/{shortKey}/metrics
     */
    @GetMapping("/{shortKey}/metrics")
    public ApiResponse<UrlMetricsResponseDto> getMetrics(@PathVariable("shortKey") String shortKey) {
        UrlMetricsResponseDto metrics = accessMetricsService.getUrlMetrics(shortKey);
        return ApiResponse.success(metrics);
    }

    /**
     * 分頁查詢短網址歷史存取日誌
     * GET /api/v1/urls/{shortKey}/logs
     */
    @GetMapping("/{shortKey}/logs")
    public ApiResponse<AccessLogPageResponseDto> getAccessLogs(
            @PathVariable("shortKey") String shortKey,
            @RequestParam(name = "page", defaultValue = "1") Integer page,
            @RequestParam(name = "size", defaultValue = "20") Integer size,
            @RequestParam(name = "start_time", required = false) String startTimeStr,
            @RequestParam(name = "end_time", required = false) String endTimeStr
    ) {
        Instant startTime = parseInstant(startTimeStr);
        Instant endTime = parseInstant(endTimeStr);

        AccessLogPageResponseDto logs = accessMetricsService.getAccessLogs(shortKey, page, size, startTime, endTime);
        return ApiResponse.success(logs);
    }

    private Instant parseInstant(String timeStr) {
        if (timeStr == null || timeStr.isBlank()) {
            return null;
        }
        try {
            if (timeStr.endsWith("Z") || timeStr.contains("+")) {
                return Instant.parse(timeStr);
            }
            // 嘗試以 ISO 本地日期時間解析為 UTC Instant
            return LocalDateTime.parse(timeStr).toInstant(ZoneOffset.UTC);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INVALID_TIME_RANGE);
        }
    }
}
