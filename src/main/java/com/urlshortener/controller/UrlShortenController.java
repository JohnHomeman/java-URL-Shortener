package com.urlshortener.controller;

import com.urlshortener.common.response.ApiResponse;
import com.urlshortener.dto.ShortenUrlRequest;
import com.urlshortener.dto.ShortenUrlResponse;
import com.urlshortener.service.UrlShortenService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 短網址生成控制器 (引用 Spec §5.1)
 */
@RestController
@RequestMapping("/api/v1/urls")
@RequiredArgsConstructor
public class UrlShortenController {

    private final UrlShortenService urlShortenService;

    /**
     * 生成短網址 API
     * POST /api/v1/urls/shorten
     *
     * @param request 請求參數
     * @return 統一 API 回應包含 ShortenUrlResponse
     */
    @PostMapping("/shorten")
    public ResponseEntity<ApiResponse<ShortenUrlResponse>> shortenUrl(@Valid @RequestBody ShortenUrlRequest request) {
        ShortenUrlResponse response = urlShortenService.createShortUrl(request);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
