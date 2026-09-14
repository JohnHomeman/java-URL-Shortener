package com.urlshortener.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 內部轉址存取事件資料載體 (引用 Spec §3.1, §4.2)
 * 用於在「轉址請求 -> Java 記憶體緩衝佇列 -> Redis 佇列」之間輕量傳遞點擊明細資料
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AccessEvent {

    /**
     * 短網址代碼 (短碼或自訂別名)
     */
    private String shortKey;

    /**
     * 訪客客戶端 IP 位址 (支援 IPv4/IPv6)
     */
    private String ip;

    /**
     * 訪客瀏覽器 / 客戶端 User-Agent 識別字串
     */
    private String userAgent;

    /**
     * 來源網址 (Referer，標記流量入口)
     */
    private String referer;

    /**
     * 存取發生時間戳
     * 使用 @JsonFormat 確保序列化至 Redis 佇列時以標準 ISO-8601 字串格式儲存 (如 "2026-09-09T09:14:18.123Z")，
     * 避免被轉為數值戳記造成反序列化或跨語言解析歧異
     */
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Instant accessedAt;
}
