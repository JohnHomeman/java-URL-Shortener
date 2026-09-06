# 驗收報告: test_01_unit_tests

## 執行時間
- 2026-09-06

## 驗證指令
```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./mvnw clean test
```

## 執行結果摘要
```text
[INFO] Running com.urlshortener.common.response.ApiResponseTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.052 s -- in com.urlshortener.common.response.ApiResponseTest
[INFO] Running com.urlshortener.controller.HealthControllerTest
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.655 s -- in com.urlshortener.controller.HealthControllerTest
[INFO] Running com.urlshortener.common.exception.GlobalExceptionHandlerTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.181 s -- in com.urlshortener.common.exception.GlobalExceptionHandlerTest
[INFO] Running com.urlshortener.UrlShortenerApplicationTests
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.082 s -- in com.urlshortener.UrlShortenerApplicationTests
[INFO] 
[INFO] Results:
[INFO] 
[INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0
[INFO] 
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
```

## 驗收情境核對 (Spec §7)
- **§7.1 單元測試**：全部通過 (4/4 passed)
- **§7.2 情境 A（健康檢查 GET /api/v1/health）**：通過，回傳 HTTP 200，code=0，data.status="UP"。
- **§7.2 情境 B（404 未知路徑攔截）**：通過，回傳 HTTP 404，code=40401，message="Resource not found"。
- **§7.2 情境 C（500 未處理例外攔截）**：通過，回傳 HTTP 500，code=50000，message="Internal server error"。
