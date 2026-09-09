# Java 高效能分散式短網址服務 (URL Shortener Service)

[![Java Version](https://img.shields.io/badge/Java-21-orange.svg)](https://www.oracle.com/java/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3.5-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Redis](https://img.shields.io/badge/Redis-Cache--Aside-red.svg)](https://redis.io/)
[![MySQL](https://img.shields.io/badge/MySQL-InnoDB-blue.svg)](https://www.mysql.com/)
[![Build Status](https://img.shields.io/badge/Tests-98%2F98%20Passed-success.svg)]()

基於 **Spring Boot 3.3.5** 與 **Java 21（啟用虛擬執行緒 Virtual Threads）** 打造的高併發、低延遲短網址服務。系統整合 **MurmurHash3 + Base62** 核心生成演算法、多重防碰撞安全降級機制，並搭配 **Redis Cache-Aside 高效快取層** 與三道快取防護（防穿透、防雪崩、防擊穿），提供毫秒級的 HTTP 302 重定向服務。

---

## 📑 目錄
- [技術架構與選型](#-技術架構與選型)
- [開發進度與路線圖 (Roadmap)](#-開發進度與路線圖-roadmap)
- [核心機制與演算法設計](#-核心機制與演算法設計)
  - [1. 短碼生成與防碰撞重試流程](#1-短碼生成與防碰撞重試流程)
  - [2. Redis Cache-Aside 與三道快取防護](#2-redis-cache-aside-與三道快取防護)
- [API 介面規格與合約](#-api-介面規格與合約)
  - [1. 建立短網址 API](#1-建立短網址-api)
  - [2. 短碼重定向轉址 API](#2-短碼重定向轉址-api)
  - [3. 系統健康檢查 API](#3-系統健康檢查-api)
- [快速開始與本地運行](#-快速開始與本地運行)
  - [環境需求](#環境需求)
  - [設定檔配置](#設定檔配置)
  - [啟動與測試指令](#啟動與測試指令)
  - [cURL 測試範例](#curl-測試範例)
- [🐳 Docker 與 Docker Compose 容器化](#-docker-與-docker-compose-容器化)
  - [使用 Docker Compose 一鍵啟動完整環境](#使用-docker-compose-一鍵啟動完整環境)
  - [手動建置與運行 Docker 映像檔](#手動建置與運行-docker-映像檔)
- [🔄 CI/CD 自動化流程 (GitHub Actions)](#-cicd-自動化流程-github-actions)

---

## 🛠 技術架構與選型

| 領域 | 技術組件 | 說明 |
| :--- | :--- | :--- |
| **開發語言** | Java 21 (LTS) | 啟用 Java 21 Virtual Threads (虛擬執行緒)，實現百萬級併發 I/O 吞吐 |
| **核心框架** | Spring Boot 3.3.5 | 現代化輕量服務架構、依賴注入與 RESTful API 控制器 |
| **持久化層** | Spring Data JPA + Hibernate | 關聯式資料持久化與索引優化查詢 |
| **快取架構** | Spring Data Redis (Lettuce) | 記憶體快取層 (Cache-Aside Pattern)、自訂連線池與 Jackson JSON 序列化 |
| **關聯式資料庫**| MySQL 8.0 / H2 Database | 支援 Flyway 遷移腳本與 H2 記憶體測試環境 |
| **雜湊與編碼** | Google Guava (MurmurHash3) + Base62 | 62 進位可見字元編碼、低碰撞非連續雜湊計算 |
| **安全與降級** | Snowflake ID Generator | 自製雪花演算法產生器，供極端雜湊碰撞時安全保底 |
| **資料庫遷移** | Flyway Core 10.x | 版本化資料庫 Schema 遷移 (`V1__create_short_urls_table.sql`) |
| **測試驗證** | JUnit 5 + Mockito + MockMvc | 包含 98 項涵蓋 Table-Driven 單元測試與全流程整合測試 |

---

## 🗺 開發進度與路線圖 (Roadmap)

- [x] **STEP2-Spring_Boot_init**：Spring Boot 3.3.5 骨架建立、統一 API 回應封裝 (`ApiResponse`)、全域異常處理器 (`GlobalExceptionHandler`) 與健康檢查端點。
- [x] **STEP3-URL_Shorten_Core**：短網址核心生成演算法（MurmurHash3 32-bit + Base62 6位短碼）、3次加鹽碰撞重試、雪花演算法安全降級、長網址冪等查重、自訂別名驗證、TTL 有效期管理與 MySQL 持久化。
- [x] **STEP4-URL_Redirect_Cache**：HTTP 302 Found 重定向轉址、Redis 快取層整合、Cache-Aside 雙層查詢、空值快取防穿透、TTL 隨機抖動防雪崩、動態過期對齊防擊穿與 Redis 異常自動降級。
- [ ] **STEP5-Access_Metrics** *(下期規劃)*：轉址存取事件日誌、非同步點擊次數累加（Redis INCR / 非同步批次回寫）與統計指標查詢 API。
- [ ] **STEP6-Stress_Testing_Benchmark** *(下期規劃)*：基於 k6 / JMeter 進行高併發極限壓測（讀/寫/穿透情境），連線池與 JVM 調優並產出效能分析報告。

---

## 🧠 核心機制與演算法設計

### 1. 短碼生成與防碰撞重試流程

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

### 2. Redis Cache-Aside 與三道快取防護

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

#### 🛡️ 快取三大防護措施：
1. **快取穿透防禦 (Negative Caching)**：DB 查無資料時，在 Redis 寫入 `__NULL__` 標記（TTL = 60 秒），杜絕惡意隨機短碼掃描拖垮 DB。
2. **快取雪崩防護 (Random Jitter)**：永久有效短網址快取加入 $0 \sim 300$ 秒隨機抖動（$TTL = 86400 + \text{Random}(0, 300)$ 秒），避免大量 Key 同時到期集體穿透。
3. **快取擊穿與動態過期對齊**：帶有 TTL 的短網址，快取存活時間嚴格取 $\min(\text{剩餘秒數}, 86400 + \text{Jitter})$，確保 Redis 絕不會在過期後提供錯誤轉址。
4. **容錯自動降級**：當 Redis 網路逾時或宕機時，自動捕捉異常並降級回源 MySQL 查詢，核心轉址業務不中斷。

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
    "expired_at": "2026-09-08T09:30:00Z",
    "created_at": "2026-09-07T09:30:00Z"
  },
  "timestamp": 1757151000000
}
```

---

### 2. 短碼重定向轉址 API
- **URL**: `GET /{shortKey}` (例如 `GET /mdn-302`)
- **Success Response (302 Found)**:
  - **HTTP Status**: `302 Found`
  - **Headers**:
    - `Location: https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/302`
    - `Cache-Control: no-cache, no-store, max-age=0, must-revalidate`
    - `Pragma: no-cache`
- **Error Responses (JSON)**:

| HTTP Code | 業務錯誤碼 (`code`) | 觸發條件 | 錯誤訊息範例 |
| :--- | :--- | :--- | :--- |
| `404 Not Found` | `40401` | 短碼不存在或空值快取命中 | `{"code": 40401, "message": "Short URL not found: notExist"}` |
| `410 Gone` | `41001` | 短網址已逾期失效 | `{"code": 41001, "message": "Short URL has expired"}` |
| `403 Forbidden` | `40301` | 短網址已被停用 | `{"code": 40301, "message": "Short URL is disabled"}` |

---

### 3. 系統健康檢查 API
- **URL**: `GET /api/v1/health`
- **Success Response (200 OK)**:
```json
{
  "code": 0,
  "message": "success",
  "data": {
    "status": "UP"
  },
  "timestamp": 1757151000000
}
```

---

## 🚀 快速開始與本地運行

### 環境需求
- **JDK 21** 或更高版本
- **MySQL 8.0+**（本地端預設連接埠 `3306`）
- **Redis 6.x+ / 7.x+**（本地端預設連接埠 `6379`）
- **Maven 3.9+**（或使用專案內建 `./mvnw`）

### 設定檔配置 (`src/main/resources/application.yml`)
預設支援環境變數覆蓋：
```yaml
spring:
  datasource:
    url: ${SPRING_DATASOURCE_URL:jdbc:mysql://localhost:3306/java_project?useSSL=false&serverTimezone=UTC&characterEncoding=UTF-8&allowPublicKeyRetrieval=true}
    username: ${SPRING_DATASOURCE_USERNAME:root}
    password: ${SPRING_DATASOURCE_PASSWORD:123456}
  data:
    redis:
      host: ${SPRING_DATA_REDIS_HOST:localhost}
      port: ${SPRING_DATA_REDIS_PORT:6379}
      username: ${SPRING_DATA_REDIS_USERNAME:default}
      password: ${SPRING_DATA_REDIS_PASSWORD:redis123456}
```

### 啟動與測試指令

1. **執行全專案單元與整合測試**：
```bash
./mvnw clean test
```

2. **本地啟動 Spring Boot 服務**：
```bash
./mvnw spring-boot:run
```

---

### cURL 測試範例

#### 1. 建立一個 60 秒過期的短網址
```bash
curl -i -X POST http://localhost:8080/api/v1/urls/shorten \
  -H "Content-Type: application/json" \
  -d '{
    "original_url": "https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/302",
    "custom_alias": "mdn-test",
    "ttl_in_seconds": 60
  }'
```

#### 2. 存取短網址跳轉（驗證 HTTP 302）
```bash
curl -i -X GET http://localhost:8080/mdn-test
```
*(回應將帶有 `Location: https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/302`)*

#### 3. 測試不存在短碼（驗證防穿透 40401）
```bash
curl -i -X GET http://localhost:8080/non-existent-link
```

---

## 🐳 Docker 與 Docker Compose 容器化

專案已內建最佳化的 **Multi-stage Dockerfile**（使用 `Java 21 Alpine` 輕量映像檔）與一鍵式 `docker-compose.yml`。

### 使用 Docker Compose 一鍵啟動完整環境

只需一條指令即可啟動包含 **MySQL 8.0**、**Redis 7** 與 **URL Shortener 應用** 的完整叢集：

```bash
# 啟動所有服務（背景執行並自動建置映像檔）
docker compose up -d --build

# 查看所有容器運行狀態
docker compose ps

# 查看應用程式即時日誌
docker compose logs -f app

# 停止並移除所有容器
docker compose down
```

### 手動建置與運行 Docker 映像檔

```bash
# 1. 建置 Docker 映像檔
docker build -t url-shortener:latest .

# 2. 運行應用容器（需確保本機已有 MySQL 與 Redis 運行）
docker run -d \
  -p 8080:8080 \
  -e SPRING_DATASOURCE_URL="jdbc:mysql://host.docker.internal:3306/java_project?useSSL=false&serverTimezone=UTC&characterEncoding=UTF-8&allowPublicKeyRetrieval=true" \
  -e SPRING_DATA_REDIS_HOST="host.docker.internal" \
  --name url-shortener-app \
  url-shortener:latest
```

---

## 🔄 CI/CD 自動化流程 (GitHub Actions)

專案配置於 [`.github/workflows/ci-cd.yml`](.github/workflows/ci-cd.yml)，實現現代化 GitOps 自動化整合與發布流程：

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

### 觸發與行為說明：
1. **Pull Request (對象為 `develop` 或 `main`)**：
   - 觸發 `Job 1: Test & Build`，進行程式碼驗證與測試，確保 PR 無破壞性改動。
2. **Push 到 `develop` 分支**：
   - 執行 `Job 1` 測試與 JAR 包版。
   - 執行 `Job 2` 自動構建 Docker 映像檔並發布標籤 `ghcr.io/<owner>/url-shortener:develop` 與 `ghcr.io/<owner>/url-shortener:sha-<commit_id>`。
3. **Push 到 `main` 分支**：
   - 自動產出 `latest` 與對應 SHA 標籤之生產發布映像檔。

