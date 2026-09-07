package com.urlshortener.repository;

import com.urlshortener.entity.ShortUrl;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

/**
 * 短網址資料庫操作介面
 */
@Repository
public interface ShortUrlRepository extends JpaRepository<ShortUrl, Long> {

    /**
     * 依短碼查詢短網址紀錄
     */
    Optional<ShortUrl> findByShortKey(String shortKey);

    /**
     * 檢查短碼是否存在
     */
    boolean existsByShortKey(String shortKey);

    /**
     * 透過 Hash 與長網址快速查詢有效的既有短網址（供長網址冪等性重用）
     */
    @Query("SELECT s FROM ShortUrl s WHERE s.originalUrlHash = :originalUrlHash " +
           "AND s.originalUrl = :originalUrl " +
           "AND s.isCustom = false " +
           "AND s.status = 1 " +
           "AND (s.expiredAt IS NULL OR s.expiredAt > :now) " +
           "ORDER BY s.id DESC LIMIT 1")
    Optional<ShortUrl> findActiveByOriginalUrlHashAndOriginalUrl(
            @Param("originalUrlHash") Long originalUrlHash,
            @Param("originalUrl") String originalUrl,
            @Param("now") Instant now
    );
}
