package com.urlshortener.controller;

import com.urlshortener.common.response.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 服務健康檢查控制器 (引用 Spec §3.3, §5.1)
 */
@RestController
@RequestMapping("/api/v1")
public class HealthController {

    /**
     * 服務健康狀態端點
     *
     * @return 統一回應封裝結構，包含 status 與 timestamp
     */
    @GetMapping("/health")
    public ApiResponse<Map<String, Object>> health() {
        Map<String, Object> data = new HashMap<>();
        data.put("status", "UP");
        return ApiResponse.success(data);
    }
}
