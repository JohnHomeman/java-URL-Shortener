# STEP4-URL_Redirect_Cache: 短網址轉址重定向與 Redis 快取層實作

> <!-- [IMPORTANT] 本文件為唯一真相 (The What)。建議遵循：背景 -> 邏輯 -> 資料 -> 合約 的順序撰寫。 -->
> <!-- 職責邊界：Spec 只回答「系統做什麼、資料長什麼樣、期望結果是什麼」。-->
> <!-- ❌ 禁止出現在 Spec 中：檔案路徑、mvn/gradle 指令、cURL 命令、Migration 步驟。這些歸 Plan。 -->

## 1. 需求背景 (Background)
短網址服務屬於典型的「讀極多、寫極少（Read-Heavy）」系統架構，轉址查詢請求量通常為生成請求量的 10 至 100 倍以上。若每次轉址請求皆直接穿透至關聯式資料庫（MySQL）進行磁碟 I/O 查詢，將在高併發情境下迅速耗盡資料庫連線池並造成嚴重的效能瓶頸與轉址延遲。

為了實現毫秒級（Sub-millisecond）極致轉址回應並保護底層資料庫，本階段將建置基於 Redis 的高性能快取層（Cache-Aside Pattern），實作短碼轉址重定向（HTTP 302 Found）、快取穿透防禦（Negative Caching 空值快取）、快取雪崩與擊穿防護（TTL 隨機抖動與動態過期時間映射），並完整涵蓋短網址狀態校驗（過期判定、停用判定）。

## 2. 系統設計與影響範圍 (System Design)
- **核心目標**：實作高效能短網址轉址重定向機制，整合 Redis 快取層，提供 HTTP 302 轉址、快取命中/未命中雙層查詢、快取穿透防禦、過期與狀態校驗。
- **影響分層**：

| 分層 | 受影響模組（職責描述） | 變更類型 |
| :--- | :--- | :--- |
| Controller | UrlRedirectController — 接收短碼轉址請求 (`GET /{shortKey}`)，依轉址結果回傳 HTTP 302 重定向或錯誤回應 | 新增 |
| Service | UrlRedirectService — 負責轉址業務編排（快取優先查詢、DB 回源載入、狀態與有效期限判定、快取動態回填） | 新增 |
| Component | RedisCacheHelper — 封裝 Redis 快取存取操作、Key 命名空間管理、空值快取標記與 TTL 計算 | 新增 |
| Config | RedisConfig — 配置 Redis 連線屬性、Lettuce 連線池與 RedisTemplate 序列化協定 | 新增 |
| Common/Exception | ErrorCode / GlobalExceptionHandler — 擴充轉址相關錯誤代碼（40401 短碼不存在、41001 短碼已過期、40301 短碼已停用）與異常處理 | 修改 |

## 3. 核心業務邏輯 (Core Logic)

### 3.1 轉址流程與狀態流轉 (Redirection & Cache-Aside Flow)
1. **接收請求**：使用者發起 `GET /{shortKey}` 請求。
2. **第一層：查詢 Redis 快取**：
   - 以 `short_url:key:{shortKey}` 作為 Key 查詢 Redis 快取。
   - **Case 1.1（快取命中正常資料）**：取得快取資料（原始長網址與過期時間）。
     - 若未過期且狀態正常，直接發出 `HTTP 302 Found` 轉址至 `original_url`（無需訪問 DB）。
   - **Case 1.2（快取命中空值標記 `__NULL__`）**：觸發快取穿透防禦，判定該短碼確定不存在，直接拋出 `40401 SHORT_URL_NOT_FOUND` 異常（阻斷對 DB 的惡意穿透）。
   - **Case 1.3（快取未命中 Cache Miss）**：進入第二層 DB 回源查詢。
3. **第二層：DB 回源查詢與狀態校驗**：
   - 查詢資料庫 `short_urls` 表中 `short_key = {shortKey}` 之紀錄。
   - **Case 2.1（DB 查無紀錄）**：
     - 將該 Key 寫入 Redis 空值快取（Value 為 `__NULL__`，設定短 TTL = 60 秒），防止後續重複請求穿透。
     - 拋出 `40401 SHORT_URL_NOT_FOUND` 異常。
   - **Case 2.2（DB 存在紀錄，但狀態為停用 `status = 0`）**：
     - 拋出 `40301 SHORT_URL_DISABLED` 業務異常，拒絕轉址。
   - **Case 2.3（DB 存在紀錄，但已逾期 `expired_at <= now`）**：
     - 拋出 `41001 SHORT_URL_EXPIRED` 業務異常，拒絕轉址。
   - **Case 2.4（DB 存在紀錄且正常有效 `status = 1` 且未過期）**：
     - 計算剩餘存活 TTL，將完整資料回填至 Redis 快取。
     - 發出 `HTTP 302 Found` 轉址至 `original_url`。

```
[Client Request: GET /{shortKey}]
             │
             ▼
    [Check Redis Cache]
      ├── Hit "__NULL__" ───────────► [40401 Not Found (Fast Fail)]
      ├── Hit Valid URL ────────────► [HTTP 302 Redirect]
      └── Miss ────────────────┐
                               ▼
                      [Query MySQL DB]
                        ├── Not Found ──────► [Set Redis "__NULL__" TTL 60s] ──► [40401 Not Found]
                        ├── Status = 0 ─────► [40301 Forbidden]
                        ├── Expired ────────► [41001 Gone/Expired]
                        └── Valid ──────────► [Write Redis with TTL] ─────────► [HTTP 302 Redirect]
```

### 3.2 快取防護演算法與過期時間計算 (Cache Protection & TTL Mapping)

#### 1. 快取穿透防禦 (Cache Penetration Defense - Negative Caching)
- 當查詢 DB 確認短碼不存在時，寫入特殊的空值標記（`__NULL__`）。
- **空值 TTL**：固定設定為短暫存活時間 $TTL_{null} = 60 \text{ 秒}$。
- 當後續遭遇大量掃描不存在的隨機短碼時，所有重複請求均在 Redis 層被直接攔截阻斷。

#### 2. 快取雪崩防護 (Cache Avalanche Defense - Random Jitter)
- 為避免大量短網址在同一時間集體過期導致瞬間湧入 DB，快取有效時間加入隨機抖動（Random Jitter）：
  $$TTL_{actual} = TTL_{base} + \text{Random}(0, 300) \text{ 秒}$$
- 對於**永久有效**之短網址（DB `expired_at == null`）：
  - $TTL_{base} = 86400 \text{ 秒 (24 小時)}$。
  - $TTL_{actual} = 86400 + \text{Random}(0, 300) \text{ 秒}$。

#### 3. 快取擊穿與動態過期對齊 (Cache Breakdown & Dynamic Expiration)
- 對於**具備過期時間**之短網址（DB `expired_at != null`）：
  - 計算剩餘存活秒數：$\text{remaining\_seconds} = \text{expired\_at} - \text{now}$。
  - 若 $\text{remaining\_seconds} \le 0$，視為已過期，不予快取。
  - 若 $\text{remaining\_seconds} > 0$，快取 TTL 取最小值：
    $$TTL_{actual} = \min(\text{remaining\_seconds}, 86400 + \text{Random}(0, 300))$$
  - 保證 Redis 快取絕不會在短網址過期後仍殘留提供轉址，確保資料一致性。

## 4. 資料架構 (Data Schema)

### 4.1 Redis 資料結構與命名空間規範 (Redis Key Schema)

| 鍵名格式 (Key Format) | 資料類型 (Type) | 數值內容 (Value Schema) | 存活時間 (TTL) | 用途與說明 |
| :--- | :--- | :--- | :--- | :--- |
| `short_url:key:{shortKey}` | String (JSON) | 完整短網址快取結構體 (見下述 Schema) | $\min(\text{remaining}, 86400) + \text{Jitter}$ | 儲存有效短網址映射與狀態快取 |
| `short_url:key:{shortKey}` | String | `"__NULL__"` | 60 秒 | 快取穿透防禦空值標記 |

#### 快取 Value JSON 資料結構 (Cache Object Payload)
```json
{
  "original_url": "https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/302",
  "status": 1,
  "expired_at": 1757237400000
}
```

- `original_url` (String)：目標原始長網址。
- `status` (Integer)：狀態碼（`1`: 啟用, `0`: 停用, `2`: 已過期）。
- `expired_at` (Long / Null)：過期時間戳（毫秒，Null 表示永久有效）。

### 4.2 資料庫關聯與索引配合
本功能重用 `STEP3-URL_Shorten_Core` 所建置之 `short_urls` 表：
- 核心查詢依賴唯一索引 `uk_short_key (`short_key`)`，確保 DB 回源查詢具備 $O(1)$ 單筆常數時間檢索效能。

## 5. 介面合約 (API Contract)

### 5.1 REST API — 短碼重定向轉址 (Redirect Short URL)
- **Endpoint**: `GET /{shortKey}`
- **Request Parameters**:
  - `shortKey` (Path Variable, String, 必填): 短網址短碼或自訂別名（例如 `mdn-302`, `4gfFC3`）。

- **Success Response (302 Found)**:
  - **HTTP Status**: `302 Found`
  - **Response Headers**:
    - `Location: https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/302`
    - `Cache-Control: no-cache, no-store, max-age=0, must-revalidate`
    - `Pragma: no-cache`
  - **Response Body**: 空（無 Body）

- **Error Responses**:

| HTTP Code | Error Code (`code`) | 條件 | 回傳 Message | 回傳 Data |
| :--- | :--- | :--- | :--- | :--- |
| 404 | 40401 | 短碼不存在（快取空值或 DB 查無紀錄） | `Short URL not found: [shortKey]` | `null` |
| 410 | 41001 | 短碼已超過有效期限 (Expired) | `Short URL has expired` | `null` |
| 403 | 40301 | 短碼已被管理員或系統停用 (Disabled) | `Short URL is disabled` | `null` |
| 500 | 50000 | Redis 連線失敗降級或系統內部未預期異常 | `Internal server error` | `null` |

- **404 Not Found 錯誤回應範例 (40401)**:
```json
{
  "code": 40401,
  "message": "Short URL not found: notExist123",
  "data": null,
  "timestamp": 1757151000000
}
```

- **410 Gone 錯誤回應範例 (41001)**:
```json
{
  "code": 41001,
  "message": "Short URL has expired",
  "data": null,
  "timestamp": 1757151000000
}
```

- **403 Forbidden 錯誤回應範例 (40301)**:
```json
{
  "code": 40301,
  "message": "Short URL is disabled",
  "data": null,
  "timestamp": 1757151000000
}
```

## 6. 技術決策與權衡 (Design Decisions)

### 6.1 重定向狀態碼選型 (HTTP 302 Found vs. HTTP 301 Moved Permanently)
- **選項 A**：HTTP 301 Moved Permanently（永久重定向）
- **選項 B**：HTTP 302 Found（臨時重定向）
- **決策**：選擇 B (HTTP 302)。
  - **原因 1（點擊統計與數據分析）**：HTTP 301 會被瀏覽器進行極其激進的本機快取（後續點擊不再發送請求至伺服器），將導致無法記錄轉址點擊次數與分析使用者行為（影響 STEP5 Access Metrics）。
  - **原因 2（動態生命週期管理）**：短網址支援自訂有效期限（TTL）與隨時停用功能。使用 HTTP 302 能確保每次點擊皆經過服務端驗證，即時判定過期與停用狀態。
- **風險**：相較於 301 瀏覽器端直接跳轉，302 每次跳轉需訪問伺服器端；透過 Redis 高性能快取層（回應延遲 $< 2\text{ms}$）可完美抵消該開銷。

### 6.2 快取模式選型 (Cache-Aside vs. Read/Write-Through)
- **選項 A**：Cache-Aside 模式（旁路快取）
- **選項 B**：Write-Through 模式（雙寫快取）
- **決策**：選擇 A (Cache-Aside)。
  - 讀取時優先查詢快取，快取未命中再回源讀取資料庫並非同步/同步回填快取。
  - 寫入時（如生成短網址或後續更新狀態）主要操作資料庫，可選同步更新/刪除快取。
  - 架構鬆耦合、容錯性高，即使 Redis 出現短暫故障亦可自動降級回源資料庫。
- **風險**：可能存在短暫的快取與資料庫不一致；透過合理的 TTL 與更新時主動驅逐/回填快取機制降低風險。

### 6.3 快取穿透防禦策略 (Negative Caching vs. Bloom Filter)
- **選項 A**：空值快取 (Negative Caching / Null Object Caching)
- **選項 B**：布隆過濾器 (Bloom Filter / Guava BloomFilter / Redis Bloom)
- **決策**：選擇 A (空值快取，TTL = 60s)。
  - **優點**：實作輕量、維護成本低，能直接針對不存在的惡意 Key 或過期短碼設定 60 秒短暫空值標記，立即阻斷連續重複穿透攻擊。
  - **評估**：當前系統處於初期建置階段，空值快取已足夠應對常態防禦；後續若面臨極端海量隨機 Key 穿透攻擊，可於後續規劃引入分散式布隆過濾器。

### 6.4 Redis 連線客戶端與本機環境配置規格 (Redis Configuration)
- **連線庫選型**：採用 Spring Data Redis 原生整合之 **Lettuce** 客戶端（基於 Netty 的非阻塞非同步高效能 I/O）。
- **序列化協定**：
  - Key 序列化器：`StringRedisSerializer`（確保 Key 可讀且無二進位前綴）。
  - Value 序列化器：`GenericJackson2JsonRedisSerializer` 或 Jackson JSON 序列化器（支援物件與標記結構化序列化）。
- **本機環境連線規格**：
  - 主機 (Host)：`localhost`
  - 連接埠 (Port)：`6379`
  - 帳號 (Username)：`default`
  - 密碼 (Password)：`redis123456`
  - 資料庫索引 (Database)：`0`
  - 連線池配置：最大活躍連線數 16，最大空閒連線數 8，最小空閒連線數 2。

### 6.5 快取降級與高可用容錯策略 (Cache Fallback & Graceful Degradation)
- 若 Redis 伺服器發生網路逾時、連線中斷或宕機異常：
  - 系統應具備容錯捕捉機制（Catch RedisException），記錄告警日誌後**自動安全降級回源至 MySQL 資料庫查詢**，確保核心轉址業務不中斷。
- **風險**：Redis 宕機期間資料庫負載將上升；已保證核心服務可用性。

## 7. 驗證目標與測試數據 (Verification Goals & Test Data)

### 7.1 單元測試測項 (Table-Driven)

| Case | 輸入 (Input) | 條件 (Condition) | 期望輸出 (Expected) |
| :--- | :--- | :--- | :--- |
| 快取命中正常短碼 | `shortKey: "mdn-302"` | Redis 快取存在有效紀錄，`status: 1` 且未過期 | 直接回傳原始網址，不呼叫 DB Repository |
| 快取命中穿透空值 | `shortKey: "fake999"` | Redis 快取值為 `"__NULL__"` | 拋出 `40401 SHORT_URL_NOT_FOUND`，不呼叫 DB |
| 快取未命中但 DB 存在 | `shortKey: "git-hub"` | Redis Miss, DB 查詢成功且未過期 | 寫入 Redis 快取，回傳原始網址 |
| 快取未命中且 DB 不存在 | `shortKey: "not-exist"` | Redis Miss, DB 查詢回傳 Empty | 寫入 Redis `"__NULL__"` (TTL 60s)，拋出 `40401` |
| 短網址已停用 | `shortKey: "disabled-key"` | DB 存在但 `status: 0` | 拋出 `40301 SHORT_URL_DISABLED` |
| 短網址已過期 | `shortKey: "expired-key"` | DB 存在但 `expired_at < now` | 拋出 `41001 SHORT_URL_EXPIRED` |
| Redis 連線異常容錯降級 | `shortKey: "mdn-302"` | Redis 丟出連線異常 | 自動降級查詢 DB，正常回傳原始網址 |

### 7.2 業務驗收情境 (Acceptance Criteria)
- **情境 A：首次轉址（Cache Miss -> DB Hit -> 快取回填 -> HTTP 302）**：
  - 前置條件：資料庫存在有效短網址紀錄（`short_key = "abc123"`, `original_url = "https://example.com"`, `status = 1`），Redis 無該快取。
  - 期望：發送 `GET /abc123` 回傳 HTTP 狀態碼 `302 Found`，Response Header 包含 `Location: https://example.com`；同時 Redis 成功建立 `short_url:key:abc123` 快取鍵，且 TTL 大於 0。
- **情境 B：第二次轉址（Cache Hit -> HTTP 302，無 DB 存取）**：
  - 前置條件：情境 A 已執行完畢，Redis 快取已就緒。
  - 期望：再次發送 `GET /abc123` 回傳 HTTP 狀態碼 `302 Found`，且資料庫無任何額外查詢行為。
- **情境 C：不存在短碼防穿透（DB Miss -> 寫入空值快取 -> 404）**：
  - 前置條件：資料庫與 Redis 均無 `short_key = "non-existent"` 之紀錄。
  - 期望：發送 `GET /non-existent` 回傳 HTTP 狀態碼 `404 Not Found`，Error Code 為 `40401`；Redis 產生 Key `short_url:key:non-existent`，其值為 `"__NULL__"` 且 TTL 約為 60 秒。
- **情境 D：連續請求不存在短碼（Cache Hit Null -> 404 快速失敗）**：
  - 前置條件：情境 C 已執行完畢，空值快取存在。
  - 期望：再次發送 `GET /non-existent` 立即回傳 HTTP `404 Not Found` (40401)，且不觸發資料庫查詢。
- **情境 E：過期短網址拒絕轉址（410 Gone）**：
  - 前置條件：資料庫存在 `expired_at` 已早於當前時間的短網址紀錄。
  - 期望：發送 `GET /{shortKey}` 回傳 HTTP 狀態碼 `410 Gone`，Error Code 為 `41001`，回傳訊息為 `"Short URL has expired"`。
- **情境 F：停用短網址拒絕轉址（403 Forbidden）**：
  - 前置條件：資料庫存在 `status = 0` 的短網址紀錄。
  - 期望：發送 `GET /{shortKey}` 回傳 HTTP 狀態碼 `403 Forbidden`，Error Code 為 `40301`，回傳訊息為 `"Short URL is disabled"`。

## 8. 預期效益 (Expected Benefits)
- **極致轉址低延遲**：快取命中情況下，轉址查詢回應時間自磁碟 DB 查詢的 10~30ms 降低至記憶體快取的 0.5~2ms，大幅提升終端使用者轉址跳轉體驗。
- **資料庫保護與高吞吐**：透過 Cache-Aside 與空值快取穿透防禦，分流 90% 以上的重複讀取流量與惡意掃描流量，有效防止資料庫連線池耗盡。
- **彈性高可用**：具備 Redis 宕機時的優雅自動降級機制，確保系統在快取中斷時核心業務依然具備可用性。

## 9. Backlog / Future Scope（下期規劃）
- **[STEP5-Access_Metrics]**：實作轉址存取事件日誌、非同步點擊次數累加（Redis INCRBY / Kafka 事件訊息）與統計指標 API（下期）。
- **[STEP6-Stress_Testing_Benchmark]**：實作高併發壓力測試（使用 k6 / JMeter）、JVM 與連線池極限調優，並產出 QPS / P99 延遲效能分析報告。

