package com.urlshortener.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 建立短網址請求 DTO (引用 Spec §5.1)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShortenUrlRequest {

    @NotBlank(message = "Original URL cannot be blank")
    @Size(max = 2048, message = "Original URL length cannot exceed 2048 characters")
    @JsonProperty("original_url")
    private String originalUrl;

    @JsonProperty("custom_alias")
    private String customAlias;

    @Min(value = 60, message = "TTL must be at least 60 seconds")
    @JsonProperty("ttl_in_seconds")
    private Long ttlInSeconds;
}
