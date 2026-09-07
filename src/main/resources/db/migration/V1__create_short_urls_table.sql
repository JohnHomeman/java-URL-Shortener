-- 檔名：db/migration/V1__create_short_urls_table.sql
-- 說明：建立短網址核心映射資料表 (short_urls)

CREATE TABLE IF NOT EXISTS `short_urls` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主鍵 ID',
  `short_key` VARCHAR(16) NOT NULL COMMENT '短網址代碼 (Base62 編碼或自訂別名)',
  `original_url` VARCHAR(2048) NOT NULL COMMENT '原始完整網址',
  `original_url_hash` BIGINT UNSIGNED NOT NULL COMMENT '原始網址 MurmurHash3 數值 (供反向快速比對)',
  `is_custom` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否為自訂別名 [0: 系統自動生成, 1: 使用者自訂]',
  `status` TINYINT NOT NULL DEFAULT 1 COMMENT '狀態 [1: 啟用, 0: 停用, 2: 已過期]',
  `expired_at` DATETIME NULL DEFAULT NULL COMMENT '過期時間 (NULL 表示永久有效)',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '建立時間',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新時間',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_short_key` (`short_key`),
  KEY `idx_url_hash` (`original_url_hash`),
  KEY `idx_expired_at_status` (`expired_at`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='短網址映射資料表';
