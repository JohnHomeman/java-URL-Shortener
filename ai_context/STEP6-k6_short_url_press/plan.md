# 執行計畫: STEP6-k6_short_url_press 短網址轉址高併發壓力測試

> <!-- [IMPORTANT] 本文件為施工地圖 (The How)。定義施工步驟與驗證動作。嚴禁重複定義邏輯或資料結構，一律以「Spec §N.N」格式引用。 -->
> <!-- ⚠️ 狀態管理：產出者應保持所有 Checkbox 為 [ ]。勾選動作 [x] 僅保留給「最終實作執行者」。 -->
> <!-- 職責邊界：Plan 只回答「改哪些檔案、按什麼順序做、怎麼跑驗證」。 -->
> <!-- ❌ 禁止出現在 Plan 中：新的期望數字、SQL Schema 、API Response 結構、業務計算公式。這些是 Spec 的內容，此處只能引用。 -->

## 1. 實作階段 (Implementation Stages)

### Stage 1: 環境確認與依賴安裝 (Environment Setup)

- [x] 確認 k6 已安裝（`k6 version`）；若未安裝，執行 `brew install k6`（macOS）。
- [x] 確認 Redis 與 MySQL 服務正常運作（`docker-compose ps` 或直接連線測試）。
- [x] **⚠️ 測試前純淨環境重置標準流程 (Pre-test Clean Reset)**：
  - 1. 清空 MySQL：`mysql -u root -p123456 -e "TRUNCATE TABLE java_project.short_urls; TRUNCATE TABLE java_project.access_logs;"`
  - 2. 清空 Redis：`redis-cli -a redis123456 FLUSHDB`
  - 3. 重啟 Spring Boot：殺死既有 Java 行程後執行 `./mvnw spring-boot:run`，並確認 `GET http://localhost:8080/actuator/health` 回應 `UP`。
- [x] 建立腳本存放目錄 `ai_context/STEP6-k6_short_url_press/workspace/`。
- **✅ Checkpoint**：`k6 version` 正常輸出版本號，應用程式健康檢查回應 200，服務全新重啟且 MySQL 與 Redis 處於純淨初始狀態。

---

### Stage 2: 撰寫 k6 壓測腳本 (Script Authoring)

- [x] [NEW] `ai_context/STEP6-k6_short_url_press/workspace/short_url_press.js`：依據 Spec §3.1 流程設計與 Spec §3.3 負載曲線規格撰寫完整 k6 腳本，包含：
  - `setup()` 函式：引用 Spec §5.1 呼叫 `POST /api/v1/urls/shorten` 50 次，收集 Spec §4.1 定義的 `{ shortKey, originalUrl }` 清單，並驗證 Spec §7.2 情境 D 的 Setup 完整性（長度為 50 且欄位非空）。
  - 自訂指標宣告：引用 Spec §4.2 宣告 `location_correct`（`Rate`）與 `redirect_duration`（`Trend`）。
  - `options.scenarios` / `options.stages`：引用 Spec §3.3 設定四段負載曲線（Warm-up / Ramp-up / Sustain / Ramp-down）。
  - `options.thresholds`：引用 Spec §3.4 設定四項閾值（`http_req_failed`、`http_req_duration (p95<200, p99<500)`、`location_correct`）。
  - 主體函式 `default(data)`：引用 Spec §3.2 實作隨機選碼邏輯（`Math.random()`），以 `redirects: 0` 參數（引用 Spec §6.1 決策）發送 `GET /{shortKey}`，並對 Spec §5.2 的兩個驗證點（status 302、Location Header）進行斷言，同步累加 `location_correct` 與 `redirect_duration`。
- **✅ Checkpoint**：執行 `k6 inspect ai_context/STEP6-k6_short_url_press/workspace/short_url_press.js` 無語法錯誤。

---

### Stage 3: 試跑冒煙測試 (Smoke Run)

- [x] 以極低負載試跑腳本（1 VU × 10s），確認 setup 資料建立成功、轉址驗證邏輯可正常執行：
  ```bash
  k6 run --vus 1 --duration 10s \
    ai_context/STEP6-k6_short_url_press/workspace/short_url_press.js
  ```
- [x] 確認 k6 輸出中 `http_req_failed` 為 0%，`location_correct` 為 100%，無腳本錯誤（`ERRO`）。
- **✅ Checkpoint**：冒煙測試通過，所有驗證點輸出符合 Spec §7.1 正常轉址測項期望。

---

### Stage 4: 執行完整全域壓測 (Full Load Test)

- [x] **執行前環境重置**：清空 MySQL、清空 Redis、重啟 Spring Boot 服務（確保日誌級別為 `INFO`）。
- [x] 執行引用 Spec §3.3 負載曲線的完整壓測，並將結果輸出至 JSON 報告：
  ```bash
  k6 run \
    --out json=ai_context/STEP6-k6_short_url_press/workspace/result.json \
    ai_context/STEP6-k6_short_url_press/workspace/short_url_press.js
  ```
- [x] 壓測結束後記錄 k6 摘要輸出（終端機最後的 Summary 區塊），確認各項 Threshold 狀態（引用 Spec §3.4），並記錄至驗收報告 `workspace/test_01_full_load.md`。
- **✅ Checkpoint**：所有指標數值與斷言完整記錄。

---

### Stage 5: 熱 Key 專項壓測與快取觀察 (Hot-Key Load Test & Cache Observation)

- [x] **執行前環境重置**：清空 MySQL、清空 Redis、重啟 Spring Boot 服務。
- [x] [NEW] `ai_context/STEP6-k6_short_url_press/workspace/short_url_press_hotkey.js`：依 Spec §6.2 決策設計熱 Key 專屬腳本，`setup()` 僅建立 1 組標示為 `/target/hot_key_test` 的短網址，主體 200 VU 全數針對該單一短碼發送請求。
- [x] 執行熱 Key 模式壓測（Sustain 2 分鐘，200 VU）並記錄結果：
  ```bash
  k6 run \
    --out json=ai_context/STEP6-k6_short_url_press/workspace/result_hotkey.json \
    ai_context/STEP6-k6_short_url_press/workspace/short_url_press_hotkey.js
  ```
- [x] 於壓測期間與結束後連線 Redis 執行命中率觀察指令（`INFO stats`），並查詢 MySQL `short_urls` 點擊持久化數據，確認期望值引用 Spec §7.2 情境 C。
- **✅ Checkpoint**：引用 Spec §7.2 情境 C 的觀察項目紀錄完整寫入 `workspace/test_02_hotkey_obs.md`。

---

## 2. 風險與注意事項 (Risks & Notes)

- [Stage 2 相關] k6 預設會將 HTTP 3xx 視為成功回應（非失敗），但需在 `options.thresholds` 中確保 `http_req_failed` 只計算非 2xx/3xx，避免誤計；引用 Spec §6.1 決策 B 說明的風險——需手動在 checks 中排除 302 被計為失敗的情境。
- [Stage 2 相關] `location_correct` 自訂指標需使用 k6 `Rate` 型別（`new Rate('location_correct')`），且 `threshold` 寫法為 `{ location_correct: ['rate==1'] }`；引用 Spec §6.3 決策 B 的嚴苛閾值設計，任一 Location 不符即整批判定失敗。
- [Stage 3 相關] 冒煙測試若出現大量 `connection refused` 或 `dial tcp ... refused`，代表應用程式未正常啟動，需先確認 Stage 1 Checkpoint 後再重試。
- [Stage 4 相關] 若本機環境 P95 延遲明顯超過 Spec §3.4 的 200ms 閾值（例如 JVM 尚未充分 JIT 熱身），可先單獨跑 Warm-up 階段數分鐘再啟動完整壓測，而非直接調高 threshold（閾值為驗收標準，不應因本機硬體問題放寬）。
- [Stage 4 相關] 若 200 VU 造成本機連線池耗盡（日誌出現 `HikariPool ... timeout`），可依 Spec §3.3 調整建議將 Sustain VU 調低至 50～100，閾值判準不變。

---

## 3. 驗證動作 (Verification Actions)

### 3.1 腳本語法檢查
```bash
k6 inspect ai_context/STEP6-k6_short_url_press/workspace/short_url_press.js
```

### 3.2 冒煙測試指令
```bash
k6 run --vus 1 --duration 10s \
  ai_context/STEP6-k6_short_url_press/workspace/short_url_press.js
```

### 3.3 完整壓測指令
```bash
k6 run \
  --out json=ai_context/STEP6-k6_short_url_press/workspace/result.json \
  ai_context/STEP6-k6_short_url_press/workspace/short_url_press.js
```

### 3.4 手動驗收步驟
> 驗收情境來源：引用 Spec §7.2。
> 腳本存放：`ai_context/STEP6-k6_short_url_press/workspace/`。

#### 情境 A 驗收：正確性驗收
1. 確認 Stage 1 環境 Checkpoint 通過。
2. 執行 Stage 4 完整壓測指令。
3. 壓測結束後，確認 k6 退出碼為 `0`，三項 threshold 全部通過，期望值引用 Spec §7.2 情境 A。
4. 將終端機 Summary 截圖或複製至 `workspace/test_01_full_load.md`。

#### 情境 B 驗收：穩定性驗收（Sustain 階段）
1. 在 Stage 4 完整壓測執行期間（Sustain 段，第 90s～210s），觀察 k6 即時輸出的 `http_req_duration` 與 `http_req_failed` 趨勢。
2. 確認延遲無明顯線性爬升、錯誤率全程 < 1%，期望值引用 Spec §7.2 情境 B。
3. 如需更細緻分析，可解析 `workspace/result.json` 並依時間分段計算 P95。

#### 情境 C 驗收：熱 Key 壓測與快取觀察
1. 確認已完成 Stage 5 步驟。
2. 執行熱 Key 模式壓測完畢後，連線 Redis 執行：
   ```bash
   redis-cli INFO stats | grep -E "keyspace_hits|keyspace_misses"
   ```
3. 確認 `status: 302` 全程維持、命中率觀察結果，期望值引用 Spec §7.2 情境 C，完整紀錄寫入 `workspace/test_02_hotkey_obs.md`。

#### 情境 D 驗收：Setup 資料完整性
1. 執行冒煙測試（Stage 3）或完整壓測（Stage 4）。
2. 確認 k6 輸出無 `setup failed` 或 `shortUrlList is empty` 等錯誤日誌。
3. 確認 setup 回傳清單長度為 50 且無空值，期望值引用 Spec §7.2 情境 D。
