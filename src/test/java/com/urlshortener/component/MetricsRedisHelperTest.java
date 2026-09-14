package com.urlshortener.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.urlshortener.dto.AccessEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MetricsRedisHelperTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    @Mock
    private ListOperations<String, String> listOperations;

    private ObjectMapper objectMapper;
    private MetricsRedisHelper metricsRedisHelper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        metricsRedisHelper = new MetricsRedisHelper(stringRedisTemplate, objectMapper);
    }

    @Test
    @DisplayName("getPendingClickCount - 成功自 Redis 取得未回寫增量")
    void testGetPendingClickCount() {
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.get(MetricsRedisHelper.KEY_METRICS_CLICKS, "mdn-302")).thenReturn("15");
        when(hashOperations.get(MetricsRedisHelper.KEY_METRICS_CLICKS_SYNCING, "mdn-302")).thenReturn("5");

        long count = metricsRedisHelper.getPendingClickCount("mdn-302");

        assertThat(count).isEqualTo(20L);
    }

    @Test
    @DisplayName("rotateClicksForSync - 當 Key 存在時原子重命名成功")
    void testRotateClicksForSync_Success() {
        when(stringRedisTemplate.hasKey(MetricsRedisHelper.KEY_METRICS_CLICKS)).thenReturn(true);

        boolean rotated = metricsRedisHelper.rotateClicksForSync();

        assertThat(rotated).isTrue();
        verify(stringRedisTemplate).delete(MetricsRedisHelper.KEY_METRICS_CLICKS_SYNCING);
        verify(stringRedisTemplate).rename(MetricsRedisHelper.KEY_METRICS_CLICKS, MetricsRedisHelper.KEY_METRICS_CLICKS_SYNCING);
    }

    @Test
    @DisplayName("popAccessLogs - 成功自 Redis List 彈出並反序列化")
    void testPopAccessLogs() throws Exception {
        when(stringRedisTemplate.opsForList()).thenReturn(listOperations);

        AccessEvent event = AccessEvent.builder()
                .shortKey("mdn-302")
                .ip("127.0.0.1")
                .userAgent("Chrome")
                .referer("https://google.com")
                .accessedAt(Instant.now())
                .build();
        String json = objectMapper.writeValueAsString(event);

        when(listOperations.rightPop(MetricsRedisHelper.KEY_METRICS_ACCESS_QUEUE))
                .thenReturn(json)
                .thenReturn(null);

        List<AccessEvent> popped = metricsRedisHelper.popAccessLogs(10);

        assertThat(popped).hasSize(1);
        assertThat(popped.get(0).getShortKey()).isEqualTo("mdn-302");
        assertThat(popped.get(0).getIp()).isEqualTo("127.0.0.1");
    }
}
