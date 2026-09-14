-- 1. 修改短網址主表：擴充累積點擊次數欄位
ALTER TABLE `short_urls`
  ADD COLUMN `click_count` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '累積點擊次數' AFTER `status`;

-- 2. 新增短網址存取明細日誌表
CREATE TABLE `access_logs` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主鍵 ID',
  `short_key` VARCHAR(16) NOT NULL COMMENT '短網址短碼',
  `ip` VARCHAR(45) NOT NULL DEFAULT '' COMMENT '存取者 IP (支援 IPv4/IPv6)',
  `user_agent` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '瀏覽器/客戶端 User-Agent',
  `referer` VARCHAR(1024) NOT NULL DEFAULT '' COMMENT '來源網址 (Referer)',
  `accessed_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '存取時間',
  PRIMARY KEY (`id`),
  KEY `idx_short_key_accessed_at` (`short_key`, `accessed_at`), -- 索引：依短碼與時間範圍高效查詢與分頁
  KEY `idx_accessed_at` (`accessed_at`) -- 索引：全域歷史數據歸檔與定期清理
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='短網址存取明細日誌表';
