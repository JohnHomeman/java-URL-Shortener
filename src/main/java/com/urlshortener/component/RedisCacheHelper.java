package com.urlshortener.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.urlshortener.dto.ShortUrlCacheDto;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Redis 快取輔助組件 (引用 Spec §3.1, §3.2, §4.1, §6.3, §6.4, §6.5)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisCacheHelper {

    public static final String KEY_PREFIX = "short_url:key:";
    public static final String NULL_VALUE_MARKER = "__NULL__";
    public static final long NULL_CACHE_TTL_SECONDS = 60L;
    public static final long DEFAULT_CACHE_TTL_SECONDS = 86400L;
    public static final int MAX_JITTER_SECONDS = 300;

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 快取查詢結果封裝
     */
    @Getter
    public static class CacheResult {
        public enum State {
            HIT_VALID,
            HIT_NULL,
            MISS
        }

        private final State state;
        private final ShortUrlCacheDto data;

        private CacheResult(State state, ShortUrlCacheDto data) {
            this.state = state;
            this.data = data;
        }

        public static CacheResult hitValid(ShortUrlCacheDto data) {
            return new CacheResult(State.HIT_VALID, data);
        }

        public static CacheResult hitNull() {
            return new CacheResult(State.HIT_NULL, null);
        }

        public static CacheResult miss() {
            return new CacheResult(State.MISS, null);
        }

        public boolean isHitValid() {
            return state == State.HIT_VALID;
        }

        public boolean isHitNull() {
            return state == State.HIT_NULL;
        }

        public boolean isMiss() {
            return state == State.MISS;
        }
    }

    /**
     * 建構 Redis Key
     */
    public String buildKey(String shortKey) {
        return KEY_PREFIX + shortKey;
    }

    /**
     * 查詢快取
     */
    public CacheResult get(String shortKey) {
        String key = buildKey(shortKey);
        try {
            String cachedJson = stringRedisTemplate.opsForValue().get(key);
            if (cachedJson == null) {
                return CacheResult.miss();
            }
            if (NULL_VALUE_MARKER.equals(cachedJson)) {
                log.debug("Cache hit null marker for key: {}", key);
                return CacheResult.hitNull();
            }
            ShortUrlCacheDto cacheDto = objectMapper.readValue(cachedJson, ShortUrlCacheDto.class);
            return CacheResult.hitValid(cacheDto);
        } catch (Exception ex) {
            log.warn("Redis read failed for key={}, degrading to DB: {}", key, ex.getMessage());
            return CacheResult.miss();
        }
    }

    /**
     * 寫入有效資料快取 (含隨機抖動與過期時間對齊)
     */
    public void set(String shortKey, ShortUrlCacheDto dto) {
        if (dto == null) {
            return;
        }
        String key = buildKey(shortKey);
        try {
            Instant now = Instant.now();
            int jitter = ThreadLocalRandom.current().nextInt(0, MAX_JITTER_SECONDS + 1);
            long ttlSeconds = calculateTtlSeconds(dto.getExpiredAt(), now, jitter);

            if (ttlSeconds <= 0) {
                log.debug("TTL for shortKey={} is non-positive ({}), skipping cache write", shortKey, ttlSeconds);
                return;
            }

            String json = objectMapper.writeValueAsString(dto);
            stringRedisTemplate.opsForValue().set(key, json, Duration.ofSeconds(ttlSeconds));
            log.debug("Cached shortKey={} with TTL={}s", shortKey, ttlSeconds);
        } catch (Exception ex) {
            log.warn("Redis write failed for key={}: {}", key, ex.getMessage());
        }
    }

    /**
     * 寫入空值快取 (防禦快取穿透，TTL = 60s)
     */
    public void setNull(String shortKey) {
        String key = buildKey(shortKey);
        try {
            stringRedisTemplate.opsForValue().set(key, NULL_VALUE_MARKER, Duration.ofSeconds(NULL_CACHE_TTL_SECONDS));
            log.debug("Set null marker cache for key={} with TTL={}s", key, NULL_CACHE_TTL_SECONDS);
        } catch (Exception ex) {
            log.warn("Redis write null marker failed for key={}: {}", key, ex.getMessage());
        }
    }

    /**
     * 刪除快取
     */
    public void delete(String shortKey) {
        String key = buildKey(shortKey);
        try {
            stringRedisTemplate.delete(key);
            log.debug("Deleted cache for key={}", key);
        } catch (Exception ex) {
            log.warn("Redis delete failed for key={}: {}", key, ex.getMessage());
        }
    }

    /**
     * 計算實際快取 TTL 秒數 (引用 Spec §3.2)
     */
    public long calculateTtlSeconds(Instant expiredAt, Instant now, int jitterSeconds) {
        long baseWithJitter = DEFAULT_CACHE_TTL_SECONDS + jitterSeconds;
        if (expiredAt == null) {
            return baseWithJitter;
        }

        long remainingSeconds = ChronoUnit.SECONDS.between(now, expiredAt);
        if (remainingSeconds <= 0) {
            return 0;
        }
        return Math.min(remainingSeconds, baseWithJitter);
    }
}
