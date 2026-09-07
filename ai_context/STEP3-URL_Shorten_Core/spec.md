# STEP3-URL_Shorten_Core: 短網址核心生成演算法與資料持久化

> <!-- [IMPORTANT] 本文件為唯一真相 (The What)。建議遵循：背景 -> 邏輯 -> 資料 -> 合約 的順序撰寫。 -->
> <!-- 職責邊界：Spec 只回答「系統做什麼、資料長什麼樣、期望結果是什麼」。-->
> <!-- ❌ 禁止出現在 Spec 中：檔案路徑、mvn/gradle 指令、cURL 命令、Migration 步驟。這些歸 Plan。 -->

## 1. 需求背景 (Background)
在縮網址服務中，將冗長的原始網址（Original URL）轉換為精簡且唯一的短碼（Short Key）是整個系統的核心起點。為滿足高吞吐量、低碰撞率、支援使用者自訂別名（Custom Alias）以及具備有效期限（TTL）管理，本階段需設計並實作高效能的短碼生成演算法、碰撞探測機制與關聯式資料庫持久化儲存架構。

## 2. 系統設計與影響範圍 (System Design)
- **核心目標**：實作短網址生成核心邏輯（MurmurHash3 + Base62 編碼）、防碰撞重試機制、自訂別名驗證、過期時間計算與資料庫 CRUD 持久化。
- **影響分層**：

| 分層 | 受影響模組（職責描述） | 變更類型 |
| :--- | :--- | :--- |
| Controller | UrlShortenController — 接收短網址生成請求，進行輸入格式校驗並調度業務服務 | 新增 |
| Service | UrlShortenService — 負責短網址建立流程編排（正規化、查重、生成、防撞重試、持久化） | 新增 |
| Component | Base62Encoder — 提供十進位整數/長整數與 Base62 字符集之相互轉換 | 新增 |
| Component | HashGenerator — 封裝 MurmurHash3 雜湊計算與加鹽雜湊機制 | 新增 |
| Component | SnowflakeIdGenerator — 封裝雪花演算法 ID 生成器，供極端碰撞時安全降級產出全域唯一短碼 | 新增 |
| Component | UrlValidator — 負責 URL 協議正規化、格式合法性與安全性（防迴圈/防本機指向）校驗 | 新增 |
| Repository | ShortUrlRepository — 封裝短網址資料庫操作（依 short_key 或 hash_value 查詢、寫入） | 新增 |
| Entity | ShortUrl — 短網址資料持久化實體結構定義 | 新增 |

## 3. 核心業務邏輯 (Core Logic)

### 3.1 原始網址校驗與正規化 (URL Validation & Normalization)
- **協議限制**：僅允許 `http://` 與 `https://` 開頭之合法 URL。
- **長度限制**：原始網址最大長度限制為 2048 字元。
- **正規化處理**：去除首尾空白字元；若未包含標準 Port 號則保持原始協議主機名格式。
- **防自指向迴圈 (Self-Referencing Check)**：若輸入的原始 URL 之 Host 與本縮網址服務網域名稱相同，視為遞迴自指向，直接拒絕生成以防無限轉址循環。

### 3.2 系統短碼生成與碰撞處理演算法 (Hash + Base62 Collision Resolution)
- **數學基礎**：
  - 採用 **MurmurHash3 (32-bit)** 演算法計算 `original_url` 的 Hash 值，取無符號整數（範圍 $0 \sim 2^{32}-1 \approx 42.9$ 億）。
  - 透過 **Base62 字符集**（`0-9`, `a-z`, `A-Z`，共 62 個可見字元）將十進位整數編碼為 6 位長度之字串（$62^6 \approx 568$ 億種組合，足以容納 32-bit Hash 空間）。
- **流程與狀態流轉 (Collision Resolution Flow)**：
  1. 計算 `hash_value = MurmurHash3_32(normalized_url)`。
  2. 編碼得出候選短碼 `candidate_key = Base62.encode(hash_value)`。
  3. 查詢資料庫中 `short_key = candidate_key` 之紀錄：
     - **情境 1（無此短碼）**：未發生碰撞，直接將 `(candidate_key, original_url, hash_value)` 寫入資料庫。
     - **情境 2（短碼已存在且 original_url 相同）**：長網址重複請求，若紀錄未過期，直接回傳既有短碼（保證冪等性）。
     - **情境 3（短碼已存在但 original_url 不同）**：發生雜湊碰撞（Hash Collision）。
  4. **碰撞重試 (Salt Retry)**：
     - 若發生碰撞，追加遞增鹽值 `salt`（如 `normalized_url + "[SALT_i]"`）重新計算 MurmurHash3 並 Base62 編碼。
     - 最大重試次數限制為 3 次。
     - 若重試 3 次依然碰撞，採用雪花演算法（Snowflake ID）產生全域唯一 64-bit ID 並經 Base62 編碼作為安全降級（Fallback），確保 100% 生成唯一短碼。
- **【特殊邊界情境備註：TTL 重複過期與防碰撞行為】**：
  - 當同一長網址設定 TTL 過期後再次請求，由於舊紀錄仍佔據 DB 唯一鍵，會觸發加鹽防碰撞重試（Salt 1..3）。
  - 若同一網址累計重複過期並重新請求超過 4 次（導致原本 Key 與 3 次加鹽 Key 皆被歷史過期紀錄佔據），第 5 次將會觸發雪花演算法（長度約 10~11 位）安全保底生成。
  - **未來改良規劃**：未來將透過 **定時排程清理機制（Cron Job）** 每日清理/歸檔過期短網址以釋放 key 空間（詳見第 9 章 Backlog）。

### 3.3 自訂別名業務規則 (Custom Alias Logic)
- **字元規範**：限定正則格式 `^[a-zA-Z0-9_-]{4,16}$`（4 至 16 個英文字母、數字、底線或破折號）。
- **系統保留字過濾**：禁止使用系統保留關鍵字作為短碼（如 `api`, `health`, `actuator`, `swagger`, `error`, `metrics`, `static` 等）。
- **唯一性檢查**：若自訂別名在資料庫已存在且尚未過期，則拒絕請求並回傳衝突錯誤。

### 3.4 有效期限管理 (TTL & Expiration)
- **過期時間計算**：`expired_at = (ttl_in_seconds != null) ? (now + ttl_in_seconds) : null`。
- **預設規則**：若未指定 `ttl_in_seconds`，預設為永久有效（`expired_at = null`）。
- **狀態判定**：
  - 正常啟用 (`status = 1`)：`status == 1 && (expired_at == null || expired_at > now)`。
  - 已過期 (`status = 2`)：`expired_at != null && expired_at <= now`。

## 4. 資料架構 (Data Schema)

### 4.1 SQL DDL
> <!-- Migration 腳本存放標注：`migration/common/V1__create_short_urls_table.sql` -->
> <!-- 版本控制遵循 Flyway 規範：`V<Version>__<Description>.sql` -->

```sql
-- 檔名：migration/common/V1__create_short_urls_table.sql
-- 短網址主表
CREATE TABLE `short_urls` (
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
  UNIQUE KEY `uk_short_key` (`short_key`), -- 唯一索引：確保短碼全域唯一
  KEY `idx_url_hash` (`original_url_hash`), -- 索引：透過 Hash 快速反查重複長網址
  KEY `idx_expired_at_status` (`expired_at`, `status`) -- 組合索引：供定時清理過期網址任務使用
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='短網址映射資料表';
```

## 5. 介面合約 (API Contract)

### 5.1 REST API — 生成短網址 (Create Short URL)
- **Endpoint**: `POST /api/v1/urls/shorten`
- **Request Headers**: `Content-Type: application/json`
- **Request Body**:
```json
{
  "original_url": "https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/302",
  "custom_alias": "mdn-302",
  "ttl_in_seconds": 86400
}
```
- **欄位規範**：

| 欄位名稱 | 型態 | 必填 | 驗證規則 / 限制 | 描述 |
| :--- | :--- | :--- | :--- | :--- |
| `original_url` | String | 是 | 必須為合法 `http://` 或 `https://` 網址，長度 $\le 2048$ | 待縮短之原始網址 |
| `custom_alias` | String | 否 | 正則 `^[a-zA-Z0-9_-]{4,16}$`，且非系統保留字 | 使用者指定之自訂短碼 |
| `ttl_in_seconds` | Long | 否 | 若有傳入，最小值為 60 秒 | 存活有效秒數 (TTL) |

- **Success Response (200 OK)**:
```json
{
  "code": 0,
  "message": "success",
  "data": {
    "short_key": "mdn-302",
    "short_url": "http://localhost:8080/mdn-302",
    "original_url": "https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/302",
    "is_custom": true,
    "expired_at": "2026-09-07T09:30:00Z",
    "created_at": "2026-09-06T09:30:00Z"
  },
  "timestamp": 1757151000000
}
```

- **Error Responses**:

| HTTP Code | Error Code (`code`) | 條件 | 回傳 Message | 回傳 Data |
| :--- | :--- | :--- | :--- | :--- |
| 400 | 40001 | 請求參數欄位為空或型態錯誤 | `Invalid request parameters: [field errors]` | `null` |
| 400 | 40002 | `original_url` 格式不合法或非支援協議 | `Invalid original URL format` | `null` |
| 400 | 40003 | `original_url` 包含本系統網域 (遞迴自指向) | `Recursive self-referencing URL is not allowed` | `null` |
| 400 | 40004 | `custom_alias` 使用了系統保留關鍵字 | `Custom alias contains reserved keyword: [alias]` | `null` |
| 400 | 40005 | `custom_alias` 格式不符合規範 (4-16 位英數_-) | `Invalid custom alias format` | `null` |
| 409 | 40901 | `custom_alias` 已被其他紀錄佔用且尚未過期 | `Custom alias already exists: [alias]` | `null` |
| 500 | 50000 | 雜湊衝突重試耗盡或系統未預期例外 | `Internal server error` | `null` |

- **409 Conflict 錯誤範例**:
```json
{
  "code": 40901,
  "message": "Custom alias already exists: mdn-302",
  "data": null,
  "timestamp": 1757151000000
}
```

## 6. 技術決策與權衡 (Design Decisions)

### 6.1 短碼生成演算法選型 (MurmurHash3 + Base62 vs. 分散式自增 ID)
- **選項 A**：MurmurHash3 (32-bit) + Base62 編碼 + 加鹽碰撞重試
- **選項 B**：雪花算法 (Snowflake) / DB 自增序列 + Base62 編碼
- **決策**：選擇 A。
  - **優點**：MurmurHash3 計算速度極快（單核數 GB/s 吞吐量）、分佈均勻性極佳且天然具備冪等性特徵（相同 URL 雜湊值相同）。
  - **短碼長度**：32-bit 整數轉 Base62 僅需最多 6 個字元（如 `4294967295` -> `4gfFC3`），兼具短小與可讀性。
  - **安全性**：相較於自增 ID，Hash 方式生成的短碼具備非連續性，外部攻擊者無法透過短碼遞增規律遍歷抓取全站資料。
- **風險**：存在百萬分之一等級的雜湊碰撞機率；已透過「碰撞探測 + 3 次加鹽重試 + Snowflake 降級」保證 100% 無碰撞。

### 6.2 持久化資料庫與 ORM 框架選型
- **選項 A**：Spring Data JPA (Hibernate) + MySQL / H2
- **選項 B**：MyBatis / MyBatis-Plus
- **決策**：選擇 A。Spring Boot 原生生態整合度高，透過 Repository 介面即可快速實現具備索引支援的 CRUD 與交易控制，並可無縫相容 H2 內嵌資料庫進行整合測試。
- **風險**：Hibernate 在極端超大批量操作時記憶體開銷較大；本階段均為單筆生成與單筆索引查詢，效能損耗可忽略。

### 6.3 冪等性處理與長網址重複縮短策略
- **選項 A**：相同長網址每次呼叫均生成新短碼
- **選項 B**：相同長網址在無自訂別名且未過期情況下，重用既有短碼
- **決策**：選擇 B。透過 `original_url_hash` 與 `original_url` 比對，若已存在有效紀錄則直接回傳既有短碼。既可大幅節省儲存空間，又能降低短碼空間消耗。
- **風險**：若使用者希望相同網址具有不同過期時間，需傳入不同別名或特定標記；常態情況下重用既有短碼更符合業務效益。

### 6.4 安全性與 SSRF / 惡意重定向防禦
- **決策**：
  1. 嚴格過濾協議，僅允許 `http` / `https`，阻擋 `file://`, `ftp://`, `gopher://`, `javascript:` 等危險協議。
  2. 加入本機主機名／系統網域名稱黑名單檢查，防止利用短網址轉向內網 IP (`127.0.0.1`, `localhost`, `10.x.x.x`, `192.168.x.x`) 或形成轉址死循環。

### 6.5 資料庫結構變更與版本管理策略 (Flyway Migration)
- **選項 A**：採用 Flyway 版本化遷移腳本（置於 `migration/common/` 目錄，遵循 `V<Version>__<Description>.sql` 命名規範）。
- **選項 B**：依賴 Hibernate 實體自動生成 DDL (`ddl-auto: update/create`)。
- **決策**：選擇 A。Flyway-style 版本遷移腳本具備跨環境可重現性、精準 DDL 控制與版本審計能力，嚴禁在正式環境使用 `ddl-auto` 自動修改 Schema，確保資料庫演進完全受版本控制追蹤。
- **風險**：每次 Schema 變更均需撰寫標準 SQL 遷移檔並維護版本號順序。

## 7. 驗證目標與測試數據 (Verification Goals & Test Data)

### 7.1 單元測試測項 (Table-Driven)

| Case | 輸入 (Input) | 條件 (Condition) | 期望輸出 (Expected) |
| :--- | :--- | :--- | :--- |
| Base62 編碼正常數值 | `number: 0` | 呼叫 `Base62Encoder.encode(0)` | `result: "0"` |
| Base62 編碼最大 32-bit | `number: 4294967295L` | 呼叫 `Base62Encoder.encode(4294967295L)` | `result: "4gfFC3"` |
| Base62 解碼正常 | `str: "4gfFC3"` | 呼叫 `Base62Encoder.decode("4gfFC3")` | `result: 4294967295L` |
| MurmurHash3 一致性 | `url: "https://example.com"` | 呼叫 `HashGenerator.hash32("https://example.com")` | 輸出固定非負 32-bit Long 整數 |
| 自訂別名格式合法 | `alias: "my-custom_link"` | 驗證正則 `^[a-zA-Z0-9_-]{4,16}$` | `valid: true` |
| 自訂別名太短 | `alias: "abc"` | 驗證長度 $< 4$ | `valid: false` (拋出 40005) |
| 自訂別名含保留字 | `alias: "health"` | 檢查保留字清單 | 拋出業務異常 `40004 RESERVED_KEYWORD` |
| URL 格式非 HTTP/HTTPS | `url: "ftp://files.example.com"` | 驗證 URL Scheme | 拋出業務異常 `40002 INVALID_URL` |
| URL 格式為自指向 | `url: "http://localhost:8080/test"` | Host 與本服務一致 | 拋出業務異常 `40003 RECURSIVE_URL` |

### 7.2 業務驗收情境 (Acceptance Criteria)
- **情境 A：正常自動生成短網址**：傳入合法 `original_url`（無 `custom_alias`），期望 HTTP 狀態碼為 `200`，`data.short_key` 為 6 位 Base62 字元，且資料庫 `short_urls` 表中新增一筆 `is_custom = 0, status = 1` 紀錄。
- **情境 B：長網址重複請求冪等性**：再次傳入情境 A 相同的 `original_url`，期望回傳與情境 A 完全相同之 `short_key`，且資料庫總筆數不增加。
- **情境 C：成功建立自訂別名短網址**：傳入合法 `custom_alias = "promo-2026"`，期望回傳 `data.short_key = "promo-2026"` 且 `is_custom = true`。
- **情境 D：自訂別名重複衝突拒絕**：再次以相同 `custom_alias = "promo-2026"` 發送建立請求，期望 HTTP 狀態碼為 `409`，回傳錯誤碼 `code = 40901`，訊息包含 `"Custom alias already exists: promo-2026"`。
- **情境 E：設定 TTL 過期時間**：傳入 `ttl_in_seconds = 3600`，期望回傳之 `expired_at` 與資料庫欄位值精確等於建立時間後 3600 秒。
- **情境 F：TTL 過期後重新請求生成新短碼**：傳入 `ttl_in_seconds = 60` 生成短網址，當 60 秒過期後再次以相同 `original_url` 發送請求，系統偵測到既有短碼已失效，將不會重用舊短碼，而是觸發防碰撞機制生成並回傳全新的 `short_key`。

## 8. 預期效益 (Expected Benefits)
- 短碼長度壓低至 6 個字元，字元空間達 568 億，大幅優化轉址連結長度與分享體驗。
- MurmurHash3 雜湊性能比 MD5/SHA256 快 5~10 倍，CPU 佔用極低。
- 完善的冪等性與快查索引設計，避免儲存膨脹與重複資料寫入。

## 9. Backlog / Future Scope（下期規劃）
- **[STEP4-URL_Redirect_Cache]**：實作短碼轉址重定向（HTTP 302/301 Redirect）、Redis 快取層與快取穿透防禦（下期）。
- **[STEP5-Access_Metrics]**：實作轉址存取事件日誌、非同步點擊次數累加與統計指標 API（下期）。
- **[STEP6-Scheduled_Cleanup]**：實作 Spring `@Scheduled` / Quartz Cron Job 每日定時清理/歸檔過期短網址紀錄，釋放佔用之 `short_key` 與避免長網址重複過期導致之多重碰撞問題。
