package com.urlshortener.scheduler;

import com.urlshortener.component.MetricsRedisHelper;
import com.urlshortener.dto.AccessEvent;
import com.urlshortener.entity.AccessLog;
import com.urlshortener.repository.AccessLogRepository;
import com.urlshortener.repository.ShortUrlRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 後台定時排程批次回寫 MySQL 服務 (引用 Spec §3.2, §6.3, §6.4)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MetricsPersistenceScheduler {

    private static final int LOG_BATCH_POP_SIZE = 500;

    private final MetricsRedisHelper metricsRedisHelper;
    private final ShortUrlRepository shortUrlRepository;
    private final AccessLogRepository accessLogRepository;

    /**
     * 定時執行批次回寫任務 (每 10 秒執行一次)
     */
    @Scheduled(fixedDelay = 10000, initialDelay = 5000)
    public void persistMetricsToDatabase() {
        try {
            syncClicksToDatabase();
        } catch (Exception e) {
            log.error("Failed to sync clicks to database: {}", e.getMessage(), e);
        }

        try {
            syncAccessLogsToDatabase();
        } catch (Exception e) {
            log.error("Failed to sync access logs to database: {}", e.getMessage(), e);
        }
    }

    /**
     * 原子提取 Redis 點擊數並批次更新 MySQL short_urls (引用 Spec §3.2)
     */
    @Transactional
    public void syncClicksToDatabase() {
        boolean rotated = metricsRedisHelper.rotateClicksForSync();
        if (!rotated) {
            return;
        }

        Map<String, Long> clicksMap = metricsRedisHelper.getAllClicksFromSyncing();
        if (clicksMap.isEmpty()) {
            metricsRedisHelper.deleteSyncingKey();
            return;
        }

        log.info("Persisting clicks for {} short keys to database...", clicksMap.size());
        for (Map.Entry<String, Long> entry : clicksMap.entrySet()) {
            String shortKey = entry.getKey();
            Long delta = entry.getValue();
            if (shortKey != null && delta != null && delta > 0) {
                try {
                    shortUrlRepository.incrementClickCount(shortKey, delta);
                } catch (Exception e) {
                    log.error("Failed to update click count for shortKey: {}, delta: {}", shortKey, delta, e);
                }
            }
        }

        // 回寫完成後刪除暫存鍵
        metricsRedisHelper.deleteSyncingKey();
        log.info("Clicks persistence completed successfully.");
    }

    /**
     * 自 Redis List 批次彈出存取日誌並 JDBC 批次插入 MySQL access_logs (引用 Spec §3.2, §6.4)
     */
    public void syncAccessLogsToDatabase() {
        int totalInserted = 0;
        while (true) {
            List<AccessEvent> events = metricsRedisHelper.popAccessLogs(LOG_BATCH_POP_SIZE);
            if (events == null || events.isEmpty()) {
                break;
            }

            List<AccessLog> accessLogs = new ArrayList<>(events.size());
            for (AccessEvent event : events) {
                accessLogs.add(AccessLog.builder()
                        .shortKey(event.getShortKey())
                        .ip(event.getIp() != null ? event.getIp() : "")
                        .userAgent(event.getUserAgent() != null ? event.getUserAgent() : "")
                        .referer(event.getReferer() != null ? event.getReferer() : "")
                        .accessedAt(event.getAccessedAt() != null ? event.getAccessedAt() : java.time.Instant.now())
                        .build());
            }

            try {
                accessLogRepository.batchInsert(accessLogs);
                totalInserted += accessLogs.size();
            } catch (Exception e) {
                log.error("Failed to batch insert {} access logs to database: {}", accessLogs.size(), e.getMessage(), e);
                break;
            }

            if (events.size() < LOG_BATCH_POP_SIZE) {
                break;
            }
        }

        if (totalInserted > 0) {
            log.info("Successfully persisted {} access logs to database.", totalInserted);
        }
    }
}
