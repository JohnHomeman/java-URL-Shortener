package com.urlshortener.service;

import com.urlshortener.common.exception.BusinessException;
import com.urlshortener.common.exception.ErrorCode;
import com.urlshortener.component.RedisCacheHelper;
import com.urlshortener.dto.ShortUrlCacheDto;
import com.urlshortener.entity.ShortUrl;
import com.urlshortener.repository.ShortUrlRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * 短網址轉址重定向服務 (引用 Spec §3.1, §3.2, §6.2)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UrlRedirectService {

    private final ShortUrlRepository shortUrlRepository;
    private final RedisCacheHelper redisCacheHelper;

    /**
     * 依短碼取得原始長網址（Cache-Aside 快取優先，未命中回源 DB）
     *
     * @param shortKey 短碼
     * @return 原始長網址
     */
    @Transactional(readOnly = true)
    public String getOriginalUrl(String shortKey) {
        if (shortKey == null || shortKey.isBlank()) {
            throw new BusinessException(ErrorCode.SHORT_URL_NOT_FOUND, "empty");
        }

        String trimmedKey = shortKey.trim();
        Instant now = Instant.now();

        // 1. 第一層：查詢 Redis 快取
        RedisCacheHelper.CacheResult cacheResult = redisCacheHelper.get(trimmedKey);

        // 1.1 快取命中空值標記 -> 觸發快取穿透防禦，快速失敗
        if (cacheResult.isHitNull()) {
            log.debug("Cache penetration prevented for shortKey={}", trimmedKey);
            throw new BusinessException(ErrorCode.SHORT_URL_NOT_FOUND, trimmedKey);
        }

        // 1.2 快取命中正常資料 -> 檢核狀態與有效期限
        if (cacheResult.isHitValid()) {
            ShortUrlCacheDto cacheDto = cacheResult.getData();
            if (cacheDto.isDisabled()) {
                throw new BusinessException(ErrorCode.SHORT_URL_DISABLED);
            }
            if (cacheDto.isExpired(now)) {
                throw new BusinessException(ErrorCode.SHORT_URL_EXPIRED);
            }
            log.debug("Cache hit for shortKey={}, redirecting to {}", trimmedKey, cacheDto.getOriginalUrl());
            return cacheDto.getOriginalUrl();
        }

        // 2. 第二層：快取未命中 (Cache Miss) 或 Redis 異常降級 -> 回源 DB 查詢
        Optional<ShortUrl> shortUrlOpt = shortUrlRepository.findByShortKey(trimmedKey);

        // 2.1 DB 查無紀錄 -> 寫入 Redis 空值快取（TTL 60s），拋出 40401
        if (shortUrlOpt.isEmpty()) {
            log.debug("DB miss for shortKey={}, setting null cache", trimmedKey);
            redisCacheHelper.setNull(trimmedKey);
            throw new BusinessException(ErrorCode.SHORT_URL_NOT_FOUND, trimmedKey);
        }

        ShortUrl shortUrl = shortUrlOpt.get();

        // 2.2 狀態檢核：已停用
        if (shortUrl.getStatus() != null && shortUrl.getStatus() == ShortUrl.STATUS_DISABLED) {
            throw new BusinessException(ErrorCode.SHORT_URL_DISABLED);
        }

        // 2.3 狀態檢核：已逾期
        if (shortUrl.getExpiredAt() != null && !shortUrl.getExpiredAt().isAfter(now)) {
            throw new BusinessException(ErrorCode.SHORT_URL_EXPIRED);
        }

        // 2.4 有效紀錄 -> 動態回填 Redis 快取並回傳原始網址
        ShortUrlCacheDto cacheDto = ShortUrlCacheDto.fromEntity(shortUrl);
        redisCacheHelper.set(trimmedKey, cacheDto);
        log.debug("DB hit for shortKey={}, backfilled cache and redirecting to {}", trimmedKey, shortUrl.getOriginalUrl());

        return shortUrl.getOriginalUrl();
    }
}
