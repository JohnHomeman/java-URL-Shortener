# 執行計畫: STEP5-Access_Metrics 轉址存取事件日誌、非同步指標累加與統計查詢

> <!-- [IMPORTANT] 本文件為施工地圖 (The How)。定義施工步驟與驗證動作。嚴禁重複定義邏輯或資料結構，一律以「Spec §N.N」格式引用。 -->
> <!-- ⚠️ 狀態管理：產出者應保持所有 Checkbox 為 [ ]。勾選動作 [x] 僅保留給「最終實作執行者」。 -->
> <!-- 職責邊界：Plan 只回答「改哪些檔案、按什麼順序做、怎麼跑驗證」。 -->
> <!-- ❌ 禁止出現在 Plan 中：新的期望數字、SQL Schema 、API Response 結構、業務計算公式。這些是 Spec 的內容，此處只能引用。 -->

## 1. 實作階段 (Implementation Stages)

### Stage 1: 資料庫遷移與資料實體 (Database Migration & Entities)
- [x] [NEW] `src/main/resources/db/migration/V2__add_click_count_and_create_access_logs_table.sql`: 引用 Spec §4.1 執行 DDL 新增 `short_urls.click_count` 欄位與建立 `access_logs` 表。
- [x] [MODIFY] `src/main/java/com/urlshortener/entity/ShortUrl.java`: 引用 Spec §4.1 擴充 `clickCount` (Long) 欄位與對應 Getter/Setter。
- [x] [NEW] `src/main/java/com/urlshortener/entity/AccessLog.java`: 引用 Spec §4.1 建立存取明細日誌實體類別（包含 `id`, `shortKey`, `ip`, `userAgent`, `referer`, `accessedAt`）。
- [x] [NEW] `src/main/java/com/urlshortener/dto/AccessEvent.java`: 引用 Spec §3.1 建立內部存取事件資料載體。
- [x] [NEW] `src/main/java/com/urlshortener/dto/UrlMetricsResponseDto.java`: 引用 Spec §5.1 建立統計指標查詢回應 DTO。
- [x] [NEW] `src/main/java/com/urlshortener/dto/AccessLogPageResponseDto.java`: 引用 Spec §5.2 建立存取日誌分頁查詢回應 DTO。
- [x] [MODIFY] `src/main/java/com/urlshortener/common/exception/ErrorCode.java`: 引用 Spec §2、§5.2 擴充 `40003 INVALID_TIME_RANGE` 與 `40004 INVALID_PAGINATION_PARAMS` 錯誤代碼。
- **✅ Checkpoint**: 執行 `./mvnw compile` 編譯成功，Flyway Migration 順利執行。

### Stage 2: 倉儲與 Redis 輔助組件 (Repository & Redis Helper)
- [x] [MODIFY] `src/main/java/com/urlshortener/repository/ShortUrlRepository.java`: 引用 Spec §2、§3.2 擴充短網址累積點擊次數之批次更新方法（`updateClickCount`）。
- [x] [NEW] `src/main/java/com/urlshortener/repository/AccessLogRepository.java`: 引用 Spec §2、§3.2、§5.2 實作存取日誌之 JDBC `batchInsert` 與分頁條件查詢方法。
- [x] [NEW] `src/main/java/com/urlshortener/component/MetricsRedisHelper.java`: 引用 Spec §3.1、§3.2、§4.2 實作 Redis 點擊計數累加（`HINCRBY`）、日誌佇列推拉（Pipeline `LPUSH` / 批次 `RPOP`）與暫存鍵原子更名（`RENAME`）封裝。
- [x] [NEW] `src/test/java/com/urlshortener/component/MetricsRedisHelperTest.java`: 撰寫單元測試驗證 Spec §4.2 定義之 Key 格式與原子操作邏輯。
- **✅ Checkpoint**: Repository 與 Redis Helper 編譯通過，單元測試通過。

### Stage 3: 本機緩衝與優雅關機服務 (In-Memory Buffer & Graceful Shutdown)
- [x] [NEW] `src/main/java/com/urlshortener/service/MetricsBufferService.java`: 引用 Spec §3.1、§3.3 實作內部有界阻塞佇列（`BlockingQueue<AccessEvent>`）、非同步批次推入 Redis Worker 執行緒，以及優雅關機排空（Drain）方法。
- [x] [NEW] `src/main/java/com/urlshortener/component/GracefulShutdownHandler.java`: 引用 Spec §3.3 監聽 Spring `ContextClosedEvent` / `@PreDestroy`，觸發 `MetricsBufferService` 執行記憶體排空與同步寫入 Redis。
- [x] [NEW] `src/test/java/com/urlshortener/service/MetricsBufferServiceTest.java`: 撰寫單元測試驗證 Spec §7.1 測項（非同步推入、批次寫入 Redis、優雅關機排空）。
- **✅ Checkpoint**: 執行單元測試通過，驗證關機時佇列完全排空且資料全數寫入 Redis。

### Stage 4: 排程持久化與指標業務層 (Persistence Scheduler & Metrics Service)
- [x] [NEW] `src/main/java/com/urlshortener/scheduler/MetricsPersistenceScheduler.java`: 引用 Spec §3.2 配置 `@Scheduled(fixedDelay = 10000)` 定時任務，自 Redis 原子提取點擊增量與日誌批次回寫至 MySQL。
- [x] [NEW] `src/main/java/com/urlshortener/service/AccessMetricsService.java`: 引用 Spec §3.4、§5.1、§5.2 實作即時指標聚合演算法（DB 基礎值 + Redis 即時增量值）與存取日誌分頁查詢業務。
- [x] [NEW] `src/test/java/com/urlshortener/service/AccessMetricsServiceTest.java`: 撰寫單元測試驗證 Spec §7.1 定義之指標聚合、分頁計算與錯誤代碼檢驗。
- **✅ Checkpoint**: 業務邏輯通過所有 Spec §7.1 的 Table-Driven 單元測試。

### Stage 5: 控制器與轉址事件串接 (Controller & Event Ingestion)
- [x] [MODIFY] `src/main/java/com/urlshortener/controller/UrlRedirectController.java`: 引用 Spec §2、§3.1 於轉址成功（HTTP 302）時，非同步建構 `AccessEvent` 並推入 `MetricsBufferService`。
- [x] [NEW] `src/main/java/com/urlshortener/controller/UrlMetricsController.java`: 引用 Spec §5.1、§5.2 實作 `GET /api/v1/urls/{shortKey}/metrics` 與 `GET /api/v1/urls/{shortKey}/logs` 端點。
- [x] [NEW] `src/test/java/com/urlshortener/controller/UrlMetricsControllerIntegrationTest.java`: 撰寫 MockMvc 整合測試，驗證 Spec §5.1、§5.2 定義之所有 API 合約與異常回應結構。
- **✅ Checkpoint**: 執行 `./mvnw clean test` 全案通過。

---

## 2. 風險與注意事項 (Risks & Notes)
- [Stage 1 相關] MySQL 5.7/8.0 在大量日誌寫入時，`access_logs` 表應建立 `(short_key, accessed_at)` 複合索引（引用 Spec §4.1），以支援高效率分頁查詢並避免全表掃描。
- [Stage 3 相關] `MetricsBufferService` 的有界佇列（`BlockingQueue`）需設定合理上限（例如 50,000 筆），避免極端流量下 JVM 記憶體溢出（OOM）；若佇列滿載應採取非阻塞降級處理，絕不阻塞轉址主線程。
- [Stage 3 相關] 優雅關機排空時需設定逾時上限（5 秒，引用 Spec §3.3），若 Redis 發生異常連線逾時，超時後應強制關閉，避免 JVM 進程無限掛起無法正常終止。
- [Stage 4 相關] 定時排程批次回寫時，必須採用暫存鍵原子重命名（`RENAME`，引用 Spec §3.2），確保排程回寫 MySQL 期間新進點擊計數 0 遺失。

---

## 3. 驗證動作 (Verification Actions)

### 3.1 自動化測試指令
> 測試數據來源：引用 Spec §7.1 與 §7.2
```bash
./mvnw clean test
```

### 3.2 手動與腳本驗收步驟 (Automation Scripts & cURL)
> 驗收情境來源：引用 Spec §7.2。
> 腳本存放：各情境之自動化驗收腳本存放於 `ai_context/STEP5-Access_Metrics/workspace/`。

#### 情境 A 驗收：轉址觸發點擊與即時指標查詢
- 執行驗收腳本：
  ```bash
  python3 ai_context/STEP5-Access_Metrics/workspace/test_01_metrics_query.py
  ```
- 或透過手動 cURL 驗證：
  1. 呼叫 `POST /api/v1/urls/shorten` 建立測試短碼 `mdn-302`。
  2. 連續執行 5 次 `curl -i -X GET http://localhost:8080/mdn-302`。
  3. 執行 `curl -i -X GET http://localhost:8080/api/v1/urls/mdn-302/metrics`，檢查回傳結構與數值，期望值引用 Spec §7.2 情境 A。

#### 情境 B 驗收：優雅關機資料不遺失驗收
- 執行專屬自動化關機驗收腳本：
  ```bash
  python3 ai_context/STEP5-Access_Metrics/workspace/test_02_graceful_shutdown.py
  ```
- 腳本執行動作：
  1. 併發發送 20 次轉址請求至應用程式。
  2. 毫秒內對應用程式進程發送 `SIGTERM` 關機訊號。
  3. 自動連線 Redis 檢驗 `short_url:metrics:clicks` 與 `short_url:metrics:access_queue`，期望值引用 Spec §7.2 情境 B。

#### 情境 C 驗收：排程批次回寫持久化驗收
- 執行排程回寫驗收腳本：
  ```bash
  python3 ai_context/STEP5-Access_Metrics/workspace/test_03_batch_persistence.py
  ```
- 腳本執行動作：
  1. 向 Redis 寫入 100 筆點擊增量與 100 筆存取日誌。
  2. 等待排程週期（10 秒）觸發完成。
  3. 連線 MySQL 查詢 `short_urls` 與 `access_logs` 表，檢查資料筆數與清空狀態，期望值引用 Spec §7.2 情境 C。

#### 情境 D 驗收：存取日誌分頁與時間篩選查詢
- 執行分頁查詢驗收腳本：
  ```bash
  python3 ai_context/STEP5-Access_Metrics/workspace/test_04_access_logs_pagination.py
  ```
- 或透過手動 cURL 驗證：
  1. 執行 `curl -i -X GET "http://localhost:8080/api/v1/urls/mdn-302/logs?page=1&size=5&start_time=2026-09-01T00:00:00&end_time=2026-09-09T23:59:59"`。
  2. 檢查回傳之 `items`, `total_elements`, `total_pages`，期望值引用 Spec §7.2 情境 D。
