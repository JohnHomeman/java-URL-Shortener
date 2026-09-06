# 框架與基礎架構模組現狀 (Framework Logic)

## 1. 模組簡介
定義專案通用基礎設施，包含 Java 21 + Spring Boot 3.3.5 執行基底、全域異常攔截、統一 API 回應格式與健康檢查。

## 2. 全域回應協議
```json
{
  "code": 0,
  "message": "success",
  "data": {},
  "timestamp": 1757116800000
}
```

## 3. 全域異常對應
| HTTP 狀態碼 | 錯誤代碼 (code) | 說明 |
| :--- | :--- | :--- |
| 400 | 40001 | 請求參數驗證失敗 (MethodArgumentNotValidException) |
| 404 | 40401 | 資源或路徑不存在 (NoResourceFoundException / NoHandlerFoundException) |
| 405 | 40501 | HTTP 方法不支援 (HttpRequestMethodNotSupportedException) |
| 500 | 50000 | 伺服器未捕捉異常 (Throwable) |

## 4. 基礎端點
- `GET /api/v1/health`：服務健康狀態檢查。
