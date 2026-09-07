package com.urlshortener.controller;

import com.urlshortener.service.UrlRedirectService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * 短網址轉址重定向控制器 (引用 Spec §5.1)
 */
@RestController
@RequiredArgsConstructor
public class UrlRedirectController {

    private final UrlRedirectService urlRedirectService;

    /**
     * 短碼重定向端點
     * GET /{shortKey}
     *
     * @param shortKey 短碼
     * @return HTTP 302 重定向至原始網址
     */
    @GetMapping("/{shortKey}")
    public ResponseEntity<Void> redirect(@PathVariable("shortKey") String shortKey) {
        String originalUrl = urlRedirectService.getOriginalUrl(shortKey);

        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(originalUrl))
                .header(HttpHeaders.CACHE_CONTROL, "no-cache, no-store, max-age=0, must-revalidate")
                .header(HttpHeaders.PRAGMA, "no-cache")
                .build();
    }
}
