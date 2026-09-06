# STEP2-Spring_Boot_init: Spring Boot 基礎專案骨架建立

> <!-- [IMPORTANT] 本文件為唯一真相 (The What)。建議遵循：背景 -> 邏輯 -> 資料 -> 合約 的順序撰寫。 -->
> <!-- 職責邊界：Spec 只回答「系統做什麼、資料長什麼樣、期望結果是什麼」。-->
> <!-- ❌ 禁止出現在 Spec 中：檔案路徑、mvn/gradle 指令、cURL 命令、Migration 步驟。這些歸 Plan。 -->

## 1. 需求背景 (Background)
本專案目標為建置高效能「縮網址轉換服務 (URL Shortener)」。為確保後續縮網址核心業務（短碼生成、長網址重定向、存取統計）具備標準化之架構基礎，本階段優先導入 Java Spring Boot 基礎專案骨架，確立分層架構、全域異常處理機制、標準 API 統一回應格式及健康檢查機制。

## 2. 系統設計與影響範圍 (System Design)
- **核心目標**：建立 Spring Boot 基礎骨架，提供標準化分層架構、全域例外攔截與健康檢查端點。
- **影響分層**：

| 分層 | 受影響模組（職責描述） | 變更類型 |
| :--- | :--- | :--- |
| Controller | HealthController — 提供服務存活與健康狀態檢查端點 | 新增 |
| Common / Advice | GlobalExceptionHandler — 統一攔截系統例外並轉換為標準錯誤結構 | 新增 |
| Common / Response | ApiResponse — 定義全域統一 API 回應封裝結構 | 新增 |
| Config | WebMvcConfig / AppConfig — 基礎 Web 與系統參數配置 | 新增 |

## 3. 核心業務邏輯 (Core Logic)

### 3.1 統一 API 回應封裝結構 (Standard API Envelope)
- **結構公式**：`Response = { code: Integer, message: String, data: T, timestamp: Long }`
- **業務規則**：
  - 成功狀態碼：`code = 0`，`message = "success"`，`data` 為實質負載物件。
  - 業務失敗狀態碼：`code > 0`，`message` 為錯誤原因描述，`data = null`。
  - 時間戳記：`timestamp` 為 UTC 毫秒時間戳。

### 3.2 全域異常攔截與狀態映射 (Global Exception Handling)
- **異常分類與處理流轉**：
  - **自訂業務異常 (BusinessException)**：擷取業務錯誤代碼與訊息，HTTP 狀態碼維持 `200 OK` 或對應業務狀態，回傳對應業務錯誤結構。
  - **參數驗證異常 (MethodArgumentNotValidException / BindException)**：擷取欄位驗證錯誤清單，HTTP 狀態碼為 `400 Bad Request`，錯誤碼為 `40001`。
  - **資源不存在異常 (NoHandlerFoundException / ResourceNotFoundException)**：HTTP 狀態碼為 `404 Not Found`，錯誤碼為 `40401`。
  - **未捕捉系統異常 (Throwable / Exception)**：HTTP 狀態碼為 `500 Internal Server Error`，錯誤碼為 `50000`，隱藏內部堆疊資訊，回傳通用錯誤訊息 `"Internal Server Error"`。

### 3.3 健康檢查 (Health Check)
- **檢查邏輯**：
  - 接收檢查請求後，驗證應用程式運作狀態。
  - 正常狀態回傳狀態字串 `"UP"` 與系統當前時間戳記。

## 4. 資料架構 (Data Schema)
本階段僅建立 Spring Boot 基礎骨架與環境連線配置規範，暫無實體資料表變更（資料表結構於後續短網址業務工單建立）。

## 5. 介面合約 (API Contract)

### 5.1 REST API — 健康檢查端點 (Health Check)
- **Endpoint**: `GET /api/v1/health`
- **Request Body**: 無
- **Success Response (200)**:
```json
{
  "code": 0,
  "message": "success",
  "data": {
    "status": "UP",
    "timestamp": 1757116800000
  },
  "timestamp": 1757116800000
}
```

### 5.2 REST API — 通用錯誤回應結構 (Error Contract)
- **Error Responses**:

| HTTP Code | Error Code (`code`) | 條件 | 回傳 Message | 回傳 Data |
| :--- | :--- | :--- | :--- | :--- |
| 400 | 40001 | 請求參數格式錯誤或校驗未通過 | `Invalid request parameters: [field errors]` | `null` |
| 404 | 40401 | 存取不存在之 API 路徑 | `Resource not found` | `null` |
| 405 | 40501 | 不支援之 HTTP Method | `Method not allowed` | `null` |
| 500 | 50000 | 系統內部未預期異常 | `Internal server error` | `null` |

- **400 Bad Request 範例**:
```json
{
  "code": 40001,
  "message": "Invalid request parameters: [url must not be blank]",
  "data": null,
  "timestamp": 1757116800000
}
```

- **500 Internal Server Error 範例**:
```json
{
  "code": 50000,
  "message": "Internal server error",
  "data": null,
  "timestamp": 1757116800000
}
```

## 6. 技術決策與權衡 (Design Decisions)

### 6.1 語言版本與框架版本選型
- **選項 A**：Java 21 + Spring Boot 3.x
- **選項 B**：Java 17 + Spring Boot 3.x
- **決策**：選擇 A。Java 21 為最新 LTS 版本且完整支援虛擬執行緒 (Virtual Threads / JEP 444)，能為縮網址服務的高併發 I/O（短碼轉址、快取查詢、計數統計）提供極高吞吐量且無須複雜非同步程式碼；Spring Boot 3.x 則提供現代化 Jakarta EE 規範支援。
- **風險**：執行環境與建置工具鏈需配置 JDK 21。

### 6.2 建置工具選型
- **選項 A**：Maven
- **選項 B**：Gradle
- **決策**：選擇 A。Maven 依賴宣告明確、生命週期標準化且團隊維護成本低。
- **風險**：多模組建置速度略慢於 Gradle，但縮網址專案規模下差異極小。

### 6.3 全域回應結構封裝策略
- **選項 A**：Controller 顯式回傳 `ApiResponse<T>`
- **選項 B**：使用 `ResponseBodyAdvice` 自動攔截並包裝純 Object
- **決策**：選擇 A。顯式宣告回傳型態於 OpenAPI/Swagger 文件生成與靜態型態檢查上更為透明清晰，避免 String 序列化衝突問題。
- **風險**：Controller 需額外呼叫 `ApiResponse.success(data)` 封裝，程式碼稍微增加，但可讀性高。

## 7. 驗證目標與測試數據 (Verification Goals & Test Data)

### 7.1 單元測試測項 (Table-Driven)

| Case | 輸入 (Input) | 條件 (Condition) | 期望輸出 (Expected) |
| :--- | :--- | :--- | :--- |
| 成功回應封裝 | `data: "pong"` | 呼叫 `ApiResponse.success("pong")` | `code: 0, message: "success", data: "pong"` |
| 業務異常封裝 | `code: 40001, msg: "bad req"` | 拋出 `BusinessException(40001, "bad req")` | `code: 40001, message: "bad req", data: null` |
| 空物件成功封裝 | `data: null` | 呼叫 `ApiResponse.success()` | `code: 0, message: "success", data: null` |

### 7.2 業務驗收情境 (Acceptance Criteria)
- **情境 A：健康檢查端點**：發送 `GET /api/v1/health` 請求，期望 HTTP 狀態碼為 `200`，回應 JSON 包含 `code = 0`、`message = "success"` 且 `data.status = "UP"`。
- **情境 B：未知路徑攔截**：發送 `GET /api/v1/non-existent-path` 請求，期望 HTTP 狀態碼為 `404`，回應 JSON 包含 `code = 40401` 與 `data = null`。
- **情境 C：未處理例外攔截**：觸發未預期之系統 RuntimeException，期望 HTTP 狀態碼為 `500`，回應 JSON 包含 `code = 50000`、`message = "Internal server error"` 且 `data = null`。

## 8. 預期效益 (Expected Benefits)
- 統一錯誤碼與回應標準，減少前後端與外部串接整合成本 50% 以上。
- 奠定分層架構規範，使後續縮網址核心邏輯（編碼、快取、重定向）開發可直接套用現有結構。

## 9. Backlog / Future Scope（下期規劃）
- **[STEP3-URL_Shorten_Core]**：實作短網址核心生成邏輯（Base62 編碼 / Hash 衝突處理）與資料持久化（下期）。
- **[STEP4-URL_Redirect_Cache]**：實作短碼 302/301 轉址與 Redis 快取層（下期）。
- **[STEP5-Access_Metrics]**：實作非同步存取統計與點擊計數器（下期）。
