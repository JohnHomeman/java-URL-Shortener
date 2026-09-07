package com.urlshortener.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.urlshortener.entity.ShortUrl;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;

/**
 * 短網址 Redis 快取資料傳輸物件 (引用 Spec §4.1)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ShortUrlCacheDto implements Serializable {

    private static final long serialVersionUID = 1L;

    @JsonProperty("original_url")
    private String originalUrl;

    @JsonProperty("status")
    private Integer status;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'", timezone = "UTC")
    @JsonProperty("expired_at")
    private Instant expiredAt;

    /**
     * 從 ShortUrl Entity 轉換為快取 DTO
     */
    public static ShortUrlCacheDto fromEntity(ShortUrl shortUrl) {
        if (shortUrl == null) {
            return null;
        }
        return ShortUrlCacheDto.builder()
                .originalUrl(shortUrl.getOriginalUrl())
                .status(shortUrl.getStatus())
                .expiredAt(shortUrl.getExpiredAt())
                .build();
    }

    /**
     * 檢查在指定時間點是否仍有效
     */
    @JsonIgnore
    public boolean isValid(Instant now) {
        if (this.status == null || this.status != ShortUrl.STATUS_ACTIVE) {
            return false;
        }
        return this.expiredAt == null || this.expiredAt.isAfter(now);
    }

    /**
     * 檢查是否已被停用
     */
    @JsonIgnore
    public boolean isDisabled() {
        return this.status != null && this.status == ShortUrl.STATUS_DISABLED;
    }

    /**
     * 檢查是否已過期
     */
    @JsonIgnore
    public boolean isExpired(Instant now) {
        return this.expiredAt != null && !this.expiredAt.isAfter(now);
    }
}
