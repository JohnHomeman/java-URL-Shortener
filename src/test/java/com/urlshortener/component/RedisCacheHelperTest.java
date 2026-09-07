package com.urlshortener.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.urlshortener.dto.ShortUrlCacheDto;
import com.urlshortener.entity.ShortUrl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RedisCacheHelperTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private ObjectMapper objectMapper;
    private RedisCacheHelper redisCacheHelper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        redisCacheHelper = new RedisCacheHelper(stringRedisTemplate, objectMapper);
    }

    @Test
    @DisplayName("計算 TTL：永久有效網址應為 86400 + Jitter")
    void calculateTtlSeconds_whenExpiredAtNull_shouldReturnBasePlusJitter() {
        Instant now = Instant.now();
        int jitter = 150;

        long ttl = redisCacheHelper.calculateTtlSeconds(null, now, jitter);

        assertThat(ttl).isEqualTo(86400L + 150L);
    }

    @Test
    @DisplayName("計算 TTL：過期時間小於 24 小時應取剩餘秒數")
    void calculateTtlSeconds_whenExpiredAtShort_shouldReturnRemainingSeconds() {
        Instant now = Instant.now();
        Instant expiredAt = now.plus(3600, ChronoUnit.SECONDS);
        int jitter = 100;

        long ttl = redisCacheHelper.calculateTtlSeconds(expiredAt, now, jitter);

        assertThat(ttl).isEqualTo(3600L);
    }

    @Test
    @DisplayName("計算 TTL：過期時間大於 24 小時應取 24 小時 + Jitter 上限")
    void calculateTtlSeconds_whenExpiredAtLong_shouldCapAtBasePlusJitter() {
        Instant now = Instant.now();
        Instant expiredAt = now.plus(100000, ChronoUnit.SECONDS);
        int jitter = 200;

        long ttl = redisCacheHelper.calculateTtlSeconds(expiredAt, now, jitter);

        assertThat(ttl).isEqualTo(86400L + 200L);
    }

    @Test
    @DisplayName("計算 TTL：已過期之網址應回傳 0")
    void calculateTtlSeconds_whenAlreadyExpired_shouldReturnZero() {
        Instant now = Instant.now();
        Instant expiredAt = now.minus(60, ChronoUnit.SECONDS);
        int jitter = 100;

        long ttl = redisCacheHelper.calculateTtlSeconds(expiredAt, now, jitter);

        assertThat(ttl).isEqualTo(0L);
    }

    @Test
    @DisplayName("快取查詢：命中正常資料")
    void get_whenHitValidJson_shouldReturnHitValid() throws Exception {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        ShortUrlCacheDto expectedDto = ShortUrlCacheDto.builder()
                .originalUrl("https://example.com")
                .status(ShortUrl.STATUS_ACTIVE)
                .expiredAt(Instant.now().plusSeconds(3600))
                .build();
        String json = objectMapper.writeValueAsString(expectedDto);
        when(valueOperations.get("short_url:key:abc123")).thenReturn(json);

        RedisCacheHelper.CacheResult result = redisCacheHelper.get("abc123");

        assertThat(result.isHitValid()).isTrue();
        assertThat(result.getData().getOriginalUrl()).isEqualTo("https://example.com");
    }

    @Test
    @DisplayName("快取查詢：命中空值標記 (__NULL__)")
    void get_whenHitNullMarker_shouldReturnHitNull() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("short_url:key:abc123")).thenReturn(RedisCacheHelper.NULL_VALUE_MARKER);

        RedisCacheHelper.CacheResult result = redisCacheHelper.get("abc123");

        assertThat(result.isHitNull()).isTrue();
        assertThat(result.getData()).isNull();
    }

    @Test
    @DisplayName("快取查詢：未命中 (Cache Miss)")
    void get_whenMiss_shouldReturnMiss() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("short_url:key:abc123")).thenReturn(null);

        RedisCacheHelper.CacheResult result = redisCacheHelper.get("abc123");

        assertThat(result.isMiss()).isTrue();
        assertThat(result.getData()).isNull();
    }

    @Test
    @DisplayName("快取查詢：Redis 連線異常應優雅降級為 Cache Miss")
    void get_whenRedisException_shouldDegradeToMiss() {
        when(stringRedisTemplate.opsForValue()).thenThrow(new RedisConnectionFailureException("Connection refused"));

        RedisCacheHelper.CacheResult result = redisCacheHelper.get("abc123");

        assertThat(result.isMiss()).isTrue();
    }

    @Test
    @DisplayName("快取寫入：設定空值快取 TTL 應為 60 秒")
    void setNull_shouldSetWith60SecondsTtl() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        redisCacheHelper.setNull("not-exist");

        verify(valueOperations).set(
                eq("short_url:key:not-exist"),
                eq(RedisCacheHelper.NULL_VALUE_MARKER),
                eq(Duration.ofSeconds(60))
        );
    }

    @Test
    @DisplayName("快取寫入：設定有效短網址快取")
    void set_shouldSerializeAndSetWithTtl() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        ShortUrlCacheDto dto = ShortUrlCacheDto.builder()
                .originalUrl("https://example.com")
                .status(1)
                .build();

        redisCacheHelper.set("validKey", dto);

        ArgumentCaptor<Duration> durationCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(valueOperations).set(
                eq("short_url:key:validKey"),
                any(String.class),
                durationCaptor.capture()
        );

        long ttlSeconds = durationCaptor.getValue().toSeconds();
        assertThat(ttlSeconds).isBetween(86400L, 86400L + 300L);
    }
}
