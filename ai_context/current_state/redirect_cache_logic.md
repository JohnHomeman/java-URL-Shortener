# 短網址轉址與快取模組現狀 (Redirect & Cache Logic)

## 1. 模組簡介
負責短網址重定向轉址（HTTP 302 Found）、Redis 高效能快取層（Cache-Aside 模式）、快取穿透防禦（空值標記 `__NULL__`）、快取雪崩防護（TTL 隨機抖動）、動態有效期限映射與快取容錯降級。

## 2. 核心架構與轉址邏輯
- **轉址流程**：
  1. 接收 `GET /{shortKey}` 請求。
  2. 優先查詢 Redis 快取鍵 `short_url:key:{shortKey}`。
  3. 若命中空值標記 `__NULL__`，觸發快取穿透防護，快速回傳 `40401 SHORT_URL_NOT_FOUND`。
  4. 若命中有效資料，檢核狀態（停用 `40301`、過期 `41001`），通過後發出 `HTTP 302 Found` 重定向至原始網址（Header `Location`）。
  5. 若快取未命中，回源 MySQL `short_urls` 表查詢，查無資料則寫入空值快取（TTL 60s），查有資料則動態回填 Redis 並回傳 `HTTP 302`。
- **快取防護演算法**：
  - **空值快取 (Negative Caching)**：未命中 DB 寫入 `__NULL__`，TTL = 60 秒。
  - **隨機抖動 (Random Jitter)**：永久有效短網址快取 TTL 為 $86400 + \text{Random}(0, 300) \text{ 秒}$。
  - **過期對齊**：帶有 TTL 的短網址，快取 TTL 取 $\min(\text{remaining\_seconds}, 86400 + \text{Jitter})$。
- **高可用容錯降級**：Redis 異常時捕捉日誌並自動降級查詢 DB，核心轉址業務不中斷。

## 3. 快取 Schema 與連線配置
- **快取鍵格式**：`short_url:key:{shortKey}`
- **快取值結構**：JSON 包含 `original_url` (String), `status` (Integer), `expired_at` (Instant/String)
- **Redis 連線**：Lettuce 連線池，預設連線至 `localhost:6379`。

## 4. 介面端點
- `GET /{shortKey}`：短網址重定向端點（HTTP 302 Found）。
