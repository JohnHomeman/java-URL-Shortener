# 短網址核心生成模組現狀 (Shorten Core Logic)

## 1. 模組簡介
負責短網址生成、MurmurHash3 (32-bit) 雜湊與 Base62 編碼、加鹽防碰撞重試、雪花演算法 (Snowflake ID) 安全降級保底、長網址冪等查重、自訂別名校驗以及有效期限（TTL）管理與資料庫持久化儲存。

## 2. 核心架構與演算法
- **常態短碼生成**：`MurmurHash3 (32-bit)` 計算原始長網址 Hash 值 $\to$ `Base62.encode()` 編碼為 6 位字串。
- **碰撞重試 (Salt Retry)**：若短碼已存在且長網址不同，使用 `url + "[SALT_i]"`（$i=1..3$）重試雜湊最多 3 次。
- **安全降級 (Snowflake Fallback)**：若 3 次加鹽重試仍碰撞，調用 `SnowflakeIdGenerator.nextId()` 產生全域唯一 64-bit ID 並轉 Base62。
- **長網址冪等重用**：依據 `original_url_hash` 索引與 `original_url` 比對，若有未過期的有效非自訂短碼則直接重用。
- **自訂別名 (Custom Alias)**：正則 `^[a-zA-Z0-9_-]{4,16}$`，過濾系統保留字（`api`, `health`, `actuator`, `swagger` 等），衝突時回傳 40901。
- **有效期限 (TTL)**：支援可選 `ttl_in_seconds`（最小 60 秒），過期時間記錄於 `expired_at`。
- **過期碰撞邊界與排程清理**：同一網址過期後再次建立會因舊紀錄佔據 key 而觸發加鹽重試（重複過期超過 4 次將觸發雪花演算法保底），規劃於後續階段透過每日 Cron Job 排程清理過期紀錄釋放 key。

## 3. 資料庫 Schema (`short_urls`)
- `id`: BIGINT AUTO_INCREMENT (PK)
- `short_key`: VARCHAR(16) UNIQUE (UK)
- `original_url`: VARCHAR(2048)
- `original_url_hash`: BIGINT (IDX)
- `is_custom`: TINYINT(1) (0: 系統生成, 1: 自訂)
- `status`: TINYINT (1: 啟用, 0: 停用, 2: 已過期)
- `expired_at`: DATETIME NULL (IDX with status)
- `created_at`, `updated_at`: DATETIME

## 4. 介面端點
- `POST /api/v1/urls/shorten`：建立短網址。
