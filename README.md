# Java 高效能分散式短網址服務 (High-Performance Distributed URL Shortener)

基於 **Java 21（啟用 Virtual Threads 虛擬執行緒）** 與 **Spring Boot 3.3.5** 打造的高併發、低延遲分散式短網址系統。整合 **MurmurHash3 + Base62** 核心生成演算法、多重防碰撞安全降級機制，並搭配 **Redis Cache-Aside 高效快取層**（防穿透、防雪崩、防擊穿、故障降級）與 **非同步寫入緩衝統計架構**，提供毫秒級的 HTTP 302 重定向與即時分析服務。

經 **k6 壓測驗證**，在 200 VU 高併發下達成 **8,200+ req/s 吞吐量**、**P95 延遲 45.39ms**、**100% 轉址正確率** 以及 **192 萬筆點擊打點 0 遺失**之極致效能與資料完整性。

---

## 📑 目錄
- [技術架構與選型](#-技術架構與選型)
- [系統核心設計與演算法流程](#-系統核心設計與演算法流程)
  - [1. 短碼生成與防碰撞重試機制](#1-短碼生成與防碰撞重試機制)
  - [2. Redis Cache-Aside 轉址與四重快取防護](#2-redis-cache-aside-轉址與四重快取防護)
  - [3. 高併發非同步存取統計與資料防遺失架構](#3-高併發非同步存取統計與資料防遺失架構)
- [📊 k6 高併發壓力測試成果](#-k6-高併發壓力測試成果)
  - [全域隨機轉址壓測數據](#1-全域隨機轉址壓測-200-vu-持續-4-分鐘)
  - [熱 Key 極限併發壓測數據](#2-熱-key-極限併發壓測-200-vu-持續-4-分鐘)
  - [壓測方法論與關鍵發現](#3-壓測方法論與關鍵發現)
- [📡 API 介面規格與合約](#-api-介面規格與合約)
  - [1. 建立短網址 API](#1-建立短網址-api)
  - [2. 短碼重定向轉址 API](#2-短碼重定向轉址-api)
  - [3. 查詢短網址即時統計指標 API](#3-查詢短網址即時統計指標-api)
  - [4. 分頁查詢歷史存取日誌 API](#4-分頁查詢歷史存取日誌-api)
  - [5. 系統健康檢查 API](#5-系統健康檢查-api)
- [📁 專案架構與目錄結構](#-專案架構與目錄結構)
- [🚀 快速開始與本地運行](#-快速開始與本地運行)
  - [環境需求](#環境需求)
  - [設定檔說明](#設定檔說明-srcmainresourcesapplicationyml)
  - [建置與測試指令](#建置與測試指令)
  - [cURL 完整測試流程範例](#curl-完整測試流程範例)
- [🐳 Docker 與 Docker Compose 容器化](#-docker-與-docker-compose-容器化)
- [🔄 CI/CD 自動化流程 (GitHub Actions)](#-cicd-自動化流程-github-actions)
- [🗺 開發階段與里程碑 (Roadmap)](#-開發階段與里程碑-roadmap)

---

## 🛠 技術架構與選型

| 領域 | 技術組件 | 說明 |
| :--- | :--- | :--- |
| **開發語言** | **Java 21 (LTS)** | 啟用 Virtual Threads (虛擬執行緒)，顯著提升高併發 I/O 密集型吞吐量 |
| **核心框架** | **Spring Boot 3.3.5** | 現代化微服務框架、依賴注入、RESTful 控制器與優雅停機 (Graceful Shutdown) |
| **持久化層** | **Spring Data JPA + Hibernate** | 關聯式資料庫持久化、物件關聯映射與複合索引優化查詢 |
| **快取架構** | **Spring Data Redis (Lettuce)** | 記憶體快取層 (Cache-Aside Pattern)、自訂連線池、Jackson JSON 序列化與 Pipeline 計數 |
| **關聯式資料庫**| **MySQL 8.0 / H2 Database** | 生產環境使用 MySQL 8.0 (InnoDB)；單元/整合測試使用 H2 In-Memory 引擎 |
| **雜湊與編碼** | **MurmurHash3 (32-bit) + Base62** | Google Guava MurmurHash3 演算法結合 62 進位字典，產出 6 位輕量短碼 |
| **安全保底** | **Snowflake ID Generator** | 自製雪花演算法產生器，當 3 次加鹽雜湊皆碰撞時提供毫秒級唯一 ID 降級保底 |
| **資料庫遷移** | **Flyway Core 10.x** | 自動化版本控制資料庫 Schema (`V1__create_short_urls...`, `V2__add_click_count...`) |
| **高併發統計** | **BlockingQueue + Virtual Threads** | 非同步記憶體佇列緩衝、Redis Pipeline 原子累加、排程批次回寫 MySQL |
| **測試與驗證** | **JUnit 5 + Mockito + MockMvc** | 114 項自動化單元測試與全流程整合測試 (100% 通過) |
| **效能壓測** | **k6 (Grafana k6)** | 涵蓋全域隨機讀取、熱點 Key 擊穿、轉址正確性校驗與點擊數對帳 |
| **容器與 CI/CD**| **Docker + GitHub Actions** | Multi-stage 輕量映像檔構建、Docker Compose 一鍵啟動、自動化推送到 GHCR |

---

## 🧠 系統核心設計與演算法流程

### 1. 短碼生成與防碰撞重試機制

短碼生成採用 **MurmurHash3 (32-bit unsigned) + Base62 編碼**，長度僅 6 碼，兼顧高效分散性與 URL 友好性：

```
[原始長網址 original_url]
         │
         ▼
[MurmurHash3 (32-bit) 計算 Hash 值]
         │
         ▼
[Base62 編碼產出 6 位候選短碼] ───► 檢查 DB 唯一鍵 short_key
                                       │
      ┌────────────────────────────────┼────────────────────────────────┐
      ▼                                ▼                                ▼
[無衝突 / 首次建立]            [長網址相同且未過期]            [雜湊碰撞 (長網址不同)]
      │                                │                                │
寫入 DB，回傳短碼              重用既有短碼 (保證冪等性)        加鹽重試 url + "[SALT_i]" (最多 3 次)
                                                                        │
                                                                 [若 3 次加鹽仍碰撞]
                                                                        │
                                                                 觸發 Snowflake ID 降級保底
```

- **冪等性保障**：若相同的原始長網址已被縮短過且尚未過期，直接回傳既有短碼，避免空間浪費。
- **加鹽碰撞重試**：若發生雜湊碰撞（不同 URL 產生相同 Hash），系統自動加鹽重試最多 3 次。
- **雪花演算法降級**：若極端情況下連續 3 次加鹽依然碰撞，安全降級為 Snowflake 64-bit ID 並轉 Base62，保證 100% 成功生成且全域唯一。
- **自訂別名支援**：允許使用者自訂短碼（長度 4~16 位元，符合 `^[a-zA-Z0-9_-]+$` 格式），並嚴格檢核保留字與重複性。

---

### 2. Redis Cache-Aside 轉址與四重快取防護

轉址請求 `GET /{shortKey}` 執行流程：

```
[客戶端請求: GET /{shortKey}]
             │
             ▼
    [查詢 Redis 快取 (short_url:key:{shortKey})]
      │
      ├── 1. 命中空值標記 "__NULL__" ────► [防禦快取穿透: 快速回傳 40401 (不查 DB)]
      ├── 2. 命中正常短網址資料 ─────────► [檢核狀態/過期 ──► 發出 HTTP 302 Found 跳轉]
      └── 3. 快取未命中 (Cache Miss) ────┐
                                         ▼
                                [回源查詢 MySQL 資料庫]
                                  │
                                  ├── 查無紀錄 ──► [寫入 Redis "__NULL__" TTL 60s] ──► [回傳 40401 Not Found]
                                  ├── 狀態停用 ──► [回傳 40301 Forbidden]
                                  ├── 已經過期 ──► [回傳 41001 Gone/Expired]
                                  └── 正常有效 ──► [計算 TTL 並回填 Redis 快取] ──────► [發出 HTTP 302 Found 跳轉]
```

#### 🛡️ 四重快取防護設計：
1. **快取穿透防禦 (Negative Caching)**：DB 查無短碼時，於 Redis 寫入 `__NULL__` 標記（TTL = 60 秒），阻擋惡意隨機短碼掃描拖垮 DB。
2. **快取雪崩防護 (Random Jitter)**：永久有效短網址快取加入 $0 \sim 300$ 秒隨機抖動（$TTL = 86400 + \text{Random}(0, 300)$ 秒），避免大量 Key 同一時間點集體失效。
3. **快取擊穿與動態過期對齊**：具過期時間之短網址，快取 TTL 取 $\min(\text{剩餘秒數}, 86400 + \text{Jitter})$，確保快取資料絕不比 DB 延後失效。
4. **容錯自動降級 (Failover)**：當 Redis 發生網路逾時或宕機時，自動捕捉異常並降級回源 MySQL 查詢，核心轉址業務不中斷。

---

### 3. 高併發非同步存取統計與資料防遺失架構

為避免轉址時同步寫入資料庫造成延遲飆升，系統採用 **非同步記憶體緩衝 + Redis Pipeline + 排程批次回寫 MySQL** 機制：

```
[HTTP 302 轉址完成]
        │ (非同步發送 AccessEvent)
        ▼
[MetricsBufferService] ──► [BlockingQueue 記憶體緩衝佇列]
                                    │
                                    ▼ (虛擬執行緒 Worker 非同步消費)
                        [Redis Pipeline 批次累加與排隊]
                        - short_url:metrics:clicks (Hash 點擊數計數器)
                        - short_url:metrics:access_queue (List 存取明細日誌佇列)
                                    │
                                    ▼ (每 5 秒定時排程)
                        [MetricsPersistenceScheduler]
                                    │
                                    ▼ (批次更新與批量 Insert)
                        [MySQL: short_urls & access_logs]
```

- **極速轉址**：點擊事件在記憶體佇列非同步處理，完全不增加客戶端 HTTP 302 轉址延遲。
- **優雅停機防遺失 (Graceful Shutdown)**：透過 Spring Context 關閉事件監聽器 (`GracefulShutdownHandler`)，在服務停止前強制將佇列內所有殘留事件 Flush 至 Redis/MySQL，保證 100% 數據不遺失。
- **點擊即時聚合**：查詢統計 API 時，即時融合 MySQL 既有歷史累計值與 Redis 記憶體中的即時增量，提供精確無延遲的統計數據。

---

## 📊 k6 高併發壓力測試成果

使用 [Grafana k6](https://k6.io/) 針對轉址核心路徑 `GET /{shortKey}` 進行嚴格的高併發極限壓測（測試腳本見 `ai_context/STEP6-k6_short_url_press/workspace/`）：

### 1. 全域隨機轉址壓測 (200 VU, 持續 4 分鐘)
- **負載曲線**：Warm-up (30s, 10 VU) → Ramp-up (1m, 200 VU) → Sustain (2m, 200 VU) → Ramp-down (30s, 0 VU)
- **測試方式**：從 50 組短網址中全域隨機選取發送請求，模擬多連結分散點擊。

| 評測指標 | 驗證標準 | 實測結果 | 評定 |
| :--- | :--- | :--- | :--- |
| **P95 延遲** | < 200 ms | **45.39 ms** |  PASS |
| **P99 延遲** | < 500 ms | **67.86 ms** |  PASS |
| **平均吞吐量 (Throughput)** | — | **8,219.19 req/s** | 極致效能 |
| **請求錯誤率** | < 1.00% | **0.00%** (0 失敗) |  PASS |
| **轉址正確率 (Location Header 校驗)** | = 100.00% | **100.00%** (1,975,057 / 1,975,057) |  PASS |

---

### 2. 熱 Key 極限併發壓測 (200 VU, 持續 4 分鐘)
- **測試方式**：200 VU 峰值併發全部鎖定單一熱點短碼，模擬爆發性集中點擊與熱點穿透情境。

| 評測指標 | 驗證標準 | 實測結果 | 評定 |
| :--- | :--- | :--- | :--- |
| **P95 延遲** | < 200 ms | **46.45 ms** |  PASS |
| **P99 延遲** | < 500 ms | **70.33 ms** |  PASS |
| **平均吞吐量 (Throughput)** | — | **8,006.05 req/s** | 極致效能 |
| **Redis 快取命中率** | > 99.00% | **100.00%** (+550 萬次 Hit, 0 Miss) |  PASS |
| **點擊打點精確對帳 (k6 轉址次數 vs MySQL 累計次數)** | 零遺失 | **1,922,382 次 vs 1,922,382 次 (100% 吻合，0 遺失)** |  PASS |

---

### 3. 壓測方法論與關鍵發現
1. **環境狀態重置至關重要**：壓測前必須同時做到「清空 DB/Cache」與「重啟 JVM Process」，避免連線池狀態與 GC 殘留干擾測試數據可信度。
2. **日誌 I/O 驗證**：透過控制變因法證明日誌等級（DEBUG vs INFO）對吞吐量影響 < 2%（8,096 vs 8,219 req/s），排除日誌 I/O 瓶頸假設。
3. **非同步防遺失驗證**：證實非同步緩衝架構在每秒 8,000+ 筆高頻點擊下，依然達成 100.00% 資料對帳精確度。

---

## 📡 API 介面規格與合約

### 1. 建立短網址 API
- **URL**: `POST /api/v1/urls/shorten`
- **Content-Type**: `application/json`
- **Request Body**:
```json
{
  "original_url": "https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/302",
  "custom_alias": "mdn-302",
  "ttl_in_seconds": 86400
}
```
- **Response (200 OK)**:
```json
{
  "code": 0,
  "message": "success",
  "data": {
    "short_key": "mdn-302",
    "short_url": "http://localhost:8080/mdn-302",
    "original_url": "https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/302",
    "is_custom": true,
    "expired_at": "2026-09-19T09:30:00Z",
    "created_at": "2026-09-18T09:30:00Z"
  },
  "timestamp": 1758187800000
}
```

---

### 2. 短碼重定向轉址 API
- **URL**: `GET /{shortKey}` (例如 `GET /mdn-302`)
- **Success Response (302 Found)**:
  - **HTTP Status**: `302 Found`
  - **Response Headers**:
    - `Location: https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/302`
    - `Cache-Control: no-cache, no-store, max-age=0, must-revalidate`
    - `Pragma: no-cache`
- **Error Responses (JSON)**:

| HTTP Status | 業務錯誤碼 (`code`) | 觸發原因 | 錯誤回應範例 |
| :--- | :--- | :--- | :--- |
| `404 Not Found` | `40401` | 短碼不存在或命中防穿透快取 | `{"code": 40401, "message": "Short URL not found: notExist"}` |
| `410 Gone` | `41001` | 短網址已過期失效 | `{"code": 41001, "message": "Short URL has expired"}` |
| `403 Forbidden` | `40301` | 短網址已被停用 | `{"code": 40301, "message": "Short URL is disabled"}` |

---

### 3. 查詢短網址即時統計指標 API
- **URL**: `GET /api/v1/urls/{shortKey}/metrics`
- **Response (200 OK)**:
```json
{
  "code": 0,
  "message": "success",
  "data": {
    "short_key": "mdn-302",
    "original_url": "https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/302",
    "total_clicks": 15420,
    "status": 1,
    "created_at": "2026-09-18T09:30:00Z",
    "expired_at": "2026-09-19T09:30:00Z",
    "last_accessed_at": "2026-09-18T13:45:12Z"
  },
  "timestamp": 1758188000000
}
```

---

### 4. 分頁查詢歷史存取日誌 API
- **URL**: `GET /api/v1/urls/{shortKey}/logs?page=1&size=20&start_time=2026-09-18T00:00:00Z&end_time=2026-09-18T23:59:59Z`
- **Response (200 OK)**:
```json
{
  "code": 0,
  "message": "success",
  "data": {
    "items": [
      {
        "id": 1024,
        "short_key": "mdn-302",
        "ip": "192.168.1.100",
        "user_agent": "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)...",
        "referer": "https://google.com",
        "accessed_at": "2026-09-18T13:45:12Z"
      }
    ],
    "page": 1,
    "size": 20,
    "total_elements": 15420,
    "total_pages": 771
  },
  "timestamp": 1758188000000
}
```

---

### 5. 系統健康檢查 API
- **URL**: `GET /api/v1/health`
- **Response (200 OK)**:
```json
{
  "code": 0,
  "message": "success",
  "data": {
    "status": "UP"
  },
  "timestamp": 1758188000000
}
```

---

## 📁 專案架構與目錄結構

```text
java-URL-Shortener/
├── .github/workflows/
│   └── ci-cd.yml                          # GitHub Actions CI/CD Pipeline
├── ai_context/                            # 開發規格、架構設計與 k6 壓測報告
│   ├── STEP2-Spring_Boot_init/
│   ├── STEP3-URL_Shorten_Core/
│   ├── STEP4-URL_Redirect_Cache/
│   ├── STEP5-Access_Metrics/
│   └── STEP6-k6_short_url_press/          # k6 壓測腳本、測試紀錄與效能總結報告
├── src/
│   ├── main/
│   │   ├── java/com/urlshortener/
│   │   │   ├── UrlShortenerApplication.java   # Spring Boot 啟動類別
│   │   │   ├── common/                        # 通用封裝 (全域例外處理、統一 API 回應)
│   │   │   │   ├── exception/
│   │   │   │   │   ├── BusinessException.java
│   │   │   │   │   ├── ErrorCode.java
│   │   │   │   │   └── GlobalExceptionHandler.java
│   │   │   │   └── response/
│   │   │   │       └── ApiResponse.java
│   │   │   ├── component/                     # 核心演算法與輔助組件
│   │   │   │   ├── Base62Encoder.java         # Base62 字典轉換器
│   │   │   │   ├── HashGenerator.java         # MurmurHash3 雜湊計算器
│   │   │   │   ├── SnowflakeIdGenerator.java  # 雪花演算法唯一 ID 降級保底
│   │   │   │   ├── RedisCacheHelper.java      # Redis 快取封裝與四重防護
│   │   │   │   ├── MetricsRedisHelper.java    # 統計指標 Redis Pipeline 累加器
│   │   │   │   ├── GracefulShutdownHandler.java # 優雅停機記憶體 Flush 防遺失
│   │   │   │   └── UrlValidator.java          # URL 合法性與別名格式校驗
│   │   │   ├── config/
│   │   │   │   └── RedisConfig.java           # RedisTemplate & 連線池配置
│   │   │   ├── controller/                    # RESTful Web 控制器
│   │   │   │   ├── HealthController.java      # 健康檢查端點
│   │   │   │   ├── UrlShortenController.java  # 短網址建立控制器
│   │   │   │   ├── UrlRedirectController.java # 短碼 302 重定向控制器
│   │   │   │   └── UrlMetricsController.java  # 統計指標與存取日誌查詢控制器
│   │   │   ├── dto/                           # 請求/回應 Data Transfer Objects
│   │   │   ├── entity/                        # JPA 資料庫實體
│   │   │   │   ├── ShortUrl.java              # 短網址主表實體
│   │   │   │   └── AccessLog.java             # 存取日誌明細實體
│   │   │   ├── repository/                    # Spring Data JPA 資料訪問層
│   │   │   │   ├── ShortUrlRepository.java
│   │   │   │   ├── AccessLogRepository.java
│   │   │   │   └── AccessLogRepositoryCustomImpl.java # 動態條件分頁查詢
│   │   │   ├── scheduler/                     # 定時排程器
│   │   │   │   └── MetricsPersistenceScheduler.java # 統計數據定時批次回寫 MySQL
│   │   │   └── service/                       # 業務邏輯服務層
│   │   │       ├── UrlShortenService.java
│   │   │       ├── UrlRedirectService.java
│   │   │       ├── MetricsBufferService.java
│   │   │       └── AccessMetricsService.java
│   │   └── resources/
│   │       ├── application.yml                # 應用程式設定檔
│   │       └── db/migration/                  # Flyway Schema 遷移腳本
│   │           ├── V1__create_short_urls_table.sql
│   │           └── V2__add_click_count_and_create_access_logs_table.sql
│   └── test/                                  # 114 項單元測試與整合測試
├── Dockerfile                                 # Multi-stage Java 21 容器建置腳本
├── docker-compose.yml                         # MySQL 8 + Redis 7 + App 容器編排
├── pom.xml                                    # Maven 專案依賴配置
└── README.md
```

---

## 🚀 快速開始與本地運行

### 環境需求
- **JDK 21** 或更高版本 (建議 Eclipse Temurin 或 Amazon Corretto)
- **MySQL 8.0+** (預設連線連接埠 `3306`)
- **Redis 6.x+ / 7.x+** (預設連線連接埠 `6379`)
- **Maven 3.9+** (或使用專案內建 `./mvnw`)

### 設定檔說明 (`src/main/resources/application.yml`)
支援透過標準環境變數進行彈性覆蓋：
```yaml
spring:
  datasource:
    url: ${SPRING_DATASOURCE_URL:jdbc:mysql://localhost:3306/java_project?useSSL=false&serverTimezone=UTC&characterEncoding=UTF-8&allowPublicKeyRetrieval=true&tinyInt1isBit=false}
    username: ${SPRING_DATASOURCE_USERNAME:root}
    password: ${SPRING_DATASOURCE_PASSWORD:123456}
  data:
    redis:
      host: ${SPRING_DATA_REDIS_HOST:localhost}
      port: ${SPRING_DATA_REDIS_PORT:6379}
      username: ${SPRING_DATA_REDIS_USERNAME:default}
      password: ${SPRING_DATA_REDIS_PASSWORD:redis123456}
app:
  short-url:
    domain: ${APP_SHORT_URL_DOMAIN:http://localhost:8080}
```

### 建置與測試指令

1. **執行全專案自動化測試 (114 項測試)**：
```bash
./mvnw clean test
```

2. **本地直接啟動服務**：
```bash
./mvnw spring-boot:run
```

3. **打包生產 JAR 檔案**：
```bash
./mvnw clean package -DskipTests
java -jar target/url-shortener-0.0.1-SNAPSHOT.jar
```

---

### cURL 完整測試流程範例

#### 1. 建立短網址（指定自訂別名與 86400 秒過期）
```bash
curl -i -X POST http://localhost:8080/api/v1/urls/shorten \
  -H "Content-Type: application/json" \
  -d '{
    "original_url": "https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/302",
    "custom_alias": "mdn-test",
    "ttl_in_seconds": 86400
  }'
```

#### 2. 測試短網址跳轉（驗證 HTTP 302 重定向）
```bash
curl -i -X GET http://localhost:8080/mdn-test
```
*(回應包含 `Location: https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/302`)*

#### 3. 測試不存在短碼（驗證 Negative Caching 防穿透 40401）
```bash
curl -i -X GET http://localhost:8080/non-existent-link
```

#### 4. 查詢即時統計指標
```bash
curl -i -X GET http://localhost:8080/api/v1/urls/mdn-test/metrics
```

#### 5. 分頁查詢存取日誌
```bash
curl -i -X GET "http://localhost:8080/api/v1/urls/mdn-test/logs?page=1&size=10"
```

---

## 🐳 Docker 與 Docker Compose 容器化

專案內建最佳化的 **Multi-stage Dockerfile**（使用 `eclipse-temurin:21-jre-alpine` 輕量映像檔）與一鍵式 `docker-compose.yml`。

### 使用 Docker Compose 一鍵啟動完整環境

一條指令即可啟動包含 **MySQL 8.0**、**Redis 7** 與 **URL Shortener 服務**：

```bash
# 啟動所有服務（背景執行並自動構建映像檔）
docker compose up -d --build

# 檢查所有容器健康與運行狀態
docker compose ps

# 查看應用程式即時日誌
docker compose logs -f app

# 停止並清理容器
docker compose down
```

### 手動建置 Docker 映像檔

```bash
# 建置 Docker 映像檔
docker build -t url-shortener:latest .

# 運行容器
docker run -d \
  -p 8080:8080 \
  -e SPRING_DATASOURCE_URL="jdbc:mysql://host.docker.internal:3306/java_project?useSSL=false&serverTimezone=UTC&characterEncoding=UTF-8&allowPublicKeyRetrieval=true&tinyInt1isBit=false" \
  -e SPRING_DATA_REDIS_HOST="host.docker.internal" \
  -e SPRING_DATA_REDIS_PASSWORD="redis123456" \
  --name url-shortener-app \
  url-shortener:latest
```

---

## 🔄 CI/CD 自動化流程 (GitHub Actions)

專案配置於 [`.github/workflows/ci-cd.yml`](.github/workflows/ci-cd.yml)，採用現代化 GitOps 流水線：

```
[Git Push / PR to develop or main]
               │
               ▼
   ┌───────────────────────┐
   │ Job 1: Test & Build   │ ──► Setup Java 21 & Maven Cache
   │                       │ ──► 執行全自動單元/整合測試 (mvn clean verify)
   │                       │ ──► 打包 JAR 並上傳為 GitHub Artifact
   └───────────┬───────────┘
               │ (僅在 develop / main 分支 Push 且測試通過時)
               ▼
   ┌───────────────────────┐
   │ Job 2: Docker Build   │ ──► 登入 GitHub Container Registry (ghcr.io)
   │        & Push         │ ──► 使用 Docker Buildx 多層快取構建
   │                       │ ──► 自動標註 Tag (分支名、Git SHA、latest)
   │                       │ ──► 推送映像檔至 ghcr.io/<owner>/url-shortener
   └───────────────────────┘
```

- **Pull Request**：自動觸發測試與編譯檢查，杜絕破壞性改動合併。
- **Push `develop`**：自動發布測試映像檔 `ghcr.io/<owner>/url-shortener:develop` 與 `sha-<commit_id>`。
- **Push `main`**：自動發布生產最新映像檔 `ghcr.io/<owner>/url-shortener:latest`。
- **Release Tag (如 `v1.0.0`)**：自動產用語意化版本映像檔 `ghcr.io/<owner>/url-shortener:v1.0.0`。

---

## 🗺 開發階段與里程碑 (Roadmap)

- [x] **STEP2-Spring_Boot_init**：Spring Boot 3.3.5 骨架建立、統一 API 回應封裝 (`ApiResponse`)、全域異常處理器 (`GlobalExceptionHandler`) 與健康檢查端點。
- [x] **STEP3-URL_Shorten_Core**：短網址核心生成演算法（MurmurHash3 32-bit + Base62 6位短碼）、3 次加鹽碰撞重試、雪花演算法安全降級、長網址冪等查重、自訂別名驗證、TTL 有效期管理與 MySQL 持久化。
- [x] **STEP4-URL_Redirect_Cache**：HTTP 302 Found 重定向轉址、Redis 快取層整合、Cache-Aside 雙層查詢、空值快取防穿透、TTL 隨機抖動防雪崩、動態過期對齊防擊穿與 Redis 異常自動降級。
- [x] **STEP5-Access_Metrics**：轉址存取事件日誌、非同步記憶體緩衝、優雅關機資料防遺失（100% Flush 至 Redis/MySQL）、定時排程批次回寫 MySQL、即時指標聚合與存取日誌分頁查詢 API。
- [x] **STEP6-Stress_Testing_Benchmark**：k6 高併發壓力測試（全域隨機 200 VU / 熱 Key 極限擊穿）、8,200+ QPS 驗證、P95 45.39ms、100% 轉址正確率與 192 萬筆點擊打點 0 遺失對帳。
