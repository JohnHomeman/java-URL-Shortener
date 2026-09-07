# 執行計畫: STEP3-URL_Shorten_Core 短網址核心生成演算法與資料持久化

> <!-- [IMPORTANT] 本文件為施工地圖 (The How)。定義施工步驟與驗證動作。嚴禁重複定義邏輯或資料結構，一律以「Spec §N.N」格式引用。 -->
> <!-- ⚠️ 狀態管理：產出者應保持所有 Checkbox 為 [ ]。勾選動作 [x] 僅保留給「最終實作執行者」。 -->
> <!-- 職責邊界：Plan 只回答「改哪些檔案、按什麼順序做、怎麼跑驗證」。 -->
> <!-- ❌ 禁止出現在 Plan 中：新的期望數字、SQL Schema 、API Response 結構、業務計算公式。這些是 Spec 的內容，此處只能引用。 -->

## 1. 實作階段 (Implementation Stages)

### Stage 1: 資料庫與持久化基礎建設 (Database & Persistence Setup)
- [x] [MODIFY] `pom.xml`: 根據 Spec §6.1、§6.2 引入 JPA (`spring-boot-starter-data-jpa`)、MySQL 驅動 (`mysql-connector-j`)、H2 記憶體資料庫 (`h2`)、Flyway 遷移 (`flyway-core`, `flyway-mysql`) 與 Guava 工具庫依賴。
- [x] [MODIFY] `src/main/resources/application.yml`: 配置 JPA/Hibernate、H2/MySQL 資料來源、Flyway 遷移路徑及自訂短網址 Base URL (`app.short-url.domain`)。
- [x] [NEW] `src/main/resources/db/migration/V1__create_short_urls_table.sql`: 根據 Spec §4.1、§6.5 建立短網址資料庫遷移腳本。
- [x] [NEW] `src/main/java/com/urlshortener/entity/ShortUrl.java`: 根據 Spec §4.1 實作短網址 JPA 實體類別與欄位對應。
- [x] [NEW] `src/main/java/com/urlshortener/repository/ShortUrlRepository.java`: 根據 Spec §2、§4.1 實作 Spring Data JPA Repository 介面（提供 `findByShortKey`、`findByOriginalUrlHashAndOriginalUrl` 等查詢方法）。
- **✅ Checkpoint**: 執行 `./mvnw compile` 無報錯，Spring Boot 測試上下文可順利啟動並透過 Flyway 完成 Schema 建立。

### Stage 2: 核心演算法與驗證組件 (Core Algorithm & Components)
- [x] [NEW] `src/main/java/com/urlshortener/component/Base62Encoder.java`: 根據 Spec §3.2 實作 Base62 字符集之 64-bit 整數編碼與解碼工具。
- [x] [NEW] `src/main/java/com/urlshortener/component/HashGenerator.java`: 根據 Spec §3.2 封裝 MurmurHash3 (32-bit) 雜湊計算與加鹽雜湊方法。
- [x] [NEW] `src/main/java/com/urlshortener/component/SnowflakeIdGenerator.java`: 根據 Spec §3.2 實作雪花演算法（64-bit 唯一 ID 產生器），供碰撞降級使用。
- [x] [NEW] `src/main/java/com/urlshortener/component/UrlValidator.java`: 根據 Spec §3.1、§3.3、§6.4 實作 URL 協議格式、長度、自指向防禦與系統保留字驗證組件。
- [x] [NEW] `src/test/java/com/urlshortener/component/Base62EncoderTest.java`: 撰寫單元測試以驗證 Spec §7.1 之 Base62 編解碼測項。
- [x] [NEW] `src/test/java/com/urlshortener/component/HashGeneratorTest.java`: 撰寫單元測試以驗證 Spec §7.1 之 MurmurHash3 測項。
- [x] [NEW] `src/test/java/com/urlshortener/component/SnowflakeIdGeneratorTest.java`: 撰寫單元測試驗證雪花演算法 ID 唯一性與遞增性。
- [x] [NEW] `src/test/java/com/urlshortener/component/UrlValidatorTest.java`: 撰寫單元測試以驗證 Spec §7.1 之 URL 格式、自指向防禦與保留字測項。
- **✅ Checkpoint**: 執行 `./mvnw test` 通過所有核心組件的單元測試測項。

### Stage 3: 業務邏輯服務層實作 (Business Service & Collision Resolution)
- [x] [NEW] `src/main/java/com/urlshortener/dto/ShortenUrlRequest.java`: 根據 Spec §5.1 定義生成短網址之請求 DTO 與 Bean Validation 約束。
- [x] [NEW] `src/main/java/com/urlshortener/dto/ShortenUrlResponse.java`: 根據 Spec §5.1 定義生成短網址之回應 DTO 結構。
- [x] [NEW] `src/main/java/com/urlshortener/service/UrlShortenService.java`: 根據 Spec §3.1 ~ §3.4、§6.3 實作短網址建立業務流程（正規化校驗、長網址冪等查重、Hash+Base62 生成、3 次加鹽重試、Snowflake 降級、自訂別名唯一性驗證、TTL 計算與資料庫持久化）。
- [x] [NEW] `src/test/java/com/urlshortener/service/UrlShortenServiceTest.java`: 撰寫 Service 單元測試，模擬各類業務場景（常態生成、長網址冪等查重、碰撞加鹽重試、雪花降級觸發、別名衝突與過期時間計算）。
- **✅ Checkpoint**: 執行 Service 層測試全數通過，涵蓋所有狀態流轉與分支覆蓋。

### Stage 4: API 控制器與全流程整合驗收 (Controller & Integration Tests)
- [x] [NEW] `src/main/java/com/urlshortener/controller/UrlShortenController.java`: 根據 Spec §5.1 實作 `POST /api/v1/urls/shorten` API 端點。
- [x] [NEW] `src/test/java/com/urlshortener/controller/UrlShortenControllerIntegrationTest.java`: 撰寫 MockMvc 整合測試，驗證 Spec §7.2 情境 A ~ E 以及 Spec §5.1 所有錯誤碼回傳情況（40001 ~ 40005, 40901）。
- **✅ Checkpoint**: 執行 `./mvnw clean test` 全案通過，所有 API 合約與業務驗收情境皆通過驗證。

## 2. 風險與注意事項 (Risks & Notes)
- [Stage 1 相關] H2 記憶體資料庫在執行 MySQL 語法的 DDL 時需確認 MySQL 模式相容性，並確保 Flyway 能在測試環境正確自動執行。
- [Stage 2 相關] MurmurHash3 32-bit 產出之整數在 Java 中為 signed 32-bit int，進行 Base62 編碼前須透過 `Integer.toUnsignedLong()` 轉為非負 64-bit Long，避免負數導致編碼異常。
- [Stage 3 相關] 長網址查重需先比對 `original_url_hash` 再精確比對 `original_url`，以發揮資料庫索引最佳效益；同時需確認紀錄是否已過期（若已過期則視為失效不可重用）。

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
2. **驗證一般自動生成短網址 (情境 A)**：
   ```bash
   curl -i -X POST http://localhost:8080/api/v1/urls/shorten \
     -H "Content-Type: application/json" \
     -d '{"original_url": "https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/302"}'
   ```
   * 檢查 HTTP 狀態碼為 200，期望結果引用 Spec §7.2 情境 A。
3. **驗證長網址重複請求冪等性 (情境 B)**：
   ```bash
   curl -i -X POST http://localhost:8080/api/v1/urls/shorten \
     -H "Content-Type: application/json" \
     -d '{"original_url": "https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/302"}'
   ```
   * 檢查回傳短碼與前次一致，期望結果引用 Spec §7.2 情境 B。
4. **驗證自訂別名與衝突 (情境 C, D)**：
   ```bash
   curl -i -X POST http://localhost:8080/api/v1/urls/shorten \
     -H "Content-Type: application/json" \
     -d '{"original_url": "https://example.com/promo", "custom_alias": "promo-2026"}'
   ```
   * 再次重複送出相同別名，檢查回傳 HTTP 409 與錯誤代碼 40901，期望結果引用 Spec §7.2 情境 C、D。
5. **驗證 TTL 設定 (情境 E)**：
   ```bash
   curl -i -X POST http://localhost:8080/api/v1/urls/shorten \
     -H "Content-Type: application/json" \
     -d '{"original_url": "https://example.com/temp", "ttl_in_seconds": 3600}'
   ```
   * 檢查回傳之 `expired_at`，期望結果引用 Spec §7.2 情境 E。
