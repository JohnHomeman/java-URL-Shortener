# STEP5-Access_Metrics: 轉址存取事件日誌、非同步指標累加與統計查詢

> <!-- [IMPORTANT] 本文件為唯一真相 (The What)。建議遵循：背景 -> 邏輯 -> 資料 -> 合約 的順序撰寫。 -->
> <!-- 職責邊界：Spec 只回答「系統做什麼、資料長什麼樣、期望結果是什麼」。-->
> <!-- ❌ 禁止出現在 Spec 中：檔案路徑、mvn/gradle 指令、cURL 命令、Migration 步驟。這些歸 Plan。 -->

## 1. 需求背景 (Background)
現行短網址系統已具備核心短碼生成與快取轉址能力，但尚未記錄使用者點擊與存取明細數據。為滿足行銷成效追蹤與流量分析需求，本次異動為轉址流程加入存取數據收集機制，提供點擊次數與存取明細查詢功能。同時建立服務終止時的資料保護機制，確保系統重啟或停機時尚未持久化的存取數據完整保留不遺失。

## 2. 系統設計與影響範圍 (System Design)
- **核心目標**：收集轉址點擊事件與訪客日誌，提供非同步快取累加、排程批次持久化、優雅關機資料防遺失保護，以及統計指標與存取日誌查詢介面。
- **影響分層**：

| 分層 | 受影響模組（職責描述） | 變更類型 |
| :--- | :--- | :--- |
| Controller | UrlMetricsController — 提供短網址點擊指標查詢與存取日誌分頁查詢端點 | 新增 |
| Controller | UrlRedirectController — 轉址成功時觸發存取事件發布 | 修改 |
| Service | AccessMetricsService — 處理指標即時聚合計算（資料庫基礎值 + 快取即時增量）與歷史日誌分頁檢索 | 新增 |
| Service | MetricsBufferService — 維護本機記憶體緩衝佇列，協調非同步批次寫入快取與關機排空流程 | 新增 |
| Component | MetricsRedisHelper — 封裝 Redis 點擊計數累加、日誌佇列推拉與原子轉移操作 | 新增 |
| Component | GracefulShutdownHandler — 監聽應用程式關機事件，觸發記憶體緩衝區排空並同步寫入 Redis | 新增 |
| Scheduler | MetricsPersistenceScheduler — 定時排程提取 Redis 點擊增量與日誌，批次回寫資料庫 | 新增 |
| Repository | AccessLogRepository — 提供存取日誌批次寫入與分頁條件查詢功能 | 新增 |
| Repository | ShortUrlRepository — 擴充短網址累積點擊次數之批次更新與查詢 | 修改 |
| Common/Exception | ErrorCode — 擴充時間區間無效與分頁參數無效錯誤代碼 | 修改 |

## 3. 核心業務邏輯 (Core Logic)

### 3.1 轉址存取事件非同步收集架構 (Event Ingestion & In-Memory Flow)
1. **事件觸發**：轉址成功發出 `HTTP 302 Found` 時，建構包含 `shortKey`, `ip`, `userAgent`, `referer`, `accessedAt` 的存取事件。
2. **本機緩衝**：主線程以非阻塞方式將事件放入記憶體有界佇列（`BlockingQueue`），立即回傳轉址結果（主線程耗時 $< 0.1\text{ms}$）。
3. **批次寫入快取**：背景 Worker 達到批次量（100 筆）或時間窗口（200ms）時，以 Pipeline 批量寫入 Redis：
   - **點擊計數**：`HINCRBY short_url:metrics:clicks {shortKey} {count}`
   - **日誌佇列**：`LPUSH short_url:metrics:access_queue {logJson...}`

```
[HTTP 302 Redirect]
        │ (Non-blocking Offer)
        ▼
[In-Memory Buffer] ──(Batch 100 / 200ms)──► [Redis Pipeline: HINCRBY & LPUSH]
```

### 3.2 後台定時排程批次回寫機制 (Scheduled Batch Persistence)
1. **觸發週期**：每 10 秒執行一次持久化任務。
2. **原子計數提取與更新**：
   - 使用原子重命名將 `short_url:metrics:clicks` 轉移至暫存鍵 `short_url:metrics:clicks:syncing`，確保提取期間新點擊累加不丟失。
   - 提取所有短碼增量，批次更新 MySQL：
     $$\text{UPDATE short\_urls SET click\_count = click\_count + :delta WHERE short\_key = :shortKey}$$
   - 更新完成後刪除暫存鍵。
3. **批次插入日誌**：
   - 自 Redis List `short_url:metrics:access_queue` 批次彈出日誌（每批最多 500 筆）。
   - 透過 JDBC `batchInsert` 批次寫入 MySQL `access_logs` 表，直至佇列為空或達單次上限。

### 3.3 優雅關機資料防遺失機制 (Graceful Shutdown & Zero-Loss Drain)
1. **停止接收**：標記停機狀態，拒絕新的存取事件推入記憶體佇列。
2. **排空記憶體（Drain）**：阻塞取出記憶體佇列中所有殘留事件。
3. **同步 Flush 至 Redis**：將取出的點擊數與日誌記錄同步寫入 Redis，設定 5 秒超時上限，確保 JVM 退出前本機記憶體 0 殘留。

### 3.4 統計指標即時聚合演算法 (Real-Time Metrics Aggregation)
查詢短網址點擊指標時，即時聚合持久化基礎值與快取中的即時增量：
$$\text{TotalClicks} = \text{MySQL}(\text{click\_count}) + \text{Redis}(\text{clicks}[shortKey]) + \text{Redis}(\text{clicks:syncing}[shortKey])$$

## 4. 資料架構 (Data Schema)

### 4.1 SQL DDL

```sql
-- 擴充短網址表點擊次數欄位
ALTER TABLE `short_urls`
  ADD COLUMN `click_count` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '累積點擊次數' AFTER `status`;

-- 建立短網址存取明細日誌表
CREATE TABLE `access_logs` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主鍵 ID',
  `short_key` VARCHAR(16) NOT NULL COMMENT '短網址短碼',
  `ip` VARCHAR(45) NOT NULL DEFAULT '' COMMENT '存取者 IP (支援 IPv4/IPv6)',
  `user_agent` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '瀏覽器/客戶端 User-Agent',
  `referer` VARCHAR(1024) NOT NULL DEFAULT '' COMMENT '來源網址 (Referer)',
  `accessed_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '存取時間',
  PRIMARY KEY (`id`),
  KEY `idx_short_key_accessed_at` (`short_key`, `accessed_at`), -- 複合索引：依短碼與時間範圍查詢與分頁
  KEY `idx_accessed_at` (`accessed_at`) -- 索引：歷史數據歸檔與定期清理
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='短網址存取明細日誌表';
```

### 4.2 Redis 資料結構規範 (Redis Key Schema)

| 鍵名格式 (Key Format) | 資料類型 (Type) | 數值內容 (Value Schema) | 存活時間 (TTL) | 用途與說明 |
| :--- | :--- | :--- | :--- | :--- |
| `short_url:metrics:clicks` | Hash | Field: `{shortKey}`<br>Value: 增量數值 (Integer) | 無過期 (排程提取) | 即時點擊增量計數器 |
| `short_url:metrics:clicks:syncing` | Hash | Field: `{shortKey}`<br>Value: 增量數值 (Integer) | 60 秒 (防殘留) | 排程回寫中之暫存增量鍵 |
| `short_url:metrics:access_queue` | List | 存取日誌 JSON 字串 (見下述範例) | 無過期 (排程彈出) | 存取日誌非同步寫入緩衝佇列 |

#### 存取日誌 Queue JSON 範例
```json
{
  "short_key": "mdn-302",
  "ip": "203.0.113.195",
  "user_agent": "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36",
  "referer": "https://google.com",
  "accessed_at": "2026-09-09T14:30:00.000"
}
```

## 5. 介面合約 (API Contract)

### 5.1 REST API — 查詢短網址統計指標 (Get Short URL Metrics)
- **Endpoint**: `GET /api/v1/urls/{shortKey}/metrics`
- **Request Parameters**:
  - `shortKey` (Path Variable, String, 必填): 短網址短碼。
- **Success Response (200)**:
```json
{
  "code": 0,
  "message": "success",
  "data": {
    "short_key": "mdn-302",
    "original_url": "https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/302",
    "total_clicks": 1582,
    "status": 1,
    "created_at": "2026-09-07T10:00:00",
    "expired_at": null,
    "last_accessed_at": "2026-09-09T14:30:00"
  },
  "timestamp": 1757428200000
}
```
- **Error Responses**:

| HTTP Code | Error Code | 條件 | 回傳 Message |
| :--- | :--- | :--- | :--- |
| 404 | 40401 | 短碼不存在 | Short URL not found: [shortKey] |
| 500 | 50000 | 系統內部異常 | Internal server error |

---

### 5.2 REST API — 分頁查詢短網址存取日誌 (Get Short URL Access Logs)
- **Endpoint**: `GET /api/v1/urls/{shortKey}/logs`
- **Request Parameters**:
  - `shortKey` (Path Variable, String, 必填): 短網址短碼。
  - `page` (Query Param, Integer, 選填, 預設: `1`): 頁碼（從 1 起算）。
  - `size` (Query Param, Integer, 選填, 預設: `20`, 最大: `100`): 每頁筆數。
  - `start_time` (Query Param, String, 選填, ISO-8601 格式): 起始時間。
  - `end_time` (Query Param, String, 選填, ISO-8601 格式): 結束時間。
- **Success Response (200)**:
```json
{
  "code": 0,
  "message": "success",
  "data": {
    "items": [
      {
        "id": 89521,
        "short_key": "mdn-302",
        "ip": "203.0.113.195",
        "user_agent": "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36",
        "referer": "https://google.com",
        "accessed_at": "2026-09-09T14:30:00"
      },
      {
        "id": 89520,
        "short_key": "mdn-302",
        "ip": "198.51.100.42",
        "user_agent": "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X)",
        "referer": "https://twitter.com",
        "accessed_at": "2026-09-09T14:28:15"
      }
    ],
    "page": 1,
    "size": 20,
    "total_elements": 1580,
    "total_pages": 79
  },
  "timestamp": 1757428200000
}
```
- **Error Responses**:

| HTTP Code | Error Code | 條件 | 回傳 Message |
| :--- | :--- | :--- | :--- |
| 400 | 40003 | 時間區間無效（起始時間大於結束時間） | Invalid time range: start_time must be before end_time |
| 400 | 40004 | 分頁參數無效（`page < 1` 或 `size < 1` 或 `size > 100`） | Invalid pagination parameters |
| 404 | 40401 | 短碼不存在 | Short URL not found: [shortKey] |
| 500 | 50000 | 系統內部異常 | Internal server error |

## 6. 技術決策與權衡 (Design Decisions)

### 6.1 存取日誌與計數非同步寫入策略
- **選項 A**：Direct Redis（轉址主線程直接同步呼叫 Redis `HINCRBY` 與 `LPUSH`）。
- **選項 B**：In-Memory Buffer + Redis 批次 + MySQL 排程。
- **決策**：選擇 B。
  - **理由**：選項 A 主線程仍受 Redis 網路往返延遲影響（1~2ms）；選項 B 將主線程操作降至記憶體操作（$< 0.1\text{ms}$），並透過 100 筆批次聚合減少 99% 的 Redis 連線開銷。
- **風險**：伺服器異常斷電可能造成記憶體資料遺失；透過優雅關機機制覆蓋正常發布與重啟情境。

### 6.2 優雅關機排空機制
- **選項 A**：依賴預設容器銷毀流程。
- **選項 B**：自訂關機鉤子執行記憶體排空與同步 Flush 至 Redis。
- **決策**：選擇 B。
  - **理由**：在關機時優先關閉進入口，同步等待並將記憶體殘留資料寫入 Redis（設定 5 秒超時），達成零資料遺失。
- **風險**：若關機超時時間過短可能遺失末段資料；5 秒上限足夠消化常態萬筆佇列。

### 6.3 Redis 計數回寫資料庫一致性
- **選項 A**：先讀取 Redis 計數 -> 回寫資料庫 -> 刪除 Redis 計數。
- **選項 B**：原子重命名暫存鍵 -> 回寫資料庫 -> 刪除暫存鍵。
- **決策**：選擇 B。
  - **理由**：選項 A 在讀與刪之間發生的新點擊會被誤刪；選項 B 原子切換鍵名，新點擊寫入新鍵，排程僅處理暫存鍵，達成無鎖零遺失。
- **風險**：若資料庫回寫失敗暫存鍵會殘留，透過 60 秒 TTL 配合重試機制避免死鎖。

## 7. 驗證目標與測試數據 (Verification Goals & Test Data)

### 7.1 單元測試測項 (Table-Driven)

| Case | 輸入 (Input) | 條件 (Condition) | 期望輸出 (Expected) |
| :--- | :--- | :--- | :--- |
| 事件推入緩衝 | `AccessEvent(shortKey: "abc", ip: "1.1.1.1")` | 記憶體佇列未滿 | 推入成功回傳 `true`，主線程不阻塞 |
| 記憶體批次寫入 Redis | 佇列累積 100 筆事件 | 觸發 Worker 批次處理 | Redis `short_url:metrics:clicks` 累加 100，`access_queue` 長度增 100 |
| 優雅關機排空緩衝 | 記憶體佇列殘留 50 筆事件 | 觸發應用程式關機事件 | 50 筆事件全數寫入 Redis，記憶體佇列為空 |
| 排程批次回寫 MySQL | Redis 累積點擊數 250，日誌 500 筆 | 排程觸發執行 | MySQL `click_count` 增 250，`access_logs` 新增 500 筆，Redis 佇列清空 |
| 即時指標查詢（含增量） | `shortKey: "mdn-302"` | MySQL `click_count: 1000`, Redis 增量 `50` | 回傳 `total_clicks: 1050` |
| 指標查詢不存在短碼 | `shortKey: "not-exist"` | DB 與 Redis 皆無紀錄 | 拋出 `40401 SHORT_URL_NOT_FOUND` |
| 存取日誌分頁查詢 | `page: 1, size: 10` | DB 存有 25 筆存取紀錄 | 回傳前 10 筆明細，`total_elements: 25`, `total_pages: 3` |
| 存取日誌時間區間錯誤 | `start_time: "2026-09-10"`, `end_time: "2026-09-01"` | `start_time > end_time` | 拋出 `40003 INVALID_TIME_RANGE` |

### 7.2 業務驗收情境 (Acceptance Criteria)
- **情境 A：轉址觸發點擊與即時指標查詢**：
  - 前置條件：短網址 `mdn-302` 在 MySQL 中 `click_count = 0`，Redis 中無增量。
  - 期望：發送 5 次 `GET /mdn-302` 轉址請求；隨後發送 `GET /api/v1/urls/mdn-302/metrics` 查詢指標，回傳之 `total_clicks` 欄位值為 `5`。
- **情境 B：優雅關機資料不遺失驗收**：
  - 前置條件：發起 20 次轉址請求，事件暫存於本機記憶體佇列中。
  - 期望：觸發應用程式關閉訊號；檢驗 Redis `short_url:metrics:clicks` 與 `short_url:metrics:access_queue`，確認 20 筆點擊與 20 筆存取日誌完整寫入 Redis，記憶體佇列為 0。
- **情境 C：排程批次回寫持久化驗收**：
  - 前置條件：Redis 中累積有 100 筆點擊增量與 100 筆存取日誌。
  - 期望：排程器執行完畢後，MySQL `short_urls` 表之 `click_count` 增加 100，`access_logs` 表新增 100 筆紀錄，Redis 暫存鍵與佇列清空。
- **情境 D：存取日誌分頁與時間篩選查詢**：
  - 前置條件：MySQL `access_logs` 已存在歷史存取紀錄。
  - 期望：測試者可依實際存取時間彈性指定區間發送 `GET /api/v1/urls/{shortKey}/logs?page={page}&size={size}&start_time={startTime}&end_time={endTime}`，驗證回傳之 `items` 明細列表數量符合 `size` 設定、`total_elements` 與 `total_pages` 計算正確，並能正確過濾時間區間與攔截不合法參數（如 `start_time > end_time` 回傳 40003、無效分頁回傳 40004）。

## 8. 預期效益 (Expected Benefits)
- **轉址零 I/O 阻塞**：透過本機記憶體佇列非同步緩衝，主線程記錄開銷 $< 0.1\text{ms}$，徹底消除轉址時的磁碟與網路阻塞。
- **資料庫削峰填谷**：將高頻轉址寫入聚合為定時批次更新，降低資料庫寫入 I/O 負載達 95% 以上。
- **停機零資料遺失**：優雅關機機制保證重啟與發布時本機記憶體資料 100% 寫入 Redis。
- **即時指標呈現**：聚合資料庫與快取數據，提供毫秒級即時統計查詢。

## 9. Backlog / Future Scope（下期規劃）
- **[STEP6-k6_short_url_press]**：使用 k6 針對短網址轉址端點進行高併發壓力測試，驗證 302 回應正確性、Location Header 準確度，並量化 P95/P99 延遲與錯誤率。

