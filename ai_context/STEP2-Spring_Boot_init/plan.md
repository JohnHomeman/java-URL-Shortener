# 執行計畫: STEP2-Spring_Boot_init Spring Boot 基礎專案骨架建立

> <!-- [IMPORTANT] 本文件為施工地圖 (The How)。定義施工步驟與驗證動作。嚴禁重複定義邏輯或資料結構，一律以「Spec §N.N」格式引用。 -->
> <!-- ⚠️ 狀態管理：產出者應保持所有 Checkbox 為 [ ]。勾選動作 [x] 僅保留給「最終實作執行者」。 -->
> <!-- 職責邊界：Plan 只回答「改哪些檔案、按什麼順序做、怎麼跑驗證」。 -->
> <!-- ❌ 禁止出現在 Plan 中：新的期望數字、SQL Schema 、API Response 結構、業務計算公式。這些是 Spec 的內容，此處只能引用。 -->

## 1. 實作階段 (Implementation Stages)

### Stage 1: 專案基礎建設與依賴配置 (Project Setup & Build Config)
- [x] [NEW] `pom.xml`: 根據 Spec §6.1、§6.2 配置 Java 21、Spring Boot 3.x 依賴（Web、Validation、Lombok 與 Test Starter）。
- [x] [NEW] `src/main/resources/application.yml`: 配置 Spring 基礎服務埠號 (8080) 與應用程式名稱。
- [x] [NEW] `src/main/java/com/urlshortener/UrlShortenerApplication.java`: 建立 Spring Boot 主應用程式啟動類別。
- **✅ Checkpoint**: 專案可透過 Maven 進行編譯與打包，無語法或依賴解析錯誤。

### Stage 2: 全域通用封裝與異常攔截 (Common Response & Global Exception)
- [x] [NEW] `src/main/java/com/urlshortener/common/response/ApiResponse.java`: 根據 Spec §3.1 實作統一回應封裝結構與靜態工廠方法。
- [x] [NEW] `src/main/java/com/urlshortener/common/exception/BusinessException.java`: 實作自訂業務異常類別，支援錯誤碼與錯誤訊息。
- [x] [NEW] `src/main/java/com/urlshortener/common/exception/GlobalExceptionHandler.java`: 根據 Spec §3.2、§5.2 實作 `@RestControllerAdvice`，統一捕捉並轉換各類異常為標準錯誤回應。
- [x] [NEW] `src/test/java/com/urlshortener/common/response/ApiResponseTest.java`: 撰寫單元測試以驗證 Spec §7.1 表格定義的測項。
- **✅ Checkpoint**: `mvn test` 通過 Spec §7.1 所有單元測試測項。

### Stage 3: 控制器與介面路由 (Controller & Route)
- [x] [NEW] `src/main/java/com/urlshortener/controller/HealthController.java`: 根據 Spec §3.3、§5.1 實作健康檢查端點。
- [x] [NEW] `src/test/java/com/urlshortener/controller/HealthControllerTest.java`: 撰寫 MockMvc 整合測試以驗證 Spec §7.2 情境 A。
- [x] [NEW] `src/test/java/com/urlshortener/common/exception/GlobalExceptionHandlerTest.java`: 撰寫 MockMvc 整合測試以驗證 Spec §7.2 情境 B 與情境 C。
- **✅ Checkpoint**: 執行測試通過所有整合測試，API 回應結構與狀態碼完全符合 Spec §5 定義。

## 2. 風險與注意事項 (Risks & Notes)
- [Stage 1 相關] 需確認本機環境具備 JDK 21 執行環境，避免編譯版本不相容問題。
- [Stage 2 相關] Spring Boot 3 預設在 404 時可能不拋出 `NoHandlerFoundException`，需確認 WebMvc 參數設定，確保未知路由能正確由 `GlobalExceptionHandler` 統一攔截並輸出 Spec §5.2 定義之結構。

## 3. 驗證動作 (Verification Actions)

### 3.1 自動化測試指令
> 測試數據來源：引用 Spec §7.1 與 §7.2
```bash
./mvnw clean test
```

### 3.2 手動驗證步驟 (cURL)
> 驗收情境來源：引用 Spec §7.2。

1. **啟動應用程式**：
   ```bash
   ./mvnw spring-boot:run
   ```
2. **驗證健康檢查端點**：
   ```bash
   curl -i -X GET http://localhost:8080/api/v1/health
   ```
   * 檢查 HTTP 狀態碼與回應 JSON，期望結果引用 Spec §7.2 情境 A。
3. **驗證未知路徑 404 異常攔截**：
   ```bash
   curl -i -X GET http://localhost:8080/api/v1/non-existent-path
   ```
   * 檢查 HTTP 狀態碼與回應 JSON，期望結果引用 Spec §7.2 情境 B。
