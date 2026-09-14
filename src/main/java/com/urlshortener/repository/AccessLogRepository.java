package com.urlshortener.repository;

import com.urlshortener.entity.AccessLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

/**
 * 存取日誌資料庫存取介面 (引用 Spec §2, §4.1, §5.2)
 */
@Repository
public interface AccessLogRepository extends JpaRepository<AccessLog, Long>, AccessLogRepositoryCustom {

    /**
     * 依短碼與可選時間範圍分頁查詢存取日誌
     */
    @Query("SELECT a FROM AccessLog a WHERE a.shortKey = :shortKey " +
           "AND (:startTime IS NULL OR a.accessedAt >= :startTime) " +
           "AND (:endTime IS NULL OR a.accessedAt <= :endTime) " +
           "ORDER BY a.accessedAt DESC, a.id DESC")
    Page<AccessLog> findByShortKeyAndTimeRange(
            @Param("shortKey") String shortKey,
            @Param("startTime") Instant startTime,
            @Param("endTime") Instant endTime,
            Pageable pageable
    );

    /**
     * 查詢短碼最新一筆存取紀錄
     */
    Optional<AccessLog> findTopByShortKeyOrderByAccessedAtDesc(String shortKey);
}
