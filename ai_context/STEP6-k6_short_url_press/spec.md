# STEP6-k6_short_url_press: 短網址轉址高併發壓力測試

> <!-- [IMPORTANT] 本文件為唯一真相 (The What)。建議遵循：背景 -> 邏輯 -> 資料 -> 合約 的順序撰寫。 -->
> <!-- 職責邊界：Spec 只回答「系統做什麼、資料長什麼樣、期望結果是什麼」。-->
> <!-- ❌ 禁止出現在 Spec 中：檔案路徑、mvn/gradle 指令、cURL 命令、Migration 步驟。這些歸 Plan。 -->

## 1. 需求背景 (Background)

短網址系統完成快取轉址（STEP4）與存取指標非同步收集（STEP5）後，核心轉址鏈路已涵蓋快取層、資料庫層與非同步緩衝三個環節，但尚未透過實際高併發流量驗證系統在壓力下的行為邊界。本次任務使用 k6 對轉址端點 `GET /{shortKey}` 進行壓力測試，目標是量化 P95/P99 延遲、觀察錯誤率，並確認 302 回應及 Location Header 的正確性在高負載下不發生偏差。

## 2. 系統設計與影響範圍 (System Design)

- **核心目標**：以 k6 腳本模擬真實流量對轉址端點進行壓力測試，驗證功能正確性（302 + Location Header）與效能穩定性（P95/P99 延遲、錯誤率閾值）。
- **⚠️ 測試前置條件 (Pre-conditions)**：
  - **服務重啟與環境清理**：執行各階段壓測（`short_url_press.js` 與 `short_url_press_hotkey.js`）前，**必須依序執行以下重置作業**，確保測試環境完全純淨：
    1. **清空 MySQL**：清理 `short_urls` 與 `access_logs` 資料表。
    2. **清空 Redis**：執行 `FLUSHDB` 清除所有快取與存取統計鍵。
    3. **重啟 Spring Boot 服務**：徹底重啟 JVM 行程，重置連線池（Tomcat/Lettuce）與 GC 狀態，並確保應用層日誌設為 `INFO`（避免高頻 DEBUG 輸出引發 I/O 阻塞）。
- **影響分層**：

| 分層 | 受影響模組（職責描述） | 變更類型 |
| :--- | :--- | :--- |
| 測試腳本 | k6 壓測腳本 — 執行 setup/teardown、併發轉址請求、即時指標收集 | 新增 |
| 測試資料 | 短網址建立 API — setup 階段透過 `POST /api/v1/urls/shorten` 批次建立測試短網址 | 借用既有 |

## 3. 核心業務邏輯 (Core Logic)

### 3.1 測試流程設計 (Test Flow)

```
[Setup]
  - 全域壓測 (short_url_press.js)：POST /api/v1/urls/shorten × 50 筆
  - 熱點壓測 (short_url_press_hotkey.js)：POST /api/v1/urls/shorten × 1 筆 (/target/hot_key_test)
        │
        ▼
[壓測主體 — 四段負載曲線]
  Warm-up (30s, 10 VU)
  → Ramp-up (1m, 10→200 VU)
  → Sustain (2m, 200 VU)
  → Ramp-down (30s, 200→0 VU)
        │  每次 iteration：
        │  全域模式隨機取一組 / 熱 Key 模式固定取該組 { shortKey, originalUrl }
        │  GET /{shortKey}，redirects: 0（關閉自動跳轉）
        │  驗證 status === 302
        │  驗證 Location header === originalUrl
        ▼
[Teardown]（Optional）
  可對短網址補發指標查詢，確認 click_count > 0 與 MySQL 持久化數據
```

### 3.2 關鍵設計決策說明

- **`redirects: 0`**：關閉 k6 自動跟隨 302 跳轉，直接在短網址服務回應層驗證 302 與 Location Header，避免測到目標網站而非系統本身。
- **隨機選碼 vs. 單一熱點選碼**：
  - 全域壓測：隨機從 50 個短碼中挑選，分散快取命中分佈。
  - 熱 Key 壓測：僅建立 1 組特定標示的短網址（`/target/hot_key_test`），200 VU 全數打同一 Key，觀察極致熱點效能與連線佇列。
- **雙重驗證**：不只驗 status 是否為 302，同時驗 Location Header 是否等於建立時的原始網址，防止「有跳轉但跳錯位置」的隱藏 bug。

### 3.3 負載曲線規格

| 階段 | 持續時間 | VU 數量 | 說明 |
| :--- | :--- | :--- | :--- |
| Warm-up | 30s | 10 | 讓 JVM JIT 熱身、快取預熱 |
| Ramp-up | 60s | 10 → 200 | 線性拉升，觀察延遲爬升曲線 |
| Sustain | 120s | 200 | 穩定承載，觀察 P95/P99 與錯誤率 |
| Ramp-down | 30s | 200 → 0 | 平滑收尾 |

> **調整建議**：若本機環境無法承受 200 VU，可將 Sustain 目標調低至 50～100 VU，閾值標準不變。

### 3.4 閾值定義 (Thresholds)

| 指標 | 閾值條件 | 說明 |
| :--- | :--- | :--- |
| `http_req_failed` | `rate < 0.01` (錯誤率 < 1%) | 非 2xx/3xx 回應或網路錯誤 |
| `http_req_duration` (P95) | `p(95) < 200` (P95 < 200ms) | 包含 Warm-up 在內的全程 P95 |
| `http_req_duration` (P99) | `p(99) < 500` (P99 < 500ms) | 包含 Warm-up 在內的全程 P99 |
| `location_correct` | `rate == 1.0` (Location 正確率 100%) | 自訂計數器，任何 Location 不符均計失敗 |

## 4. 資料架構 (Data Schema)

### 4.1 測試資料規格

Setup 階段建立的短網址格式如下（以 `POST /api/v1/urls/shorten` 呼叫）：

1. **全域壓測模式**（呼叫 50 次，i = 0..49）：
```json
{
  "original_url": "https://k6-press-test.example.com/target/{i}",
  "custom_alias": null,
  "ttl_in_seconds": null
}
```

2. **熱 Key 專項壓測模式**（呼叫 1 次）：
```json
{
  "original_url": "https://k6-press-test.example.com/target/hot_key_test",
  "custom_alias": null,
  "ttl_in_seconds": null
}
```

```json
// Response 取用欄位
{
  "code": 0,
  "data": {
    "short_key": "abc123",
    "original_url": "https://k6-press-test.example.com/target/..."
  }
}
```

Setup 結果儲存為 `Array<{ shortKey: string, originalUrl: string }>`，作為壓測主體迭代時的取樣來源。

### 4.2 自訂指標 (Custom Metrics)

| 指標名稱 | 類型 | 說明 |
| :--- | :--- | :--- |
| `location_correct` | `Rate` | 每次 iteration 中 Location Header 與預期原始網址完全吻合的比率 |
| `redirect_duration` | `Trend` | 每次轉址請求的完整往返時間（ms），用於 P95/P99 分位數報告 |

## 5. 介面合約 (API Contract)

### 5.1 REST API — 建立短網址（Setup 階段借用）

- **Endpoint**: `POST /api/v1/urls/shorten`
- **Request Body**:
```json
{
  "original_url": "https://k6-press-test.example.com/target/0"
}
```
- **Success Response (200)**:
```json
{
  "code": 0,
  "message": "success",
  "data": {
    "short_key": "abc123",
    "short_url": "http://localhost:8080/abc123",
    "original_url": "https://k6-press-test.example.com/target/0",
    "status": 1,
    "created_at": "2026-09-15T09:00:00",
    "expired_at": null
  },
  "timestamp": 1757995200000
}
```
- **Error Responses**:

| HTTP Code | Error Code | 條件 | 回傳 Message |
| :--- | :--- | :--- | :--- |
| 400 | 40001 | `original_url` 格式非法 | Invalid URL format |
| 500 | 50000 | 系統內部異常 | Internal server error |

---

### 5.2 REST API — 轉址（壓測主體）

- **Endpoint**: `GET /{shortKey}`
- **行為**：服務端回傳 HTTP 302，k6 設定 `redirects: 0` 不跟隨跳轉。
- **Success Response (302)**:

| Header | 值 | 說明 |
| :--- | :--- | :--- |
| `Location` | `{originalUrl}` | 必須完全等於 setup 時建立的原始網址 |

- **驗證點**:
  1. `response.status === 302`
  2. `response.headers['Location'] === shortUrlMap[shortKey].originalUrl`

- **Error Responses**:

| HTTP Code | Error Code | 條件 | 回傳 Message |
| :--- | :--- | :--- | :--- |
| 404 | 40401 | 短碼不存在或已刪除 | Short URL not found |
| 403 | 40301 | 短碼已停用 | Short URL is disabled |
| 410 | 40401 | 短碼已過期 | Short URL is expired |

## 6. 技術決策與權衡 (Design Decisions)

### 6.1 關閉自動跳轉（`redirects: 0`）策略

- **選項 A**：允許 k6 自動跟隨 302（預設行為）。
- **選項 B**：`redirects: 0`，在短網址服務回應層直接驗證。
- **決策**：選擇 B。
  - **理由**：選項 A 的 `http_req_duration` 包含目標網站的回應時間，測的是端對端延遲而非短網址服務本身；且無法個別驗證 Location Header 是否正確（已被自動跟隨消耗）。
- **風險**：需手動在 k6 中處理 3xx 狀態碼，確保 `http_req_failed` 計數器不誤將 302 計入失敗。

### 6.2 隨機選碼 vs. 單一熱點選碼

- **選項 A**：僅建立 1 組特定標記的短網址（`/target/hot_key_test`），所有 iteration 全力打同一 Key（極致熱 Key 情境）。
- **選項 B**：建立 50 組短網址，每次 iteration 從中隨機選取。
- **決策**：同時實作兩種模式（隨機選碼作為全域主壓測，單一短碼作為熱 Key 專項壓測）。
  - **理由**：B 能模擬真實分散流量，驗證多 Key 快取命中穩定性；A 則專門驗證極端熱點下 Redis 單 Key 吞吐上限與後續緩衝層反應。
- **風險**：單 Key 極限高併發下可能引發 Redis 與連線池佇列爭奪，屬預期觀察範圍。

### 6.3 自訂指標 `location_correct` 設計

- **選項 A**：只用內建 `checks` 驗證，不另設 threshold。
- **選項 B**：額外定義 `Rate` 自訂指標，並設定 threshold `rate == 1.0`。
- **決策**：選擇 B。
  - **理由**：內建 `checks` 失敗只會在摘要中顯示，不會導致 k6 以非零碼退出（無法整合 CI 管線）；自訂 threshold 確保任何 Location 偏差都造成測試失敗，提高防護強度。
- **風險**：`rate == 1.0` 閾值嚴苛，任一單次 Location 不符即判定整批失敗；此為刻意設計，因錯誤跳轉屬於嚴重功能缺陷。

## 7. 驗證目標與測試數據 (Verification Goals & Test Data)

### 7.1 單元測試測項 (Table-Driven)

> 壓測腳本無傳統單元測試，此節記錄每個 iteration 的核心驗證邏輯。

| Case | 輸入 (Input) | 條件 (Condition) | 期望輸出 (Expected) |
| :--- | :--- | :--- | :--- |
| 正常轉址 | `GET /{validShortKey}`，`redirects: 0` | 短碼存在且未過期 | `status: 302`，`Location: {originalUrl}` |
| Location 比對正確 | `GET /{shortKey}`，`redirects: 0` | Location Header 與 setup 記錄的 originalUrl 對比 | `location_correct` 計數器 +1（Pass） |
| Location 比對錯誤 | `GET /{shortKey}`，`redirects: 0` | Location Header 與預期不符 | `location_correct` 計數器 +0（Fail），測試整體判定失敗 |
| 不存在短碼 | `GET /{invalidKey}`，`redirects: 0` | 短碼不在 setup 清單中 | `status: 404`，計入 `http_req_failed` |

### 7.2 業務驗收情境 (Acceptance Criteria)

- **情境 A：正確性驗收**：
  - 前置條件：setup 階段成功建立 50 筆短網址，壓測完整執行四段負載曲線（共約 4 分鐘）。
  - 期望：`location_correct` threshold `rate == 1.0` 通過（即每一次轉址 Location Header 均正確）；`http_req_failed rate < 0.01`；`http_req_duration p(95) < 200ms` 且 `p(99) < 500ms`。

- **情境 B：穩定性驗收（Sustain 階段）**：
  - 前置條件：進入 Sustain 階段（200 VU，持續 2 分鐘）。
  - 期望：延遲不隨時間線性爬升（無記憶體洩漏或連線池耗盡跡象）；錯誤率在 Sustain 全程維持 < 1%。

- **情境 C：熱 Key 壓測與快取觀察**：
  - 前置條件：執行熱 Key 專屬壓測腳本（setup 僅建立 1 組 `/target/hot_key_test` 短碼），以 200 VU Sustain 2 分鐘。
  - 期望：`status: 302` 全程維持，快取層（Redis）命中率可透過 Redis `INFO stats` 中的 `keyspace_hits` / `keyspace_misses` 比率觀察；P95 延遲應低於非熱 Key 模式（因快取命中率更高）。

- **情境 D：Setup 資料完整性**：
  - 前置條件：k6 setup 函式執行完畢。
  - 期望：全域壓測回傳的 `shortUrlList` 長度為 50，熱 Key 壓測長度為 1，每筆元素均含非空 `shortKey` 與 `originalUrl`；任一項失敗則中止壓測，避免以不完整資料污染結果。

## 8. 預期效益 (Expected Benefits)

- **行為邊界量化**：取得 200 VU 下 P95/P99 延遲基準，作為後續效能優化的對照基準線。
- **功能防護升級**：高負載下自動驗證 Location Header 正確性，防止快取污染或競態條件造成的靜默錯誤跳轉。
- **可重複性**：腳本一鍵執行，結果可整合至 CI 管線，對每次部署自動執行冒煙壓測。

