package com.urlshortener.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * 存取明細日誌分頁查詢回應 DTO (引用 Spec §5.2)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class AccessLogPageResponseDto {

    private List<LogItem> items;
    private Integer page;
    private Integer size;
    private Long totalElements;
    private Integer totalPages;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class LogItem {
        private Long id;
        private String shortKey;
        private String ip;
        private String userAgent;
        private String referer;

        @JsonFormat(shape = JsonFormat.Shape.STRING)
        private Instant accessedAt;
    }
}
