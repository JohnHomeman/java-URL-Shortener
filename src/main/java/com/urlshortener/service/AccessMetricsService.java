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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 短網址統計指標與存取歷史查詢業務服務 (引用 Spec §3.4, §5.1, §5.2)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccessMetricsService {

    private final ShortUrlRepository shortUrlRepository;
    private final AccessLogRepository accessLogRepository;
    private final MetricsRedisHelper metricsRedisHelper;

    /**
     * 查詢短網址即時統計指標 (即時聚合演算法，引用 Spec §3.4, §5.1)
     */
    @Transactional(readOnly = true)
    public UrlMetricsResponseDto getUrlMetrics(String shortKey) {
        if (shortKey == null || shortKey.isBlank()) {
            throw new BusinessException(ErrorCode.SHORT_URL_NOT_FOUND, shortKey);
        }

        ShortUrl shortUrl = shortUrlRepository.findByShortKey(shortKey)
                .orElseThrow(() -> new BusinessException(ErrorCode.SHORT_URL_NOT_FOUND, shortKey));

        // 1. MySQL 持久化基礎點擊數
        long dbClicks = shortUrl.getClickCount() != null ? shortUrl.getClickCount() : 0L;

        // 2. Redis 快取中即時尚未回寫的增量點擊數
        long pendingClicks = metricsRedisHelper.getPendingClickCount(shortKey);

        // 3. 雙層聚合總點擊數
        long totalClicks = dbClicks + pendingClicks;

        // 4. 最新一次存取時間
        Optional<AccessLog> latestLog = accessLogRepository.findTopByShortKeyOrderByAccessedAtDesc(shortKey);
        Instant lastAccessedAt = latestLog.map(AccessLog::getAccessedAt).orElse(null);

        return UrlMetricsResponseDto.builder()
                .shortKey(shortUrl.getShortKey())
                .originalUrl(shortUrl.getOriginalUrl())
                .totalClicks(totalClicks)
                .status(shortUrl.getStatus())
                .createdAt(shortUrl.getCreatedAt())
                .expiredAt(shortUrl.getExpiredAt())
                .lastAccessedAt(lastAccessedAt)
                .build();
    }

    /**
     * 分頁查詢短網址歷史存取明細日誌 (引用 Spec §5.2)
     */
    @Transactional(readOnly = true)
    public AccessLogPageResponseDto getAccessLogs(String shortKey, Integer page, Integer size, Instant startTime, Instant endTime) {
        if (shortKey == null || shortKey.isBlank()) {
            throw new BusinessException(ErrorCode.SHORT_URL_NOT_FOUND, shortKey);
        }

        if (!shortUrlRepository.existsByShortKey(shortKey)) {
            throw new BusinessException(ErrorCode.SHORT_URL_NOT_FOUND, shortKey);
        }

        int targetPage = page != null ? page : 1;
        int targetSize = size != null ? size : 20;

        if (targetPage < 1 || targetSize < 1 || targetSize > 100) {
            throw new BusinessException(ErrorCode.INVALID_PAGINATION_PARAMS);
        }

        if (startTime != null && endTime != null && startTime.isAfter(endTime)) {
            throw new BusinessException(ErrorCode.INVALID_TIME_RANGE);
        }

        Pageable pageable = PageRequest.of(targetPage - 1, targetSize);
        Page<AccessLog> logPage = accessLogRepository.findByShortKeyAndTimeRange(shortKey, startTime, endTime, pageable);

        List<AccessLogPageResponseDto.LogItem> items = logPage.getContent().stream()
                .map(logItem -> AccessLogPageResponseDto.LogItem.builder()
                        .id(logItem.getId())
                        .shortKey(logItem.getShortKey())
                        .ip(logItem.getIp())
                        .userAgent(logItem.getUserAgent())
                        .referer(logItem.getReferer())
                        .accessedAt(logItem.getAccessedAt())
                        .build())
                .toList();

        return AccessLogPageResponseDto.builder()
                .items(items)
                .page(targetPage)
                .size(targetSize)
                .totalElements(logPage.getTotalElements())
                .totalPages(logPage.getTotalPages())
                .build();
    }
}
