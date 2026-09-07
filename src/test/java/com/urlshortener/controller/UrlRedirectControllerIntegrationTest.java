package com.urlshortener.controller;

import com.urlshortener.component.RedisCacheHelper;
import com.urlshortener.entity.ShortUrl;
import com.urlshortener.repository.ShortUrlRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * UrlRedirectController 整合測試 (引用 Spec §5.1, §7.2 情境 A ~ F)
 */
@SpringBootTest
@AutoConfigureMockMvc
class UrlRedirectControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ShortUrlRepository shortUrlRepository;

    @Autowired
    private RedisCacheHelper redisCacheHelper;

    private static final String TEST_KEY_A = "test-abc123";
    private static final String TEST_KEY_EXPIRED = "test-expired";
    private static final String TEST_KEY_DISABLED = "test-disabled";
    private static final String TEST_KEY_NON_EXISTENT = "test-not-exist";

    @BeforeEach
    void setUp() {
        cleanTestData();
    }

    @AfterEach
    void tearDown() {
        cleanTestData();
    }

    private void cleanTestData() {
        shortUrlRepository.deleteAll();
        redisCacheHelper.delete(TEST_KEY_A);
        redisCacheHelper.delete(TEST_KEY_EXPIRED);
        redisCacheHelper.delete(TEST_KEY_DISABLED);
        redisCacheHelper.delete(TEST_KEY_NON_EXISTENT);
    }

    @Test
    @DisplayName("情境 A & B：首次轉址 (Cache Miss -> DB Hit -> 快取回填) 與第二次轉址 (Cache Hit) 回傳 HTTP 302")
    void testScenarioAAndB_FirstAndSecondRedirect() throws Exception {
        String originalUrl = "https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/302";
        ShortUrl entity = ShortUrl.builder()
                .shortKey(TEST_KEY_A)
                .originalUrl(originalUrl)
                .originalUrlHash(12345678L)
                .isCustom(false)
                .status(ShortUrl.STATUS_ACTIVE)
                .expiredAt(null)
                .build();
        shortUrlRepository.save(entity);

        // 首次請求 (情境 A: Cache Miss -> DB Hit -> 寫入 Redis 快取)
        mockMvc.perform(get("/" + TEST_KEY_A))
                .andExpect(status().isFound())
                .andExpect(header().string(HttpHeaders.LOCATION, originalUrl))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-cache, no-store, max-age=0, must-revalidate"))
                .andExpect(header().string(HttpHeaders.PRAGMA, "no-cache"));

        // 驗證 Redis 快取已成功寫入
        RedisCacheHelper.CacheResult cacheResult = redisCacheHelper.get(TEST_KEY_A);
        assertThat(cacheResult.isHitValid()).isTrue();
        assertThat(cacheResult.getData().getOriginalUrl()).isEqualTo(originalUrl);

        // 第二次請求 (情境 B: Cache Hit)
        mockMvc.perform(get("/" + TEST_KEY_A))
                .andExpect(status().isFound())
                .andExpect(header().string(HttpHeaders.LOCATION, originalUrl));
    }

    @Test
    @DisplayName("情境 C & D：不存在短碼防穿透 (DB Miss -> 空值快取) 與連續請求 (Cache Hit Null -> 404 快速失敗)")
    void testScenarioCAndD_NonExistentKeyPenetrationDefense() throws Exception {
        // 首次請求不存在短碼 (情境 C: DB Miss -> 寫入空值快取)
        mockMvc.perform(get("/" + TEST_KEY_NON_EXISTENT))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40401))
                .andExpect(jsonPath("$.message").value("Short URL not found: " + TEST_KEY_NON_EXISTENT))
                .andExpect(jsonPath("$.data").doesNotExist());

        // 驗證 Redis 快取已寫入空值標記
        RedisCacheHelper.CacheResult cacheResult = redisCacheHelper.get(TEST_KEY_NON_EXISTENT);
        assertThat(cacheResult.isHitNull()).isTrue();

        // 連續請求 (情境 D: Cache Hit Null -> 404 快速阻斷)
        mockMvc.perform(get("/" + TEST_KEY_NON_EXISTENT))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40401));
    }

    @Test
    @DisplayName("情境 E：過期短網址拒絕轉址 - 回傳 HTTP 410 (41001)")
    void testScenarioE_ExpiredShortUrl() throws Exception {
        ShortUrl entity = ShortUrl.builder()
                .shortKey(TEST_KEY_EXPIRED)
                .originalUrl("https://example.com/expired-page")
                .originalUrlHash(87654321L)
                .isCustom(false)
                .status(ShortUrl.STATUS_ACTIVE)
                .expiredAt(Instant.now().minus(10, ChronoUnit.MINUTES))
                .build();
        shortUrlRepository.save(entity);

        mockMvc.perform(get("/" + TEST_KEY_EXPIRED))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value(41001))
                .andExpect(jsonPath("$.message").value("Short URL has expired"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("情境 F：停用短網址拒絕轉址 - 回傳 HTTP 403 (40301)")
    void testScenarioF_DisabledShortUrl() throws Exception {
        ShortUrl entity = ShortUrl.builder()
                .shortKey(TEST_KEY_DISABLED)
                .originalUrl("https://example.com/disabled-page")
                .originalUrlHash(99999999L)
                .isCustom(false)
                .status(ShortUrl.STATUS_DISABLED)
                .expiredAt(null)
                .build();
        shortUrlRepository.save(entity);

        mockMvc.perform(get("/" + TEST_KEY_DISABLED))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301))
                .andExpect(jsonPath("$.message").value("Short URL is disabled"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }
}
