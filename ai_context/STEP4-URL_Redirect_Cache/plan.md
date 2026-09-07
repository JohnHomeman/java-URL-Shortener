# 執行計畫: STEP4-URL_Redirect_Cache 短網址轉址重定向與 Redis 快取層實作

> <!-- [IMPORTANT] 本文件為施工地圖 (The How)。定義施工步驟與驗證動作。嚴禁重複定義邏輯或資料結構，一律以「Spec §N.N」格式引用。 -->
> <!-- ⚠️ 狀態管理：產出者應保持所有 Checkbox 為 [ ]。勾選動作 [x] 僅保留給「最終實作執行者」。 -->
> <!-- 職責邊界：Plan 只回答「改哪些檔案、按什麼順序做、怎麼跑驗證」。 -->
> <!-- ❌ 禁止出現在 Plan 中：新的期望數字、SQL Schema 、API Response 結構、業務計算公式。這些是 Spec 的內容，此處只能引用。 -->

## 1. 實作階段 (Implementation Stages)

### Stage 1: Redis 快取層依賴與基礎連線配置 (Redis Dependency & Configuration)
- [x] [MODIFY] `pom.xml`: 根據 Spec §6.4 引入 Spring Data Redis (`spring-boot-starter-data-redis`) 與連線池依賴 (`commons-pool2`)。
- [x] [MODIFY] `src/main/resources/application.yml`: 根據 Spec §6.4 配置本機 Redis 連線參數（`host`, `port`, `username`, `password`, `database` 及 Lettuce 連線池屬性）。
- [x] [NEW] `src/main/java/com/urlshortener/dto/ShortUrlCacheDto.java`: 根據 Spec §4.1 建立短網址快取資料傳輸物件（包含 `original_url`, `status`, `expired_at`）。
- [x] [NEW] `src/main/java/com/urlshortener/config/RedisConfig.java`: 根據 Spec §4.1、§6.4 配置 `RedisTemplate<String, Object>`、`StringRedisTemplate`、Key 序列化器 (`StringRedisSerializer`) 與 Value 序列化器 (Jackson JSON Serializer)。
- **✅ Checkpoint**: 執行 `./mvnw compile` 編譯通過，Spring Boot 上下文能正確載入 Redis 相關 Bean 配置。

### Stage 2: 快取輔助組件與異常擴充 (Cache Helper & Exception Expansion)
- [x] [MODIFY] `src/main/java/com/urlshortener/common/exception/ErrorCode.java`: 根據 Spec §2、§5.1 擴充轉址相關業務錯誤代碼（`40401 SHORT_URL_NOT_FOUND`、`41001 SHORT_URL_EXPIRED`、`40301 SHORT_URL_DISABLED`）。
- [x] [NEW] `src/main/java/com/urlshortener/component/RedisCacheHelper.java`: 根據 Spec §3.1、§3.2、§4.1 實作快取操作封裝組件（包含 Key 格式化、空值標記 `__NULL__` 存取與判定、TTL 隨機抖動計算、動態剩餘過期時間映射與 Redis 異常安全捕捉降級）。
- [x] [NEW] `src/test/java/com/urlshortener/component/RedisCacheHelperTest.java`: 撰寫單元測試驗證 Spec §3.2 之 TTL 抖動隨機範圍、過期時間計算與空值判定。
- **✅ Checkpoint**: 執行單元測試通過，快取輔助工具邏輯符合預期。

### Stage 3: 轉址業務服務層實作 (Redirect Service & Cache-Aside Flow)
- [x] [NEW] `src/main/java/com/urlshortener/service/UrlRedirectService.java`: 根據 Spec §3.1、§3.2、§6.2 實作轉址業務流程（Redis 快取優先查詢、空值穿透攔截、Cache Miss 回源 DB 查詢、停用/過期狀態判定、動態快取回填與目標 URL 取得）。
- [x] [NEW] `src/test/java/com/urlshortener/service/UrlRedirectServiceTest.java`: 撰寫 Service 單元測試以驗證 Spec §7.1 定義之所有 Table-Driven 測項（快取命中正常資料、快取命中穿透空值、快取未命中但 DB 存在、快取未命中且 DB 不存在、短網址已停用、短網址已過期、Redis 連線異常容錯降級）。
- **✅ Checkpoint**: 執行 `./mvnw test -Dtest=UrlRedirectServiceTest` 全數通過，各項業務情境分支覆蓋完整。

### Stage 4: API 控制器與全流程整合驗收 (Controller & Integration Tests)
- [x] [NEW] `src/main/java/com/urlshortener/controller/UrlRedirectController.java`: 根據 Spec §5.1 實作 `GET /{shortKey}` 轉址重定向端點，回傳 HTTP 302 Found 狀態與 `Location` Header。
- [x] [NEW] `src/test/java/com/urlshortener/controller/UrlRedirectControllerIntegrationTest.java`: 撰寫 MockMvc 整合測試，驗證 Spec §5.1 定義之 HTTP 302 跳轉 Header、40401、41001、40301 錯誤回應結構，並驗收 Spec §7.2 情境 A ~ F。
- **✅ Checkpoint**: 執行 `./mvnw clean test` 全案通過，所有 API 合約與業務驗收情境皆通過驗證。

## 2. 風險與注意事項 (Risks & Notes)
- [Stage 1 相關] Spring Boot 3.x 預設採用 Lettuce 作為 Redis 客戶端，需額外引入 `org.apache.commons:commons-pool2` 才能啟用連線池（`spring.data.redis.lettuce.pool`）機制。
- [Stage 2 相關] Redis 發生連線逾時或宕機時，`RedisCacheHelper` 須妥善捕捉 `DataAccessException` / `RedisConnectionFailureException`，記錄 WARN 日誌後回傳空值，避免 Redis 異常直接造成轉址服務中斷（引用 Spec §6.5）。
- [Stage 3 相關] 快取空值防禦時，需區分「快取未命中（Cache Miss，回傳 null）」與「命中空值標記（Hit Null Object，回傳 `__NULL__`）」，前者需回源 DB，後者應直接拋出 40401 快速失敗。
- [Stage 4 相關] `GET /{shortKey}` 為根路徑動態比對，需確保不影響 `/api/**` 等既有 API 路由分派與靜態資源存取。

## 3. 驗證動作 (Verification Actions)

### 3.1 自動化測試指令
> 測試數據來源：引用 Spec §7.1 與 §7.2
```bash
./mvnw clean test
```

### 3.2 手動驗證步驟 (cURL)
> 驗收情境來源：引用 Spec §7.2

1. **啟動應用程式**：
   ```bash
   ./mvnw spring-boot:run
   ```

2. **建立測試短網址**：
   ```bash
   curl -i -X POST http://localhost:8080/api/v1/urls/shorten \
     -H "Content-Type: application/json" \
     -d '{"original_url": "https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/302", "custom_alias": "mdn-302"}'
   ```

3. **驗證首次轉址 (Cache Miss -> DB Hit -> 快取回填，情境 A)**：
   ```bash
   curl -i -X GET http://localhost:8080/mdn-302
   ```
   * 檢查 HTTP 狀態碼為 302，且 `Location` Header 包含原始網址，期望結果引用 Spec §7.2 情境 A。

4. **驗證第二次轉址 (Cache Hit，情境 B)**：
   ```bash
   curl -i -X GET http://localhost:8080/mdn-302
   ```
   * 檢查 HTTP 狀態碼為 302，期望結果引用 Spec §7.2 情境 B。

5. **驗證不存在短碼防穿透 (DB Miss -> 空值快取，情境 C)**：
   ```bash
   curl -i -X GET http://localhost:8080/non-existent-key
   ```
   * 檢查 HTTP 狀態碼為 404，錯誤代碼為 40401，期望結果引用 Spec §7.2 情境 C。

6. **驗證連續請求不存在短碼 (Cache Hit Null 快速失敗，情境 D)**：
   ```bash
   curl -i -X GET http://localhost:8080/non-existent-key
   ```
   * 檢查 HTTP 狀態碼為 404，錯誤代碼為 40401，期望結果引用 Spec §7.2 情境 D。

7. **驗證過期短網址 (情境 E)**：
   - 先建立一筆 1 秒過期之短網址，等待 2 秒後發起轉址請求：
   ```bash
   curl -i -X GET http://localhost:8080/{expiredKey}
   ```
   * 檢查 HTTP 狀態碼為 410，錯誤代碼為 41001，期望結果引用 Spec §7.2 情境 E。
