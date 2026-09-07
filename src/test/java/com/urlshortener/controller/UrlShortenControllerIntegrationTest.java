package com.urlshortener.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.urlshortener.dto.ShortenUrlRequest;
import com.urlshortener.repository.ShortUrlRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * UrlShortenController 整合測試 (引用 Spec §7.2 情境 A ~ E 與 Spec §5.1 錯誤碼情境)
 */
@SpringBootTest
@AutoConfigureMockMvc
class UrlShortenControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ShortUrlRepository shortUrlRepository;

    @BeforeEach
    void setUp() {
        shortUrlRepository.deleteAll();
    }

    @Test
    @DisplayName("情境 A：正常自動生成短網址 - 回傳 200, 6 位 short_key, DB 寫入 1 筆")
    void testScenarioA_NormalAutoShorten() throws Exception {
        ShortenUrlRequest request = ShortenUrlRequest.builder()
                .originalUrl("https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/302")
                .build();

        mockMvc.perform(post("/api/v1/urls/shorten")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.data.short_key").isString())
                .andExpect(jsonPath("$.data.short_url").isString())
                .andExpect(jsonPath("$.data.original_url").value("https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/302"))
                .andExpect(jsonPath("$.data.is_custom").value(false))
                .andExpect(jsonPath("$.data.created_at").isNotEmpty());

        assertThat(shortUrlRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("情境 B：長網址重複請求冪等性 - 二次請求回傳相同 short_key, DB 總筆數不增加")
    void testScenarioB_IdempotencyReuse() throws Exception {
        ShortenUrlRequest request = ShortenUrlRequest.builder()
                .originalUrl("https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/302")
                .build();

        // 第一次請求
        MvcResult result1 = mockMvc.perform(post("/api/v1/urls/shorten")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn();

        String responseJson1 = result1.getResponse().getContentAsString();
        String key1 = objectMapper.readTree(responseJson1).path("data").path("short_key").asText();

        // 第二次請求
        MvcResult result2 = mockMvc.perform(post("/api/v1/urls/shorten")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn();

        String responseJson2 = result2.getResponse().getContentAsString();
        String key2 = objectMapper.readTree(responseJson2).path("data").path("short_key").asText();

        assertThat(key1).isEqualTo(key2);
        assertThat(shortUrlRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("情境 C：成功建立自訂別名短網址 - 回傳 200, is_custom=true, short_key=promo-2026")
    void testScenarioC_CustomAliasSuccess() throws Exception {
        ShortenUrlRequest request = ShortenUrlRequest.builder()
                .originalUrl("https://example.com/promo-target")
                .customAlias("promo-2026")
                .build();

        mockMvc.perform(post("/api/v1/urls/shorten")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.short_key").value("promo-2026"))
                .andExpect(jsonPath("$.data.is_custom").value(true));

        assertThat(shortUrlRepository.findByShortKey("promo-2026")).isPresent();
    }

    @Test
    @DisplayName("情境 D：自訂別名重複衝突拒絕 - 回傳 409 Conflict, code=40901")
    void testScenarioD_CustomAliasConflict() throws Exception {
        ShortenUrlRequest request1 = ShortenUrlRequest.builder()
                .originalUrl("https://example.com/target-1")
                .customAlias("promo-2026")
                .build();

        ShortenUrlRequest request2 = ShortenUrlRequest.builder()
                .originalUrl("https://example.com/target-2")
                .customAlias("promo-2026")
                .build();

        // 第一次成功
        mockMvc.perform(post("/api/v1/urls/shorten")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request1)))
                .andExpect(status().isOk());

        // 第二次衝突
        mockMvc.perform(post("/api/v1/urls/shorten")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request2)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40901))
                .andExpect(jsonPath("$.message").value("Custom alias already exists: promo-2026"));
    }

    @Test
    @DisplayName("情境 E：設定 TTL 過期時間 - 回傳 200, expired_at 不為空")
    void testScenarioE_WithTTL() throws Exception {
        ShortenUrlRequest request = ShortenUrlRequest.builder()
                .originalUrl("https://example.com/temp-page")
                .ttlInSeconds(3600L)
                .build();

        mockMvc.perform(post("/api/v1/urls/shorten")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.expired_at").isNotEmpty());
    }

    @Test
    @DisplayName("錯誤碼 40001: 請求欄位為空或型態錯誤")
    void testErrorCode40001_InvalidParams() throws Exception {
        mockMvc.perform(post("/api/v1/urls/shorten")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"original_url\": \"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
    }

    @Test
    @DisplayName("錯誤碼 40002: original_url 格式不合法或非 HTTP/HTTPS")
    void testErrorCode40002_InvalidOriginalUrl() throws Exception {
        ShortenUrlRequest request = ShortenUrlRequest.builder()
                .originalUrl("ftp://files.example.com/archive.zip")
                .build();

        mockMvc.perform(post("/api/v1/urls/shorten")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40002));
    }

    @Test
    @DisplayName("錯誤碼 40003: original_url 包含本系統網域 (自指向迴圈)")
    void testErrorCode40003_RecursiveUrl() throws Exception {
        ShortenUrlRequest request = ShortenUrlRequest.builder()
                .originalUrl("http://localhost:8080/loop")
                .build();

        mockMvc.perform(post("/api/v1/urls/shorten")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40003));
    }

    @Test
    @DisplayName("錯誤碼 40004: custom_alias 使用系統保留關鍵字")
    void testErrorCode40004_ReservedKeyword() throws Exception {
        ShortenUrlRequest request = ShortenUrlRequest.builder()
                .originalUrl("https://example.com/valid")
                .customAlias("health")
                .build();

        mockMvc.perform(post("/api/v1/urls/shorten")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40004));
    }

    @Test
    @DisplayName("錯誤碼 40005: custom_alias 格式不符合規範")
    void testErrorCode40005_InvalidAliasFormat() throws Exception {
        ShortenUrlRequest request = ShortenUrlRequest.builder()
                .originalUrl("https://example.com/valid")
                .customAlias("abc")
                .build();

        mockMvc.perform(post("/api/v1/urls/shorten")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40005));
    }
}
