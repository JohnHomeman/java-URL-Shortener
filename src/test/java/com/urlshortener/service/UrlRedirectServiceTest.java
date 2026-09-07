package com.urlshortener.service;

import com.urlshortener.common.exception.BusinessException;
import com.urlshortener.common.exception.ErrorCode;
import com.urlshortener.component.RedisCacheHelper;
import com.urlshortener.dto.ShortUrlCacheDto;
import com.urlshortener.entity.ShortUrl;
import com.urlshortener.repository.ShortUrlRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * UrlRedirectService 單元測試 (引用 Spec §7.1 Table-Driven 測項)
 */
@ExtendWith(MockitoExtension.class)
class UrlRedirectServiceTest {

    @Mock
    private ShortUrlRepository shortUrlRepository;

    @Mock
    private RedisCacheHelper redisCacheHelper;

    @InjectMocks
    private UrlRedirectService urlRedirectService;

    @Test
    @DisplayName("Table-Driven: 快取命中正常短碼，直接回傳原始網址且不查詢 DB")
    void getOriginalUrl_whenCacheHitValid_shouldReturnUrlWithoutDbQuery() {
        String shortKey = "mdn-302";
        String expectedUrl = "https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/302";
        ShortUrlCacheDto cacheDto = ShortUrlCacheDto.builder()
                .originalUrl(expectedUrl)
                .status(ShortUrl.STATUS_ACTIVE)
                .expiredAt(null)
                .build();

        when(redisCacheHelper.get(shortKey)).thenReturn(RedisCacheHelper.CacheResult.hitValid(cacheDto));

        String actualUrl = urlRedirectService.getOriginalUrl(shortKey);

        assertThat(actualUrl).isEqualTo(expectedUrl);
        verifyNoInteractions(shortUrlRepository);
    }

    @Test
    @DisplayName("Table-Driven: 快取命中穿透空值，拋出 40401 且不查詢 DB")
    void getOriginalUrl_whenCacheHitNullMarker_shouldThrowNotFoundWithoutDbQuery() {
        String shortKey = "fake999";
        when(redisCacheHelper.get(shortKey)).thenReturn(RedisCacheHelper.CacheResult.hitNull());

        assertThatThrownBy(() -> urlRedirectService.getOriginalUrl(shortKey))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getCode()).isEqualTo(ErrorCode.SHORT_URL_NOT_FOUND.getCode());
                });

        verifyNoInteractions(shortUrlRepository);
    }

    @Test
    @DisplayName("Table-Driven: 快取未命中但 DB 存在，回填快取並回傳原始網址")
    void getOriginalUrl_whenCacheMissAndDbHit_shouldBackfillCacheAndReturnUrl() {
        String shortKey = "git-hub";
        String expectedUrl = "https://github.com";
        ShortUrl entity = ShortUrl.builder()
                .id(1L)
                .shortKey(shortKey)
                .originalUrl(expectedUrl)
                .status(ShortUrl.STATUS_ACTIVE)
                .expiredAt(null)
                .build();

        when(redisCacheHelper.get(shortKey)).thenReturn(RedisCacheHelper.CacheResult.miss());
        when(shortUrlRepository.findByShortKey(shortKey)).thenReturn(Optional.of(entity));

        String actualUrl = urlRedirectService.getOriginalUrl(shortKey);

        assertThat(actualUrl).isEqualTo(expectedUrl);
        verify(redisCacheHelper).set(eq(shortKey), any(ShortUrlCacheDto.class));
    }

    @Test
    @DisplayName("Table-Driven: 快取未命中且 DB 不存在，寫入空值快取並拋出 40401")
    void getOriginalUrl_whenCacheMissAndDbMiss_shouldSetNullCacheAndThrowNotFound() {
        String shortKey = "not-exist";
        when(redisCacheHelper.get(shortKey)).thenReturn(RedisCacheHelper.CacheResult.miss());
        when(shortUrlRepository.findByShortKey(shortKey)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> urlRedirectService.getOriginalUrl(shortKey))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getCode()).isEqualTo(ErrorCode.SHORT_URL_NOT_FOUND.getCode());
                });

        verify(redisCacheHelper).setNull(shortKey);
    }

    @Test
    @DisplayName("Table-Driven: 短網址已停用 (DB status=0)，拋出 40301 SHORT_URL_DISABLED")
    void getOriginalUrl_whenDisabledInDb_shouldThrowDisabled() {
        String shortKey = "disabled-key";
        ShortUrl entity = ShortUrl.builder()
                .id(2L)
                .shortKey(shortKey)
                .originalUrl("https://example.com/disabled")
                .status(ShortUrl.STATUS_DISABLED)
                .build();

        when(redisCacheHelper.get(shortKey)).thenReturn(RedisCacheHelper.CacheResult.miss());
        when(shortUrlRepository.findByShortKey(shortKey)).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> urlRedirectService.getOriginalUrl(shortKey))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getCode()).isEqualTo(ErrorCode.SHORT_URL_DISABLED.getCode());
                });
    }

    @Test
    @DisplayName("Table-Driven: 短網址已過期 (DB expired_at < now)，拋出 41001 SHORT_URL_EXPIRED")
    void getOriginalUrl_whenExpiredInDb_shouldThrowExpired() {
        String shortKey = "expired-key";
        ShortUrl entity = ShortUrl.builder()
                .id(3L)
                .shortKey(shortKey)
                .originalUrl("https://example.com/expired")
                .status(ShortUrl.STATUS_ACTIVE)
                .expiredAt(Instant.now().minusSeconds(100))
                .build();

        when(redisCacheHelper.get(shortKey)).thenReturn(RedisCacheHelper.CacheResult.miss());
        when(shortUrlRepository.findByShortKey(shortKey)).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> urlRedirectService.getOriginalUrl(shortKey))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getCode()).isEqualTo(ErrorCode.SHORT_URL_EXPIRED.getCode());
                });
    }

    @Test
    @DisplayName("Table-Driven: 快取命中但資料已停用，拋出 40301")
    void getOriginalUrl_whenCacheHitDisabled_shouldThrowDisabled() {
        String shortKey = "cache-disabled";
        ShortUrlCacheDto cacheDto = ShortUrlCacheDto.builder()
                .originalUrl("https://example.com")
                .status(ShortUrl.STATUS_DISABLED)
                .build();

        when(redisCacheHelper.get(shortKey)).thenReturn(RedisCacheHelper.CacheResult.hitValid(cacheDto));

        assertThatThrownBy(() -> urlRedirectService.getOriginalUrl(shortKey))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getCode()).isEqualTo(ErrorCode.SHORT_URL_DISABLED.getCode());
                });

        verifyNoInteractions(shortUrlRepository);
    }

    @Test
    @DisplayName("Table-Driven: 快取命中但資料已過期，拋出 41001")
    void getOriginalUrl_whenCacheHitExpired_shouldThrowExpired() {
        String shortKey = "cache-expired";
        ShortUrlCacheDto cacheDto = ShortUrlCacheDto.builder()
                .originalUrl("https://example.com")
                .status(ShortUrl.STATUS_ACTIVE)
                .expiredAt(Instant.now().minusSeconds(50))
                .build();

        when(redisCacheHelper.get(shortKey)).thenReturn(RedisCacheHelper.CacheResult.hitValid(cacheDto));

        assertThatThrownBy(() -> urlRedirectService.getOriginalUrl(shortKey))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getCode()).isEqualTo(ErrorCode.SHORT_URL_EXPIRED.getCode());
                });

        verifyNoInteractions(shortUrlRepository);
    }
}
