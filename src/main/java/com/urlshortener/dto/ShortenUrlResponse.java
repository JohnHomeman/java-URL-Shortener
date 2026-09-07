package com.urlshortener.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 建立短網址回應 DTO (引用 Spec §5.1)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShortenUrlResponse {

    @JsonProperty("short_key")
    private String shortKey;

    @JsonProperty("short_url")
    private String shortUrl;

    @JsonProperty("original_url")
    private String originalUrl;

    @JsonProperty("is_custom")
    private Boolean isCustom;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'", timezone = "UTC")
    @JsonProperty("expired_at")
    private Instant expiredAt;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'", timezone = "UTC")
    @JsonProperty("created_at")
    private Instant createdAt;
}
