package com.urlshortener.service;

import com.urlshortener.common.exception.BusinessException;
import com.urlshortener.component.Base62Encoder;
import com.urlshortener.component.HashGenerator;
import com.urlshortener.component.SnowflakeIdGenerator;
import com.urlshortener.component.UrlValidator;
import com.urlshortener.dto.ShortenUrlRequest;
import com.urlshortener.dto.ShortenUrlResponse;
import com.urlshortener.entity.ShortUrl;
import com.urlshortener.repository.ShortUrlRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * UrlShortenService 單元測試
 */
@ExtendWith(MockitoExtension.class)
class UrlShortenServiceTest {

    @Mock
    private ShortUrlRepository shortUrlRepository;

    @Mock
    private UrlValidator urlValidator;

    @Mock
    private HashGenerator hashGenerator;

    @Mock
    private Base62Encoder base62Encoder;

    @Mock
    private SnowflakeIdGenerator snowflakeIdGenerator;

    @InjectMocks
    private UrlShortenService urlShortenService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(urlShortenService, "domainBaseUrl", "http://localhost:8080");
    }

    @Test
    @DisplayName("常態自動生成短網址: 無碰撞，成功寫入並回傳 6 位短碼")
    void testCreateShortUrl_Normal() {
        String url = "https://example.com/page";
        ShortenUrlRequest request = ShortenUrlRequest.builder().originalUrl(url).build();

        when(urlValidator.validateAndNormalizeUrl(url)).thenReturn(url);
        when(hashGenerator.hash64(url)).thenReturn(123456789L);
        when(hashGenerator.hash32(url)).thenReturn(987654L);
        when(base62Encoder.encode(987654L)).thenReturn("4gfFC3");
        when(shortUrlRepository.findActiveByOriginalUrlHashAndOriginalUrl(eq(123456789L), eq(url), any(Instant.class)))
                .thenReturn(Optional.empty());
        when(shortUrlRepository.existsByShortKey("4gfFC3")).thenReturn(false);
        when(shortUrlRepository.save(any(ShortUrl.class))).thenAnswer(inv -> {
            ShortUrl s = inv.getArgument(0);
            s.setCreatedAt(Instant.now());
            return s;
        });

        ShortenUrlResponse response = urlShortenService.createShortUrl(request);

        assertThat(response.getShortKey()).isEqualTo("4gfFC3");
        assertThat(response.getShortUrl()).isEqualTo("http://localhost:8080/4gfFC3");
        assertThat(response.getIsCustom()).isFalse();
        verify(shortUrlRepository).save(any(ShortUrl.class));
    }

    @Test
    @DisplayName("冪等性測試: 既有有效紀錄直接重用回傳，不重複寫入 DB")
    void testCreateShortUrl_IdempotencyReuse() {
        String url = "https://example.com/page";
        ShortenUrlRequest request = ShortenUrlRequest.builder().originalUrl(url).build();
        ShortUrl existing = ShortUrl.builder()
                .shortKey("existingKey")
                .originalUrl(url)
                .originalUrlHash(123456789L)
                .isCustom(false)
                .status(ShortUrl.STATUS_ACTIVE)
                .createdAt(Instant.now())
                .build();

        when(urlValidator.validateAndNormalizeUrl(url)).thenReturn(url);
        when(hashGenerator.hash64(url)).thenReturn(123456789L);
        when(shortUrlRepository.findActiveByOriginalUrlHashAndOriginalUrl(eq(123456789L), eq(url), any(Instant.class)))
                .thenReturn(Optional.of(existing));

        ShortenUrlResponse response = urlShortenService.createShortUrl(request);

        assertThat(response.getShortKey()).isEqualTo("existingKey");
        verify(shortUrlRepository, never()).save(any(ShortUrl.class));
    }

    @Test
    @DisplayName("碰撞加鹽重試: 第 0 次碰撞，第 1 次加鹽成功產出短碼")
    void testCreateShortUrl_CollisionResolvedWithSalt() {
        String url = "https://example.com/page";
        ShortenUrlRequest request = ShortenUrlRequest.builder().originalUrl(url).build();

        when(urlValidator.validateAndNormalizeUrl(url)).thenReturn(url);
        when(hashGenerator.hash64(url)).thenReturn(123456789L);
        when(hashGenerator.hash32(url)).thenReturn(111111L);
        when(base62Encoder.encode(111111L)).thenReturn("collide0");

        when(shortUrlRepository.findActiveByOriginalUrlHashAndOriginalUrl(eq(123456789L), eq(url), any(Instant.class)))
                .thenReturn(Optional.empty());
        when(shortUrlRepository.existsByShortKey("collide0")).thenReturn(true);

        when(hashGenerator.hashWithSalt(url, 1)).thenReturn(222222L);
        when(base62Encoder.encode(222222L)).thenReturn("saltKey1");
        when(shortUrlRepository.existsByShortKey("saltKey1")).thenReturn(false);

        when(shortUrlRepository.save(any(ShortUrl.class))).thenAnswer(inv -> inv.getArgument(0));

        ShortenUrlResponse response = urlShortenService.createShortUrl(request);

        assertThat(response.getShortKey()).isEqualTo("saltKey1");
    }

    @Test
    @DisplayName("極端碰撞降級: 重試 3 次依然碰撞，觸發雪花演算法 Fallback")
    void testCreateShortUrl_SnowflakeFallback() {
        String url = "https://example.com/page";
        ShortenUrlRequest request = ShortenUrlRequest.builder().originalUrl(url).build();

        when(urlValidator.validateAndNormalizeUrl(url)).thenReturn(url);
        when(hashGenerator.hash64(url)).thenReturn(123456789L);
        when(hashGenerator.hash32(url)).thenReturn(100L);
        when(base62Encoder.encode(100L)).thenReturn("key0");

        when(shortUrlRepository.findActiveByOriginalUrlHashAndOriginalUrl(eq(123456789L), eq(url), any(Instant.class)))
                .thenReturn(Optional.empty());
        when(shortUrlRepository.existsByShortKey(anyString())).thenReturn(true);

        when(hashGenerator.hashWithSalt(eq(url), any(Integer.class))).thenReturn(200L);
        when(base62Encoder.encode(200L)).thenReturn("saltedKey");

        when(snowflakeIdGenerator.nextId()).thenReturn(999999999L);
        when(base62Encoder.encode(999999999L)).thenReturn("snowflakeKey");

        when(shortUrlRepository.save(any(ShortUrl.class))).thenAnswer(inv -> inv.getArgument(0));

        ShortenUrlResponse response = urlShortenService.createShortUrl(request);

        assertThat(response.getShortKey()).isEqualTo("snowflakeKey");
        verify(snowflakeIdGenerator).nextId();
    }

    @Test
    @DisplayName("自訂別名成功: 格式合法且唯一，成功儲存並回傳")
    void testCreateShortUrl_CustomAliasSuccess() {
        String url = "https://example.com/promo";
        String alias = "promo-2026";
        ShortenUrlRequest request = ShortenUrlRequest.builder()
                .originalUrl(url)
                .customAlias(alias)
                .build();

        when(urlValidator.validateAndNormalizeUrl(url)).thenReturn(url);
        when(hashGenerator.hash64(url)).thenReturn(555555L);
        when(shortUrlRepository.findByShortKey(alias)).thenReturn(Optional.empty());
        when(shortUrlRepository.save(any(ShortUrl.class))).thenAnswer(inv -> inv.getArgument(0));

        ShortenUrlResponse response = urlShortenService.createShortUrl(request);

        assertThat(response.getShortKey()).isEqualTo("promo-2026");
        assertThat(response.getIsCustom()).isTrue();
        verify(urlValidator).validateCustomAlias(alias);
    }

    @Test
    @DisplayName("自訂別名重複衝突: 已被佔用且有效，拋出 40901 業務異常")
    void testCreateShortUrl_CustomAliasConflict() {
        String url = "https://example.com/promo";
        String alias = "promo-2026";
        ShortenUrlRequest request = ShortenUrlRequest.builder()
                .originalUrl(url)
                .customAlias(alias)
                .build();

        ShortUrl existing = ShortUrl.builder()
                .shortKey(alias)
                .status(ShortUrl.STATUS_ACTIVE)
                .expiredAt(null)
                .build();

        when(urlValidator.validateAndNormalizeUrl(url)).thenReturn(url);
        when(hashGenerator.hash64(url)).thenReturn(555555L);
        when(shortUrlRepository.findByShortKey(alias)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> urlShortenService.createShortUrl(request))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException be = (BusinessException) e;
                    assertThat(be.getCode()).isEqualTo(40901);
                });
    }

    @Test
    @DisplayName("設定 TTL: expiredAt 正確計算為目前時間加上 TTL 秒數")
    void testCreateShortUrl_WithTTL() {
        String url = "https://example.com/temp";
        ShortenUrlRequest request = ShortenUrlRequest.builder()
                .originalUrl(url)
                .ttlInSeconds(3600L)
                .build();

        when(urlValidator.validateAndNormalizeUrl(url)).thenReturn(url);
        when(hashGenerator.hash64(url)).thenReturn(123L);
        when(hashGenerator.hash32(url)).thenReturn(456L);
        when(base62Encoder.encode(456L)).thenReturn("ttlKey");
        when(shortUrlRepository.findActiveByOriginalUrlHashAndOriginalUrl(eq(123L), eq(url), any(Instant.class)))
                .thenReturn(Optional.empty());
        when(shortUrlRepository.existsByShortKey("ttlKey")).thenReturn(false);
        when(shortUrlRepository.save(any(ShortUrl.class))).thenAnswer(inv -> inv.getArgument(0));

        Instant before = Instant.now().plusSeconds(3595);
        ShortenUrlResponse response = urlShortenService.createShortUrl(request);
        Instant after = Instant.now().plusSeconds(3605);

        assertThat(response.getExpiredAt()).isNotNull();
        assertThat(response.getExpiredAt()).isBetween(before, after);
    }

}

