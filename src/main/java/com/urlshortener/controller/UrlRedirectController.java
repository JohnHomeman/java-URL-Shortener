package com.urlshortener.controller;

import com.urlshortener.dto.AccessEvent;
import com.urlshortener.service.MetricsBufferService;
import com.urlshortener.service.UrlRedirectService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Instant;

/**
 * 短網址轉址重定向控制器 (引用 Spec §3.1, §5.1)
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class UrlRedirectController {

    private final UrlRedirectService urlRedirectService;
    private final MetricsBufferService metricsBufferService;

    /**
     * 短碼重定向端點
     * GET /{shortKey}
     *
     * @param shortKey 短碼
     * @return HTTP 302 重定向至原始網址
     */
    @GetMapping("/{shortKey}")
    public ResponseEntity<Void> redirect(@PathVariable("shortKey") String shortKey, HttpServletRequest request) {
        String originalUrl = urlRedirectService.getOriginalUrl(shortKey);

        // 轉址成功後非同步記錄存取事件 (引用 Spec §3.1)
        try {
            String ip = extractClientIp(request);
            String userAgent = request.getHeader(HttpHeaders.USER_AGENT);
            String referer = request.getHeader(HttpHeaders.REFERER);

            AccessEvent event = AccessEvent.builder()
                    .shortKey(shortKey)
                    .ip(ip)
                    .userAgent(userAgent != null ? userAgent : "")
                    .referer(referer != null ? referer : "")
                    .accessedAt(Instant.now())
                    .build();

            metricsBufferService.recordAccessEvent(event);
        } catch (Exception e) {
            log.warn("Failed to record access event for shortKey {}: {}", shortKey, e.getMessage());
        }

        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(originalUrl))
                .header(HttpHeaders.CACHE_CONTROL, "no-cache, no-store, max-age=0, must-revalidate")
                .header(HttpHeaders.PRAGMA, "no-cache")
                .build();
    }

    private String extractClientIp(HttpServletRequest request) {
        if (request == null) {
            return "";
        }
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isBlank() && !"unknown".equalsIgnoreCase(xForwardedFor)) {
            return xForwardedFor.split(",")[0].trim();
        }
        String xRealIp = request.getHeader("X-Real-IP");
        if (xRealIp != null && !xRealIp.isBlank() && !"unknown".equalsIgnoreCase(xRealIp)) {
            return xRealIp.trim();
        }
        return request.getRemoteAddr() != null ? request.getRemoteAddr() : "";
    }
}
