package com.urlshortener.component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.urlshortener.dto.AccessEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 封裝 Redis 點擊計數累加、日誌佇列推拉與原子轉移操作組件 (引用 Spec §3.1, §3.2, §4.2)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MetricsRedisHelper {

    public static final String KEY_METRICS_CLICKS = "short_url:metrics:clicks";
    public static final String KEY_METRICS_CLICKS_SYNCING = "short_url:metrics:clicks:syncing";
    public static final String KEY_METRICS_ACCESS_QUEUE = "short_url:metrics:access_queue";

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 批次累加點擊數 (使用 Redis Hash HINCRBY)
     */
    public void incrementClicks(Map<String, Long> keyCountMap) {
        if (keyCountMap == null || keyCountMap.isEmpty()) {
            return;
        }
        try {
            stringRedisTemplate.executePipelined((org.springframework.data.redis.core.RedisCallback<Object>) connection -> {
                byte[] rawKey = stringRedisTemplate.getStringSerializer().serialize(KEY_METRICS_CLICKS);
                for (Map.Entry<String, Long> entry : keyCountMap.entrySet()) {
                    if (entry.getKey() != null && entry.getValue() != null && entry.getValue() > 0) {
                        byte[] rawField = stringRedisTemplate.getStringSerializer().serialize(entry.getKey());
                        connection.hashCommands().hIncrBy(rawKey, rawField, entry.getValue());
                    }
                }
                return null;
            });
        } catch (Exception e) {
            log.warn("Failed to increment clicks in Redis: {}", e.getMessage());
        }
    }

    /**
     * 批次推入存取日誌至 Redis List (LPUSH)
     */
    public void pushAccessLogs(List<AccessEvent> events) {
        if (events == null || events.isEmpty()) {
            return;
        }
        try {
            List<String> jsonList = new ArrayList<>(events.size());
            for (AccessEvent event : events) {
                try {
                    jsonList.add(objectMapper.writeValueAsString(event));
                } catch (JsonProcessingException e) {
                    log.error("Failed to serialize AccessEvent: {}", event, e);
                }
            }

            if (!jsonList.isEmpty()) {
                stringRedisTemplate.executePipelined((org.springframework.data.redis.core.RedisCallback<Object>) connection -> {
                    byte[] rawKey = stringRedisTemplate.getStringSerializer().serialize(KEY_METRICS_ACCESS_QUEUE);
                    for (String json : jsonList) {
                        byte[] rawVal = stringRedisTemplate.getStringSerializer().serialize(json);
                        connection.listCommands().lPush(rawKey, rawVal);
                    }
                    return null;
                });
            }
        } catch (Exception e) {
            log.warn("Failed to push access logs to Redis: {}", e.getMessage());
        }
    }

    /**
     * 取得指定短碼當前在 Redis 快取中尚未回寫的增量點擊數 (引用 Spec §3.4)
     */
    public long getPendingClickCount(String shortKey) {
        if (shortKey == null || shortKey.isBlank()) {
            return 0L;
        }
        long totalPending = 0L;
        try {
            // 查詢 main clicks hash
            Object val = stringRedisTemplate.opsForHash().get(KEY_METRICS_CLICKS, shortKey);
            if (val != null) {
                totalPending += Long.parseLong(val.toString());
            }
            // 查詢 syncing clicks hash (若正好在排程回寫中)
            Object syncVal = stringRedisTemplate.opsForHash().get(KEY_METRICS_CLICKS_SYNCING, shortKey);
            if (syncVal != null) {
                totalPending += Long.parseLong(syncVal.toString());
            }
        } catch (Exception e) {
            log.warn("Failed to get pending clicks from Redis for {}: {}", shortKey, e.getMessage());
        }
        return totalPending;
    }

    /**
     * 原子重命名 clicks 鍵為 syncing 鍵，以供排程安全提取數據 (引用 Spec §3.2, §6.3)
     * @return 若成功切換且有數據則回傳 true，若無鍵或鍵為空回傳 false
     */
    public boolean rotateClicksForSync() {
        try {
            Boolean hasKey = stringRedisTemplate.hasKey(KEY_METRICS_CLICKS);
            if (Boolean.TRUE.equals(hasKey)) {
                // 若之前殘留 syncing 鍵先清理
                stringRedisTemplate.delete(KEY_METRICS_CLICKS_SYNCING);
                stringRedisTemplate.rename(KEY_METRICS_CLICKS, KEY_METRICS_CLICKS_SYNCING);
                // 設置安全過期時間 60 秒 (防異常殘留)
                stringRedisTemplate.expire(KEY_METRICS_CLICKS_SYNCING, 60, TimeUnit.SECONDS);
                return true;
            }
        } catch (DataAccessException e) {
            log.warn("Redis rename key failed or key not found: {}", e.getMessage());
        }
        return false;
    }

    /**
     * 讀取 syncing 鍵中的所有短碼點擊增量
     */
    public Map<String, Long> getAllClicksFromSyncing() {
        Map<String, Long> result = new HashMap<>();
        try {
            Map<Object, Object> entries = stringRedisTemplate.opsForHash().entries(KEY_METRICS_CLICKS_SYNCING);
            for (Map.Entry<Object, Object> entry : entries.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    try {
                        result.put(entry.getKey().toString(), Long.parseLong(entry.getValue().toString()));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to read syncing clicks from Redis: {}", e.getMessage());
        }
        return result;
    }

    /**
     * 刪除 syncing 暫存鍵
     */
    public void deleteSyncingKey() {
        try {
            stringRedisTemplate.delete(KEY_METRICS_CLICKS_SYNCING);
        } catch (Exception e) {
            log.warn("Failed to delete syncing clicks key: {}", e.getMessage());
        }
    }

    /**
     * 自 Redis List 批次彈出存取日誌 (每次最多 batchSize 筆，引用 Spec §3.2, §6.4)
     */
    public List<AccessEvent> popAccessLogs(int batchSize) {
        if (batchSize <= 0) {
            return Collections.emptyList();
        }
        List<AccessEvent> events = new ArrayList<>();
        try {
            for (int i = 0; i < batchSize; i++) {
                String json = stringRedisTemplate.opsForList().rightPop(KEY_METRICS_ACCESS_QUEUE);
                if (json == null) {
                    break;
                }
                try {
                    AccessEvent event = objectMapper.readValue(json, AccessEvent.class);
                    events.add(event);
                } catch (Exception e) {
                    log.error("Failed to deserialize popped AccessEvent json: {}", json, e);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to pop access logs from Redis: {}", e.getMessage());
        }
        return events;
    }
}
