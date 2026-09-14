package com.urlshortener.service;

import com.urlshortener.common.exception.BusinessException;
import com.urlshortener.common.exception.ErrorCode;
import com.urlshortener.component.MetricsRedisHelper;
import com.urlshortener.dto.AccessLogPageResponseDto;
import com.urlshortener.dto.UrlMetricsResponseDto;
import com.urlshortener.entity.AccessLog;
import com.urlshortener.entity.ShortUrl;
import com.urlshortener.repository.AccessLogRepository;
import com.urlshortener.repository.ShortUrlRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccessMetricsServiceTest {

    @Mock
    private ShortUrlRepository shortUrlRepository;

    @Mock
    private AccessLogRepository accessLogRepository;

    @Mock
    private MetricsRedisHelper metricsRedisHelper;

    @InjectMocks
    private AccessMetricsService accessMetricsService;

    @Test
    @DisplayName("getUrlMetrics - 即時指標查詢：DB 基礎值 1000 + Redis 增量 50 = 1050 (Spec §7.1)")
    void testGetUrlMetrics_AggregatesDbAndRedis() {
        ShortUrl shortUrl = ShortUrl.builder()
                .shortKey("mdn-302")
                .originalUrl("https://developer.mozilla.org/302")
                .clickCount(1000L)
                .status(1)
                .createdAt(Instant.now().minusSeconds(86400))
                .build();

        AccessLog latestLog = AccessLog.builder()
                .shortKey("mdn-302")
                .accessedAt(Instant.now().minusSeconds(10))
                .build();

        when(shortUrlRepository.findByShortKey("mdn-302")).thenReturn(Optional.of(shortUrl));
        when(metricsRedisHelper.getPendingClickCount("mdn-302")).thenReturn(50L);
        when(accessLogRepository.findTopByShortKeyOrderByAccessedAtDesc("mdn-302")).thenReturn(Optional.of(latestLog));

        UrlMetricsResponseDto metrics = accessMetricsService.getUrlMetrics("mdn-302");

        assertThat(metrics.getShortKey()).isEqualTo("mdn-302");
        assertThat(metrics.getTotalClicks()).isEqualTo(1050L);
        assertThat(metrics.getLastAccessedAt()).isNotNull();
    }

    @Test
    @DisplayName("getUrlMetrics - 短碼不存在時拋出 40401 SHORT_URL_NOT_FOUND (Spec §7.1)")
    void testGetUrlMetrics_NotFound() {
        when(shortUrlRepository.findByShortKey("not-exist")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accessMetricsService.getUrlMetrics("not-exist"))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.SHORT_URL_NOT_FOUND.getCode());
    }

    @Test
    @DisplayName("getAccessLogs - 成功分頁查詢日誌 (Spec §7.1)")
    void testGetAccessLogs_Success() {
        when(shortUrlRepository.existsByShortKey("mdn-302")).thenReturn(true);

        List<AccessLog> logs = List.of(
                AccessLog.builder().id(1L).shortKey("mdn-302").ip("1.1.1.1").userAgent("UA").referer("REF").accessedAt(Instant.now()).build(),
                AccessLog.builder().id(2L).shortKey("mdn-302").ip("2.2.2.2").userAgent("UA").referer("REF").accessedAt(Instant.now()).build()
        );
        PageImpl<AccessLog> page = new PageImpl<>(logs, Pageable.unpaged(), 25);

        when(accessLogRepository.findByShortKeyAndTimeRange(eq("mdn-302"), any(), any(), any()))
                .thenReturn(page);

        AccessLogPageResponseDto response = accessMetricsService.getAccessLogs("mdn-302", 1, 10, null, null);

        assertThat(response.getItems()).hasSize(2);
        assertThat(response.getTotalElements()).isEqualTo(25L);
        assertThat(response.getPage()).isEqualTo(1);
        assertThat(response.getSize()).isEqualTo(10);
    }

    @Test
    @DisplayName("getAccessLogs - 起始時間大於結束時間時拋出 40003 INVALID_TIME_RANGE (Spec §7.1)")
    void testGetAccessLogs_InvalidTimeRange() {
        when(shortUrlRepository.existsByShortKey("mdn-302")).thenReturn(true);

        Instant start = Instant.now();
        Instant end = start.minusSeconds(3600);

        assertThatThrownBy(() -> accessMetricsService.getAccessLogs("mdn-302", 1, 10, start, end))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.INVALID_TIME_RANGE.getCode());
    }

    @Test
    @DisplayName("getAccessLogs - 分頁參數無效時拋出 40004 INVALID_PAGINATION_PARAMS (Spec §7.1)")
    void testGetAccessLogs_InvalidPagination() {
        when(shortUrlRepository.existsByShortKey("mdn-302")).thenReturn(true);

        assertThatThrownBy(() -> accessMetricsService.getAccessLogs("mdn-302", 0, 10, null, null))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.INVALID_PAGINATION_PARAMS.getCode());

        assertThatThrownBy(() -> accessMetricsService.getAccessLogs("mdn-302", 1, 101, null, null))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.INVALID_PAGINATION_PARAMS.getCode());
    }
}
