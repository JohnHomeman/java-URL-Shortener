package com.urlshortener.service;

import com.urlshortener.component.MetricsRedisHelper;
import com.urlshortener.dto.AccessEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class MetricsBufferServiceTest {

    @Mock
    private MetricsRedisHelper metricsRedisHelper;

    private MetricsBufferService metricsBufferService;

    @BeforeEach
    void setUp() {
        metricsBufferService = new MetricsBufferService(metricsRedisHelper);
        metricsBufferService.startWorker();
    }

    @AfterEach
    void tearDown() {
        metricsBufferService.shutdownAndFlush();
    }

    @Test
    @DisplayName("recordAccessEvent - 成功將存取事件推入內部記憶體佇列")
    void testRecordAccessEvent() {
        AccessEvent event = AccessEvent.builder()
                .shortKey("mdn-302")
                .ip("127.0.0.1")
                .userAgent("Mozilla/5.0")
                .referer("https://google.com")
                .accessedAt(Instant.now())
                .build();

        boolean result = metricsBufferService.recordAccessEvent(event);

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("shutdownAndFlush - 優雅關機時排空記憶體緩衝區並同步 Flush 至 Redis (Spec §7.1)")
    void testShutdownAndFlush_DrainsRemainingEvents() {
        for (int i = 0; i < 50; i++) {
            AccessEvent event = AccessEvent.builder()
                    .shortKey("batch-test")
                    .ip("192.168.1." + i)
                    .userAgent("Unit-Test")
                    .referer("https://example.com")
                    .accessedAt(Instant.now())
                    .build();
            metricsBufferService.recordAccessEvent(event);
        }

        // 觸發優雅關機
        metricsBufferService.shutdownAndFlush();

        // 驗證記憶體佇列完全為空
        assertThat(metricsBufferService.getQueueSize()).isZero();

        // 驗證 Redis 操作已被呼叫
        verify(metricsRedisHelper, atLeastOnce()).incrementClicks(any());
        verify(metricsRedisHelper, atLeastOnce()).pushAccessLogs(any());
    }

    @Test
    @DisplayName("flushBatchToRedis - 點擊數正確聚合並呼叫 Redis Helper")
    void testFlushBatchToRedis_AggregatesCounts() {
        List<AccessEvent> events = List.of(
                AccessEvent.builder().shortKey("key1").build(),
                AccessEvent.builder().shortKey("key1").build(),
                AccessEvent.builder().shortKey("key2").build()
        );

        metricsBufferService.flushBatchToRedis(events);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Long>> captor = ArgumentCaptor.forClass(Map.class);
        verify(metricsRedisHelper).incrementClicks(captor.capture());

        Map<String, Long> captured = captor.getValue();
        assertThat(captured).containsEntry("key1", 2L);
        assertThat(captured).containsEntry("key2", 1L);
        verify(metricsRedisHelper).pushAccessLogs(events);
    }
}
